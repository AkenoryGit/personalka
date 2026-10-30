package com.akenory.personalka.data.sync

import androidx.room3.PooledConnection
import androidx.room3.deferredTransaction
import androidx.room3.immediateTransaction
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import com.akenory.personalka.data.account.AccountException
import com.akenory.personalka.data.account.TokenStore
import com.akenory.personalka.data.account.string
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.entity.SyncOutboxEntity
import com.akenory.personalka.data.prefs.DevicePrefs
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.format.AccountFormat
import com.akenory.personalka.domain.sync.SyncProblem
import com.akenory.personalka.domain.sync.SyncRules
import com.akenory.personalka.domain.sync.SyncStamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Что на сервере перед подключением: данных тренера нет, они есть (с другого телефона) или сервер не ответил. */
enum class ServerData { EMPTY, HAS_DATA, FAILED }

/**
 * Что вышло, когда после входа включалась копия на сервере (шаг 5.1а, ТЗ 1.5): [Done] — копия включена;
 * [ServerHasData] — на телефоне свои данные, ещё не бывавшие на сервере, а у аккаунта на сервере уже есть данные —
 * слить их нельзя (двойные типы и места), можно только заменить данные телефона; [OtherAccount] — на телефоне данные
 * другого аккаунта, [OtherAccount.complete] — все они на сервере, и их можно убрать; [Failed] — причина в [SyncState].
 */
sealed interface Attach {
    data object Done : Attach

    data object ServerHasData : Attach

    data class OtherAccount(val email: String, val complete: Boolean) : Attach

    data object Failed : Attach
}

/**
 * Перед «Выйти» (шаг 5.1а): [NO_COPY] — копии этого аккаунта на телефоне нет, выход как раньше; [SENT] — все правки
 * на сервере, данные с телефона можно убрать; [NOT_SENT] — не всё ушло (нет связи, сервер отказал), стирать нельзя.
 */
enum class SignOutCheck { NO_COPY, SENT, NOT_SENT }

/** Первая выгрузка (7.2, п. 5): отправлено [sent] записей из [total] — для полосы прогресса. */
data class SyncProgress(val sent: Int, val total: Int)

/**
 * Что знает о синхронизации этот телефон: [connected] — сервер подключён на этом телефоне; [lastSync] — момент
 * последнего полного обмена (миллисекунды UTC); [failures] — ошибок связи подряд, по ним пауза повтора; [error] — текст
 * сбоя при [SyncProblem.FAILED].
 */
data class SyncState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val progress: SyncProgress? = null,
    val lastSync: Long? = null,
    val problem: SyncProblem? = null,
    val failures: Int = 0,
    /** Текст ошибки при [SyncProblem.FAILED] — чтобы тренер мог его прислать. */
    val error: String? = null,
    /** Обменов, закончившихся удачно, с запуска приложения: по нему экраны узнают о каждом обмене (шаг 5.3з). */
    val completed: Int = 0,
)

