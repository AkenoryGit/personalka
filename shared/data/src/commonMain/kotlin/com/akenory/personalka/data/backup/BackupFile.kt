package com.akenory.personalka.data.backup

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64

/** Таблица базы в копии: имена столбцов и строки значений в том же порядке. */
internal class BackupTable(val columns: List<String>, val rows: List<List<JsonElement>>) {
    /** Сколько строк без отметки об удалении: «34 клиента» в предпросмотре. */
    fun liveRows(): Int {
        val deleted = columns.indexOf("deletedAt")
        return if (deleted < 0) rows.size else rows.count { it[deleted] is JsonNull }
    }
}

/** Содержимое копии: когда снята, версия схемы базы и все таблицы. */
internal class BackupData(val createdAt: LocalDateTime, val schemaVersion: Int, val tables: Map<String, BackupTable>)

/**
 * Параметры Argon2id: 19 МиБ памяти и 2 прохода — меньше секунды на телефоне, а перебор паролей — долгий.
 * Пишутся в описание копии: следующие версии могут взять другие, а старые копии откроются со своими.
 */
data class KdfParams(val memoryKiB: Int = 19 * 1024, val iterations: Int = 2, val parallelism: Int = 1)

/** Ключ шифрования из пароля с его солью. Хранится на телефоне, чтобы автокопия шифровалась без вопроса о пароле. */
class BackupKey(val salt: ByteArray, val key: ByteArray, val params: KdfParams) {
    /** Строкой для настроек телефона. */
    fun encode(): String = listOf(
        b64(salt), b64(key), params.memoryKiB.toString(), params.iterations.toString(), params.parallelism.toString(),
    ).joinToString(";")

    companion object {
        /** Новый ключ из [password] со свежей солью — медленно, нарочно (Argon2id). */
        fun fromPassword(password: String, params: KdfParams = KdfParams()): BackupKey =
            derive(password, BackupPlatform.randomBytes(16), params)

        internal fun derive(password: String, salt: ByteArray, params: KdfParams): BackupKey = BackupKey(
            salt = salt,
            key = Argon2id.hash(password.encodeToByteArray(), salt, params.memoryKiB, params.iterations, params.parallelism, 32),
            params = params,
        )

        fun decode(text: String): BackupKey? = runCatching {
            val (salt, key, memory, iterations, parallelism) = text.split(";")
            BackupKey(Base64.decode(salt), Base64.decode(key), KdfParams(memory.toInt(), iterations.toInt(), parallelism.toInt()))
        }.getOrNull()
    }
}

/** Что вышло при открытии файла копии. */
internal sealed interface Unpacked {
    /** Копия открыта; [key] — ключ, которым она зашифрована (`null` — без пароля). */
    class Opened(val data: BackupData, val key: BackupKey?) : Unpacked

    /** Зашифрована — нужен пароль. */
    data object NeedPassword : Unpacked

    data object WrongPassword : Unpacked

    /** Не копия «Персоналки» или файл повреждён. */
    data object NotBackup : Unpacked
}

/**
 * Файл копии (ТЗ 7.6): zip с описанием `backup.json` (версия схемы, число записей по таблицам, SHA-256 данных,
 * параметры шифра) и данными — `data.json` или, с паролем, `data.bin` (AES-256-GCM, ключ — Argon2id из пароля).
 */
internal object BackupFile {
    const val FORMAT = "personalka-backup"
    private const val FORMAT_VERSION = 1
    private const val META = "backup.json"
    private const val DATA = "data.json"
    private const val SEALED = "data.bin"

    private val json = Json { prettyPrint = false }

