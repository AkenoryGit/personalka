package com.akenory.personalka.data.cabinet

import com.akenory.personalka.data.account.AccountException
import com.akenory.personalka.data.account.TokenStore
import com.akenory.personalka.data.account.string
import com.akenory.personalka.data.backup.BackupPlatform
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.domain.cabinet.CabinetLinkRules
import com.akenory.personalka.domain.cabinet.ConsentInfo
import com.akenory.personalka.domain.format.AccountFormat
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64

/**
 * Секреты по имени в защищённом хранилище телефона (3.7: `token_local`, `pin_local`): на Android — под ключом
 * Android Keystore, на iOS — в Keychain. Не в базе и не в копии.
 */
interface SecretStore {
    fun read(name: String): String?

    /** `null` — стереть. */
    fun write(name: String, value: String?)
}

/** Секреты в памяти — для тестов. */
class MemorySecrets : SecretStore {
    private val values = mutableMapOf<String, String>()

    override fun read(name: String): String? = values[name]

    override fun write(name: String, value: String?) {
        if (value == null) values.remove(name) else values[name] = value
    }
}

/**
 * Ссылка клиента. [url] — адрес для «Отправить»; `null` — ссылку выпустил другой телефон, токена здесь нет.
 * [pin] — код, если он известен этому телефону; [pinOn] — спрашивается ли код; [lastOpenedAt] — мс UTC.
 */
data class CabinetLink(val url: String?, val pin: String?, val pinOn: Boolean, val lastOpenedAt: Long?)

/**
 * Кабинет клиента в окне «Кабинет». [online] — сервер ответил; без связи [link] — то, что помнит телефон, и [problem] —
 * почему нет ответа. [consent] — последнее согласие (только при ответе сервера).
 */
data class CabinetStatus(
    val online: Boolean,
    val link: CabinetLink?,
    val consent: ConsentInfo?,
    val problem: String? = null,
)

/** Ответ на действие окна: сделано или текст ошибки для показа. */
sealed interface CabinetResult {
    data object Done : CabinetResult

    data class Failed(val message: String) : CabinetResult
}

/**
 * Ссылки кабинетов в приложении (ТЗ 5.9, 3.7, 7.1; шаг 2.13). Токен — 32 случайных байта в base64url — создаёт
 * телефон и хранит в [secrets] вместе с кодом: тренер может отправить ссылку и код ещё раз. На сервер уходит только
 * SHA-256 токена; код сервер хэширует сам. Выключатель «Кабинет открыт» — `client.cabinetEnabled`, он уходит
 * на сервер обычной синхронизацией.
 *
 * @param tokens токен сессии тренера — тот же, что у входа.
 */