/**
 * Синхронизация телефона с сервером (ТЗ 7.2, 1.5; шаг 2.10): очередь изменений ([SyncOutbox] — триггеры в базе),
 * отправка пачками до 500, получение страницами, «последнее изменение побеждает» ([SyncRules.applyIncoming]),
 * полная синхронизация после 410, подключение (первая выгрузка или данные с сервера) и отключение.
 *
 * Всё, что нужно только этому телефону — подключён ли сервер, номер последнего полученного изменения, id устройства, —
 * лежит в [prefs], а не в базе: в копию и на другой телефон не попадает. Обмены идут по одному ([busy]).
 *
 * Сервер подключается для одного аккаунта ([OWNER]). Вошли в другой — обмен и «Отключиться» не идут
 * ([SyncProblem.OTHER_ACCOUNT]): данные телефона и обоих аккаунтов на сервере не трогаются и не смешиваются.
 *
 * @param owner почта аккаунта, в который сейчас вошли, `null` — не вошли.
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class SyncRepository(
    private val db: PersonalkaDatabase,
    private val api: SyncApi,
    private val tokens: TokenStore,
    private val prefs: DevicePrefs,
    private val owner: () -> String? = { null },
    private val clock: () -> Long,
) {
    private val _state = MutableStateFlow(
        SyncState(connected = prefs.boolean(CONNECTED) == true, lastSync = prefs.string(LAST)?.toLongOrNull()),
    )
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Сколько записей ждёт отправки. */
    val pending: Flow<Int> = db.syncDao().observePending()

    /** Самая давняя правка, ждущая отправки (миллисекунды UTC). */
    val oldestPending: Flow<Long?> = db.syncDao().observeOldest()

    /** То же числом сейчас. */
    suspend fun pendingNow(): Int = db.syncDao().pending()

    private val busy = Mutex()

    /** Постоянный id этого устройства для сервера (7.2): создаётся один раз и больше не меняется. */
    val device: String
        get() = prefs.string(DEVICE) ?: Ids.newId(clock()).also { prefs.putString(DEVICE, it) }

    /**
     * Подключить сервер на этом телефоне (1.5, 7.2, п. 5). Обычно — первая выгрузка: вся база ложится в очередь
     * и уходит на сервер с полосой прогресса, потом приходит то, что на сервере новее. [takeFromServer] — новое
     * устройство: данные этого телефона заменяются данными с сервера (полное получение с нуля). Телефон стирается,
     * только когда сервер ответил первой страницей, — без связи и без открытой копии данные остаются.
     *
     * `true` — обмен прошёл до конца; иначе причина — в [state], а сервер остаётся подключённым и обмен продолжится сам,
     * кроме отказа сервера ([SyncProblem.CLOSED], [SyncProblem.SUSPENDED], [SyncProblem.SIGNED_OUT]) — тогда не подключается.
     */
    suspend fun connect(takeFromServer: Boolean = false): Boolean = busy.withLock {
        val token = tokens.read() ?: return@withLock fail(SyncProblem.SIGNED_OUT)
        if (takeFromServer) {
            val first = try {
                api.pull(token, SyncRules.SCHEMA_VERSION, since = 0, limit = SyncRules.PULL_DEFAULT)
            } catch (e: AccountException) {
                return@withLock failed(e)
            }
            // Страница не легла в базу — транзакция откатилась, телефон не стёрт и сервер не подключён.
            try {
                db.useWriterConnection<Unit> { connection ->
                    connection.immediateTransaction {
                        SyncOutbox.clearAll(this)
                        apply(this, first)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock broken(e)
            }
            setConnected(true)
            rememberOwner()
            prefs.putString(SENT, "")
            prefs.pullFromStart()
            prefs.putString(SINCE, lastSeq(first, 0).toString())
            if (first.flag("has_more")) return@withLock exchange(token, push = false)
            return@withLock done()
        }
        db.useWriterConnection { connection -> connection.immediateTransaction { SyncOutbox.requeueAll(this) } }
        prefs.pullFromStart()
        setConnected(true)
        rememberOwner()
        prefs.putString(SENT, "")
        val total = db.syncDao().pending()
        _state.update { it.copy(progress = SyncProgress(0, total)) }
        val ok = exchange(token, push = true)
        if (!ok && _state.value.problem in REFUSED) setConnected(false)
        ok
    }

    /**
     * Вход = копия на сервере (шаг 5.1а, ТЗ 1.5): после входа в аккаунт с открытой копией. Телефон пустой — данные
     * приходят с сервера. Данные телефона — этого аккаунта (сервер подключали для него) — вся база снова уходит на
     * сервер, сервер оставляет новее: так копия на сервере снова полная, даже если её стирали с другого телефона
     * (ограничение 2.10а). Данные ничьи (сервер не подключали) — первая выгрузка, если на сервере пусто, иначе
     * [Attach.ServerHasData]. Данные другого аккаунта — [Attach.OtherAccount], ничего не трогается.
     */
    suspend fun attach(): Attach {
        val was = phoneOwner()
        val me = owner()
        if (!hasCoach()) return if (connect(takeFromServer = true)) Attach.Done else Attach.Failed
        if (was != null && me != null && !was.equals(me, ignoreCase = true)) return Attach.OtherAccount(was, complete())
        if (was != null) return if (connect()) Attach.Done else Attach.Failed
        return when (checkServer()) {
            ServerData.EMPTY -> if (connect()) Attach.Done else Attach.Failed
            ServerData.HAS_DATA -> Attach.ServerHasData
            ServerData.FAILED -> Attach.Failed
        }
    }

    /** Для какого аккаунта на телефоне данные: подключали сервер — его почта; подключённые до шага 3.9б — нынешний. */
    private fun phoneOwner(): String? =
        prefs.string(OWNER)?.takeIf { it.isNotEmpty() } ?: if (_state.value.connected) owner() else null

    /**
     * Все данные телефона — на сервере: при последнем «Выйти» всё ушло ([SENT]), сервер с тех пор подключён и новых
     * правок нет. Вышли без связи или сервер отказал (копию стёрли с другого телефона) — не полная.
     */
    private suspend fun complete(): Boolean = _state.value.connected && prefs.string(SENT) == "1" && pendingNow() == 0

    /** Есть ли на телефоне тренер — без него телефон пустой (первый запуск). */
    private suspend fun hasCoach(): Boolean = db.useReaderConnection { connection ->
        connection.usePrepared("SELECT 1 FROM coach LIMIT 1") { it.step() }
    }

    /**
     * Перед «Выйти» (шаг 5.1а): копия этого аккаунта на телефоне — обмен сейчас, чтобы на сервер ушли все правки.
     * Ушло всё — [SignOutCheck.SENT], и телефон запоминает, что его данные на сервере полные (вход в другой аккаунт
     * сможет их убрать).
     */
    suspend fun beforeSignOut(): SignOutCheck {
        if (!_state.value.connected) return SignOutCheck.NO_COPY
        val was = phoneOwner()
        val me = owner()
        if (was != null && me != null && !was.equals(me, ignoreCase = true)) return SignOutCheck.NO_COPY
        sync()
        val sent = pendingNow() == 0 && _state.value.problem !in REFUSED
        prefs.putString(SENT, if (sent) "1" else "")
        return if (sent) SignOutCheck.SENT else SignOutCheck.NOT_SENT
    }

    /**
     * «Убрать данные с телефона» (шаг 5.1а): все записи стираются, телефон — как после установки. Только когда
     * ничего не ждёт отправки, иначе `false` и всё остаётся: правки, которых нет на сервере, не стираются.
     */
    suspend fun wipePhone(): Boolean = busy.withLock {
        if (db.syncDao().pending() > 0) return@withLock false
        db.useWriterConnection { connection -> connection.immediateTransaction { SyncOutbox.clearAll(this) } }
        setConnected(false)
        prefs.putString(OWNER, "")
        prefs.putString(SENT, "")
        prefs.putString(LAST, "")
        prefs.pullFromStart()
        _state.update { SyncState() }
        true
    }

    /**
     * Перед подключением (шаг 2.10а): есть ли на сервере данные этого тренера — тогда обычная выгрузка дала бы
     * двойные типы и места, и телефону предлагается взять данные с сервера. Данные клуба не в счёт. Сервер не
     * ответил или отказал — [ServerData.FAILED], причина в [state].
     */
    suspend fun checkServer(): ServerData = busy.withLock {
        val token = tokens.read() ?: return@withLock ServerData.FAILED.also { fail(SyncProblem.SIGNED_OUT) }
        val page = try {
            api.pull(token, SyncRules.SCHEMA_VERSION, since = 0, limit = SyncRules.PULL_MAX)
        } catch (e: AccountException) {
            failed(e)
            return@withLock ServerData.FAILED
        }
        _state.update { it.copy(problem = null) }
        val changes = (page["changes"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val coach = changes.any { change ->
            val data = change["data"] as? JsonObject
            val entity = change.string("entity").orEmpty()
            !data.isEmptyRow() && !SyncRules.isClub(entity, data?.string("scope"))
        }
        // Целая страница одних данных клуба — редкость; считаем, что дальше могут быть данные тренера.
        if (coach || page.flag("has_more")) ServerData.HAS_DATA else ServerData.EMPTY
    }

    /**
     * Обмен (7.2, п. 4): сначала отправка всей очереди, потом получение всего нового. Сервер не подключён — ничего.
     * `false` — причина в [state].
     */
    suspend fun sync(): Boolean = busy.withLock {
        if (!_state.value.connected) return@withLock false
        val token = tokens.read() ?: return@withLock fail(SyncProblem.SIGNED_OUT)
        if (otherAccount()) return@withLock false
        exchange(token, push = true)
    }

    /**
     * Вошли не в тот аккаунт, для которого подключали сервер: обмена нет — иначе правки ушли бы в чужой аккаунт, а его
     * данные пришли бы в этот телефон. Подключение и номер получения не трогаются: вернулись в свой — обмен идёт дальше.
     * Подключённые до шага 3.9б запоминают аккаунт здесь.
     */
    private fun otherAccount(): Boolean {
        val now = owner() ?: return false
        val was = prefs.string(OWNER)?.takeIf { it.isNotEmpty() }
        if (was == null) prefs.putString(OWNER, now)
        if (was == null || was.equals(now, ignoreCase = true)) return false
        fail(SyncProblem.OTHER_ACCOUNT)
        return true
    }

    private fun rememberOwner() = prefs.putString(OWNER, owner().orEmpty())

    /**
     * «Удалить копию с сервера» (1.5, шаг 5.1а): данные тренера на сервере стираются, сервер закрывает все его входы,
     * на телефоне всё остаётся, обмен больше не идёт. Телефон помнит, чьи у него данные: вход в этот же аккаунт
     * соберёт копию заново, вход в другой — их не уберёт (их больше нигде нет). Без связи или под чужим аккаунтом —
     * `false`, сервер остаётся подключённым.
     */
    suspend fun disconnect(): Boolean = busy.withLock {
        val token = tokens.read() ?: return@withLock fail(SyncProblem.SIGNED_OUT)
        // Под чужим аккаунтом «Отключиться» стёрло бы на сервере его данные.
        if (otherAccount()) return@withLock false
        try {
            api.deleteData(token)
        } catch (e: AccountException) {
            return@withLock failed(e)
        }
        setConnected(false)
        prefs.putString(SENT, "")
        prefs.pullFromStart()
        _state.update { it.copy(problem = null, failures = 0, progress = null) }
        true
    }

    private suspend fun exchange(token: String, push: Boolean): Boolean {
        _state.update { it.copy(running = true) }
        try {
            if (push) push(token)
            pull(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountException) {
            return failed(e)
        } catch (e: Exception) {
            // Сбой в телефоне (база, разбор ответа) не должен ронять приложение: при каждом запуске обмен шёл бы снова
            // и снова ронял его. Страница откатывается целиком, номер получения не сдвигается — данные целы.
            return broken(e)
        } finally {
            _state.update { it.copy(running = false) }
        }
        return done()
    }

    /** Вся очередь — пачками; убирается только то, что сервер принял, счёл старым или отклонил как данные клуба. */
    private suspend fun push(token: String) {
        while (true) {
            val batch = db.syncDao().next(SyncRules.PUSH_MAX)
            if (batch.isEmpty()) return
            val changes = db.useReaderConnection { connection ->
                connection.deferredTransaction { batch.map { change(this, it) } }
            }
            val answer = api.push(token, SyncRules.SCHEMA_VERSION, device, changes)
            val results = answer["results"] as? JsonArray ?: throw serverError()
            var removed = 0
            batch.zip(results).forEach { (item, result) ->
                if ((result as? JsonObject)?.string("status") in DONE_STATUSES) {
                    db.syncDao().sent(item.entity, item.rowId, item.updatedAt)
                    removed++
                }
            }
            _state.update { s -> s.progress?.let { s.copy(progress = it.copy(sent = (it.sent + removed).coerceAtMost(it.total))) } ?: s }
            // Сервер ничего не принял — ошибка в коде, а не в связи: без этого очередь крутилась бы бесконечно.
            if (removed == 0) throw serverError()
        }
    }

    /** Изменение для сервера: запись целиком; стёртая совсем — удаление с пустыми данными (3.1). */
    private suspend fun change(connection: PooledConnection, item: SyncOutboxEntity): JsonObject {
        val row = SyncOutbox.read(connection, item.entity, item.rowId)
        if (row == null) {
            // Часы телефона могут отставать от момента самой записи — удаление всё равно не старше её.
            val now = maxOf(clock(), item.updatedAt)
            return JsonObject(
                mapOf(
                    "entity" to JsonPrimitive(item.entity),
                    "id" to JsonPrimitive(item.rowId),
                    "version" to JsonPrimitive(0),
                    "updated_at" to JsonPrimitive(now),
                    "deleted_at" to JsonPrimitive(now),
                    "data" to JsonObject(emptyMap()),
                ),
            )
        }
        return JsonObject(
            mapOf(
                "entity" to JsonPrimitive(item.entity),
                "id" to JsonPrimitive(item.rowId),
                "version" to (row["version"] ?: JsonPrimitive(1)),
                "updated_at" to (row["updatedAt"] ?: JsonPrimitive(item.updatedAt)),
                "deleted_at" to (row["deletedAt"] ?: JsonNull),
                "data" to row,
            ),
        )
    }

    /** Всё новое — страницами; номер последнего полученного запоминается после каждой. 410 — с начала (7.2, п. 6). */
    private suspend fun pull(token: String) {
        var restarted = false
        while (true) {
            val since = prefs.string(SINCE)?.toLongOrNull() ?: 0
            val page = try {
                api.pull(token, SyncRules.SCHEMA_VERSION, since, SyncRules.PULL_DEFAULT)
            } catch (e: AccountException) {
                if (e.code != SyncApi.FULL_SYNC || restarted) throw e
                restarted = true
                prefs.pullFromStart()
                continue
            }
            db.useWriterConnection { connection -> connection.immediateTransaction { apply(this, page) } }
            prefs.putString(SINCE, lastSeq(page, since).toString())
            if (!page.flag("has_more")) return
        }
    }

    /** Страница выдачи — в базу одной транзакцией вызывающего. */
    private suspend fun apply(connection: PooledConnection, page: JsonObject) {
        val changes = page["changes"] as? JsonArray ?: throw serverError()
        val columns = mutableMapOf<String, Set<String>>()
        val me = device
        for (item in changes) {
            val change = item as? JsonObject ?: continue
            val entity = change.string("entity") ?: continue
            val id = change.string("id") ?: continue
            if (entity !in SyncRules.ENTITIES) continue
            val updatedAt = (change["updated_at"] as? JsonPrimitive)?.longOrNull ?: continue
            val stamp = SyncStamp(updatedAt, change.string("device_id").orEmpty())
            val pending = SyncOutbox.pending(connection, entity, id)
            // Своя запись стёрта совсем и удаление ещё не ушло — оно новее всего, что придёт.
            val local = SyncOutbox.updatedAt(connection, entity, id) ?: if (pending) Long.MAX_VALUE else null
            if (!SyncRules.applyIncoming(stamp, local, pending, me)) continue
            val data = change["data"]
            if (data.isEmptyRow()) {
                SyncOutbox.delete(connection, entity, id)
            } else {
                val known = columns.getOrPut(entity) { SyncOutbox.columns(connection, entity) }
                SyncOutbox.write(connection, entity, data as JsonObject, known)
            }
            SyncOutbox.forget(connection, entity, id)
        }
    }

    private fun lastSeq(page: JsonObject, since: Long) = (page["last_seq"] as? JsonPrimitive)?.longOrNull ?: since

    private fun JsonObject.flag(name: String) = (this[name] as? JsonPrimitive)?.booleanOrNull == true

    private fun serverError() = AccountException(AccountException.SERVER, AccountFormat.SERVER_ERROR)

    private fun setConnected(on: Boolean) {
        prefs.putBoolean(CONNECTED, on)
        _state.update { it.copy(connected = on) }
    }

    private fun done(): Boolean {
        val now = clock()
        prefs.putString(LAST, now.toString())
        _state.update { it.copy(lastSync = now, problem = null, failures = 0, progress = null, error = null, completed = it.completed + 1) }
        return true
    }

    private fun failed(e: AccountException): Boolean = fail(
        when (e.code) {
            SyncApi.UPDATE_REQUIRED -> SyncProblem.UPDATE_REQUIRED
            SyncApi.SERVER_OUTDATED -> SyncProblem.SERVER_OUTDATED
            SyncApi.FEATURE_CLOSED -> SyncProblem.CLOSED
            SyncApi.SUSPENDED -> SyncProblem.SUSPENDED
            AccountException.UNAUTHORIZED -> SyncProblem.SIGNED_OUT
            else -> SyncProblem.OFFLINE
        },
    )

    /** Сбой в телефоне: обмен стоит до следующего раза, текст — для тренера ([SyncState.error]). */
    private fun broken(e: Exception): Boolean {
        val text = (e::class.simpleName ?: "Exception") + (e.message?.let { ": $it" } ?: "")
        _state.update { it.copy(problem = SyncProblem.FAILED, error = text.take(ERROR_MAX), progress = null) }
        return false
    }

    private fun fail(problem: SyncProblem): Boolean {
        _state.update {
            it.copy(problem = problem, failures = if (problem in RETRIED) it.failures + 1 else it.failures, progress = null)
        }
        return false
    }

    companion object {
        /** Подключён ли сервер на этом телефоне. */
        const val CONNECTED = "sync.connected"

        /** Номер последнего полученного изменения (`seq`). */
        const val SINCE = "sync.since"

        /** Момент последнего полного обмена. */
        const val LAST = "sync.last"

        /** Почта аккаунта, для которого подключён сервер. */
        const val OWNER = "sync.owner"

        /** «1» — при последнем «Выйти» все правки ушли на сервер (шаг 5.1а). */
        const val SENT = "sync.sent"

        /** Id этого устройства для сервера. */
        const val DEVICE = "sync.device"

        /** Длина текста ошибки, не больше. */
        private const val ERROR_MAX = 600

        /** Ответы сервера, после которых запись уходит из очереди. */
        private val DONE_STATUSES = setOf("accepted", "stale", "club_only")

        /** Повторяются с нарастающей паузой: связь, сбой или обновление сервера. */
        val RETRIED = setOf(SyncProblem.OFFLINE, SyncProblem.SERVER_OUTDATED)

        /** Сервер отказал: при подключении — не подключаться. */
        private val REFUSED = setOf(SyncProblem.CLOSED, SyncProblem.SUSPENDED, SyncProblem.SIGNED_OUT)
    }
}

/** Получать изменения с самого начала: после восстановления из копии, при подключении и отключении сервера. */
internal fun DevicePrefs.pullFromStart() = putString(SyncRepository.SINCE, "0")