    fun pack(data: BackupData, key: BackupKey?): ByteArray {
        val plain = json.encodeToString(JsonObject.serializer(), dataJson(data)).encodeToByteArray()
        val nonce = BackupPlatform.randomBytes(12)
        val meta = buildJsonObject {
            put("format", FORMAT)
            put("formatVersion", FORMAT_VERSION)
            put("schemaVersion", data.schemaVersion)
            put("createdAt", data.createdAt.toString())
            putJsonObject("records") { data.tables.forEach { (name, table) -> put(name, table.rows.size) } }
            put("sha256", hex(BackupPlatform.sha256(plain)))
            if (key != null) {
                putJsonObject("encryption") {
                    put("cipher", "AES-256-GCM")
                    put("kdf", "Argon2id")
                    put("memoryKiB", key.params.memoryKiB)
                    put("iterations", key.params.iterations)
                    put("parallelism", key.params.parallelism)
                    put("salt", b64(key.salt))
                    put("nonce", b64(nonce))
                }
            }
        }
        val metaBytes = json.encodeToString(JsonObject.serializer(), meta).encodeToByteArray()
        return BackupPlatform.zip(
            if (key == null) {
                mapOf(META to metaBytes, DATA to plain)
            } else {
                mapOf(META to metaBytes, SEALED to BackupPlatform.seal(key.key, nonce, plain))
            },
        )
    }

    /**
     * Открыть файл: без пароля — пока не выяснится, что он нужен; [known] — ключ этого телефона, им копию
     * пробуют открыть, если соль совпала (своя копия открывается без пароля).
     */
    fun unpack(bytes: ByteArray, password: String?, known: BackupKey? = null): Unpacked {
        val files = BackupPlatform.unzip(bytes) ?: return Unpacked.NotBackup
        val meta = files[META]?.let { parse(it) } ?: return Unpacked.NotBackup
        if (meta["format"]?.jsonPrimitive?.content != FORMAT) return Unpacked.NotBackup
        val encryption = meta["encryption"] as? JsonObject
        var key: BackupKey? = null
        val plain = if (encryption == null) {
            files[DATA] ?: return Unpacked.NotBackup
        } else {
            val sealed = files[SEALED] ?: return Unpacked.NotBackup
            val salt = runCatching { Base64.decode(encryption.text("salt")) }.getOrNull() ?: return Unpacked.NotBackup
            val nonce = runCatching { Base64.decode(encryption.text("nonce")) }.getOrNull() ?: return Unpacked.NotBackup
            val params = runCatching {
                KdfParams(encryption.number("memoryKiB"), encryption.number("iterations"), encryption.number("parallelism"))
            }.getOrNull() ?: return Unpacked.NotBackup
            key = when {
                known != null && known.salt.contentEquals(salt) && known.params == params -> known
                password != null -> BackupKey.derive(password, salt, params)
                else -> return Unpacked.NeedPassword
            }
            BackupPlatform.open(key.key, nonce, sealed) ?: return if (password == null) Unpacked.NeedPassword else Unpacked.WrongPassword
        }
        if (meta["sha256"]?.jsonPrimitive?.content != hex(BackupPlatform.sha256(plain))) return Unpacked.NotBackup
        val data = parse(plain) ?: return Unpacked.NotBackup
        val createdAt = runCatching { LocalDateTime.parse(meta.text("createdAt")) }.getOrNull() ?: return Unpacked.NotBackup
        val schema = meta["schemaVersion"]?.jsonPrimitive?.intOrNull ?: return Unpacked.NotBackup
        val tables = runCatching {
            data.getValue("tables").jsonObject.mapValues { (_, t) ->
                val o = t.jsonObject
                BackupTable(
                    columns = o.getValue("columns").jsonArray.map { it.jsonPrimitive.content },
                    rows = o.getValue("rows").jsonArray.map { it.jsonArray.toList() },
                )
            }
        }.getOrNull() ?: return Unpacked.NotBackup
        return Unpacked.Opened(BackupData(createdAt, schema, tables), key)
    }

    private fun dataJson(data: BackupData): JsonObject = buildJsonObject {
        putJsonObject("tables") {
            data.tables.forEach { (name, table) ->
                put(
                    name,
                    JsonObject(
                        mapOf(
                            "columns" to JsonArray(table.columns.map { JsonPrimitive(it) }),
                            "rows" to JsonArray(table.rows.map { JsonArray(it) }),
                        ),
                    ),
                )
            }
        }
    }

    private fun parse(bytes: ByteArray): JsonObject? = runCatching { json.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrNull()

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.number(name: String): Int = getValue(name).jsonPrimitive.int
}

internal fun b64(bytes: ByteArray): String = Base64.encode(bytes)

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