class CabinetRepository(
    private val db: PersonalkaDatabase,
    private val api: CabinetApi,
    private val tokens: TokenStore,
    private val secrets: SecretStore,
    private val clock: () -> Long,
    private val random: (Int) -> ByteArray = BackupPlatform::randomBytes,
) {
    private val clients = db.clientDao()

    /** Ссылка и согласие клиента: с сервера, без связи — что помнит телефон. */
    suspend fun status(clientId: String): CabinetStatus {
        val local = local(clientId)
        val offline = CabinetStatus(false, local?.let { CabinetLink(url(it.token), it.pin, it.pin != null, null) }, null)
        val token = tokens.read() ?: return offline.copy(problem = AccountFormat.EXPIRED)
        val json = try {
            api.list(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountException) {
            return offline.copy(problem = e.message)
        }
        val server = (json["cabinets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.firstOrNull { it.string("client_id") == clientId }
        val consent = (json["consents"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { it.string("client_id") == clientId }?.let(::consent)
        if (server == null) {
            // Ссылку отозвали (отзыв согласия, «Отключиться от сервера») — помнить её незачем.
            if (local != null) secrets.write(key(clientId), null)
            return CabinetStatus(true, null, consent)
        }
        val mine = local?.takeIf { hash(it.token) == server.string("token_hash") }
        val pinOn = (server["pin"] as? JsonPrimitive)?.booleanOrNull ?: (mine?.pin != null)
        val link = CabinetLink(
            url = mine?.let { url(it.token) },
            pin = mine?.pin?.takeIf { pinOn },
            pinOn = pinOn,
            lastOpenedAt = (server["last_opened_at"] as? JsonPrimitive)?.longOrNull,
        )
        return CabinetStatus(true, link, consent)
    }

    /** «Выпустить ссылку» / «Выпустить новую ссылку»: прежняя перестаёт открываться. [withPin] — с новым кодом. */
    suspend fun issue(clientId: String, withPin: Boolean): CabinetResult = act { token ->
        val link = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(random(CabinetLinkRules.TOKEN_BYTES))
        val pin = if (withPin) CabinetLinkRules.pin(random(4)) else null
        api.issue(token, clientId, hash(link), pin)
        save(clientId, LocalLink(link, pin))
    }

    /** «Код доступа»: включить — новый код у той же ссылки; выключить — ссылка открывается без кода. */
    suspend fun setPin(clientId: String, on: Boolean): CabinetResult = act { token ->
        val pin = if (on) CabinetLinkRules.pin(random(4)) else null
        api.changePin(token, clientId, pin)
        local(clientId)?.let { save(clientId, it.copy(pin = pin)) }
    }

    /**
     * «Кабинет открыт»: отметка у клиента — запись в базе, на сервер её отвезёт синхронизация. Открыли, а ссылки
     * ещё нет — выпускается сразу, с кодом ([withPin]).
     */
    suspend fun setOpen(clientId: String, open: Boolean, withPin: Boolean): CabinetResult {
        val client = clients.client(clientId) ?: return CabinetResult.Done
        if (client.cabinetEnabled != open) {
            clients.upsertClient(client.copy(sync = client.sync.touched(clock()), cabinetEnabled = open))
        }
        if (!open) return CabinetResult.Done
        val status = status(clientId)
        return if (status.online && status.link == null) issue(clientId, withPin) else CabinetResult.Done
    }

    /** Адрес для родителя по токену. */
    private suspend fun url(token: String): String = CabinetLinkRules.url(api.server(), token)

    private suspend fun act(block: suspend (String) -> Unit): CabinetResult {
        val token = tokens.read() ?: return CabinetResult.Failed(AccountFormat.EXPIRED)
        return try {
            block(token)
            CabinetResult.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountException) {
            CabinetResult.Failed(e.message)
        }
    }

    private data class LocalLink(val token: String, val pin: String?)

    /** В хранилище — «токен» или «токен:код»: в base64url двоеточия нет. */
    private fun local(clientId: String): LocalLink? = secrets.read(key(clientId))?.takeIf { it.isNotEmpty() }?.let {
        LocalLink(it.substringBefore(':'), it.substringAfter(':', "").ifEmpty { null })
    }

    private fun save(clientId: String, link: LocalLink) {
        secrets.write(key(clientId), link.token + (link.pin?.let { ":$it" } ?: ""))
    }

    private fun consent(json: JsonObject): ConsentInfo? {
        val accepted = (json["accepted_at"] as? JsonPrimitive)?.longOrNull ?: return null
        return ConsentInfo(
            acceptedAt = accepted,
            photo = (json["photo"] as? JsonPrimitive)?.booleanOrNull == true,
            givenBy = json.string("given_by_name"),
            revokedAt = (json["revoked_at"] as? JsonPrimitive)?.longOrNull,
        )
    }

    internal companion object {
        fun key(clientId: String) = "cabinet.$clientId"

        /** SHA-256 токена в base64url без «=» — как его ждёт сервер (7.1). */
        fun hash(token: String): String =
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(BackupPlatform.sha256(token.encodeToByteArray()))
    }
}
