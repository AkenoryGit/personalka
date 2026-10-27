package com.akenory.personalka.data.account

import com.akenory.personalka.data.prefs.DevicePrefs
import com.akenory.personalka.domain.account.AccountRules
import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.account.FeatureGrant
import com.akenory.personalka.domain.format.AccountFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

/**
 * Где лежит токен сессии (ТЗ 7.3): на Android — зашифрованным ключом из Android Keystore, на iOS — в Keychain.
 * Не в базе и не в копии: копия, отданная другому, не впускает его в аккаунт.
 */
interface TokenStore {
    fun read(): String?

    /** `null` — стереть. */
    fun write(token: String?)
}

/** Токен в памяти — для тестов. */
class MemoryTokens(private var token: String? = null) : TokenStore {
    override fun read(): String? = token

    override fun write(token: String?) {
        this.token = token
    }
}

/** Устройство в списке «Устройства»: [current] — это самое. */
data class DeviceSession(val id: Long, val name: String, val lastUsed: Instant, val current: Boolean)

/**
 * Аккаунт, в который вошёл этот телефон (ответ `GET /me`): возможности — сроки с учётом зависимостей, открыто ли
 * сегодня — [AccountRules.open]. Список устройств пустой, пока сервер не ответил после входа.
 */
data class Account(
    val email: String,
    val name: String,
    val admin: Boolean,
    val features: List<FeatureGrant>,
    val sessions: List<DeviceSession>,
    /** Администратор приостановил доступ (1.5): синхронизация и кабинеты стоят, данные в телефоне целы. */
    val suspended: Boolean = false,
)

/** Ответ на действие экрана входа. */
sealed interface AccountResult {
    /** Вошли или сделано. */
    data object Done : AccountResult

    /** Код ушёл письмом на [email] и действует [minutes] минут — показать поле для него. */
    data class CodeSent(val email: String, val minutes: Int) : AccountResult

    /** Не вышло: [message] — для показа как есть; [code] — код ошибки сервера (7.1) или свой. */
    data class Failed(val code: String, val message: String) : AccountResult
}

/**
 * Аккаунт на этом телефоне (шаг 2.8а, ТЗ 7.3, 1.5): регистрация с кодом из письма, вход, сброс пароля кодом,
 * устройства и выход. Токен — в [tokens], профиль для показа без связи — в [prefs] (в нём нет секретов).
 * Форма проверяется теми же [AccountRules], что на сервере, — до запроса.
 *
 * @param coachId id тренера в телефоне: регистрация отдаёт его серверу, синхронизация (2.9–2.10) найдёт данные по нему.
 * @param device имя телефона для списка устройств: «Samsung SM-F976B».
 */
class AccountRepository(
    private val api: AccountApi,
    private val tokens: TokenStore,
    private val prefs: DevicePrefs,
    private val device: String,
    private val coachId: suspend () -> String?,
) {
    private val _account = MutableStateFlow(if (tokens.read() != null) cached() else null)

    /** Вошедший аккаунт или `null`. */
    val account: StateFlow<Account?> = _account.asStateFlow()

    suspend fun register(name: String, email: String, password: String): AccountResult {
        val cleanName = AccountRules.name(name) ?: return failed(AccountFormat.NAME_EMPTY)
        val cleanEmail = AccountRules.email(email) ?: return failed(AccountFormat.EMAIL_WRONG)
        AccountRules.passwordProblem(password)?.let { return failed(it) }
        return answer {
            codeSent(api.register(cleanEmail, password, cleanName, coachId()))
        }
    }

    /** Код из письма после регистрации или входа с неподтверждённой почтой: пароль — тот же, что вводили. */
    suspend fun confirm(email: String, password: String, code: String): AccountResult {
        val cleanEmail = AccountRules.email(email) ?: return failed(AccountFormat.EMAIL_WRONG)
        return answer { signedIn(api.confirm(cleanEmail, password, code.trim(), device)) }
    }

    /** Вход. Почта не подтверждена — сервер выслал код, ответ [AccountResult.CodeSent]. */
    suspend fun login(email: String, password: String): AccountResult {
        val cleanEmail = AccountRules.email(email) ?: return failed(AccountFormat.EMAIL_WRONG)
        if (password.isEmpty()) return failed(AccountFormat.PASSWORD_EMPTY)
        val result = answer { signedIn(api.login(cleanEmail, password, device)) }
        return if (result is AccountResult.Failed && result.code == AccountException.NOT_CONFIRMED) {
            AccountResult.CodeSent(cleanEmail, CODE_MINUTES)
        } else {
            result
        }
    }

    suspend fun requestReset(email: String): AccountResult {
        val cleanEmail = AccountRules.email(email) ?: return failed(AccountFormat.EMAIL_WRONG)
        return answer { codeSent(api.requestReset(cleanEmail)) }
    }

    /** Новый пароль по коду: остальные устройства выходят, этот входит. */
    suspend fun reset(email: String, code: String, password: String): AccountResult {
        val cleanEmail = AccountRules.email(email) ?: return failed(AccountFormat.EMAIL_WRONG)
        AccountRules.passwordProblem(password)?.let { return failed(it) }
        return answer { signedIn(api.reset(cleanEmail, code.trim(), password, device)) }
    }

    /** Свежий профиль, возможности и устройства. Вход устарел — телефон выходит из аккаунта. */
    suspend fun refresh(): AccountResult {
        val token = tokens.read() ?: return failed(AccountFormat.EXPIRED, AccountException.UNAUTHORIZED)
        return answer { save(api.me(token)) }
    }

    /** «Выйти на этом устройстве» у строки устройства; своя строка — обычный выход. */
    suspend fun closeSession(session: DeviceSession): AccountResult {
        if (session.current) return logout()
        val token = tokens.read() ?: return failed(AccountFormat.EXPIRED, AccountException.UNAUTHORIZED)
        return answer {
            api.closeSession(token, session.id)
            _account.value = _account.value?.let { a -> a.copy(sessions = a.sessions.filter { it.id != session.id }) }
            runCatching { save(api.me(token)) }
        }
    }

    /**
     * «Выйти»: телефон забывает токен сразу, даже без связи; сервер закрывает сессию, если ответит. Данные
     * в телефоне не трогаются.
     */
    suspend fun logout(): AccountResult {
        val token = tokens.read()
        signOut()
        if (token != null) {
            try {
                api.logout(token)
            } catch (e: AccountException) {
                // Без связи сессия на сервере доживёт до срока или её закроют с другого устройства.
            }
        }
        return AccountResult.Done
    }

    private fun failed(message: String, code: String = "form") = AccountResult.Failed(code, message)

    private suspend fun answer(block: suspend () -> Any?): AccountResult = try {
        when (val r = block()) {
            is AccountResult -> r
            else -> AccountResult.Done
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AccountException) {
        when (e.code) {
            AccountException.UNAUTHORIZED -> {
                if (tokens.read() != null) signOut()
                AccountResult.Failed(e.code, e.message)
            }
            else -> AccountResult.Failed(e.code, e.message)
        }
    }

    private fun codeSent(json: JsonObject): AccountResult.CodeSent = AccountResult.CodeSent(
        json.string("email").orEmpty(),
        (json["minutes"] as? JsonPrimitive)?.longOrNull?.toInt() ?: CODE_MINUTES,
    )

    /** Ответ входа: токен — в хранилище, тренер — в профиль; устройства — следом, если сервер ответит. */
    private suspend fun signedIn(json: JsonObject) {
        val token = json.string("token") ?: throw AccountException(AccountException.SERVER, AccountFormat.SERVER_ERROR)
        val coach = json["coach"] as? JsonObject ?: throw AccountException(AccountException.SERVER, AccountFormat.SERVER_ERROR)
        tokens.write(token)
        save(coach)
        try {
            save(api.me(token))
        } catch (e: AccountException) {
            // Вход уже есть; устройства подтянутся при следующем открытии экрана.
        }
    }

    private fun save(me: JsonObject) {
        val account = parse(me) ?: throw AccountException(AccountException.SERVER, AccountFormat.SERVER_ERROR)
        prefs.putString(PROFILE, me.toString())
        _account.value = account
    }

    private fun signOut() {
        tokens.write(null)
        prefs.putString(PROFILE, "")
        _account.value = null
    }

    private fun cached(): Account? = prefs.string(PROFILE)?.takeIf { it.isNotEmpty() }?.let { text ->
        runCatching { parse(Json.parseToJsonElement(text) as JsonObject) }.getOrNull()
    }

    companion object {
        /** Профиль аккаунта в настройках телефона: почта, имя, возможности, устройства. */
        const val PROFILE = "account.profile"

        /** Сколько живёт код, если сервер не сказал (7.3). */
        const val CODE_MINUTES = 15

        /** Тренер из ответа `/me` или входа; без почты — не ответ сервера. */
        internal fun parse(json: JsonObject): Account? {
            val email = json.string("email") ?: return null
            val features = (json["features"] as? JsonArray).orEmpty().mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val feature = Feature.of(obj.string("code").orEmpty()) ?: return@mapNotNull null
                val until = obj.string("until")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                FeatureGrant(feature, until)
            }
            val sessions = (json["sessions"] as? JsonArray).orEmpty().mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val id = (obj["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                val used = obj.string("last_used_at")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@mapNotNull null
                DeviceSession(id, obj.string("device_name").orEmpty(), used, (obj["current"] as? JsonPrimitive)?.booleanOrNull == true)
            }
            return Account(
                email = email,
                name = json.string("display_name").orEmpty(),
                admin = json.string("role") == "ADMIN",
                features = features,
                sessions = sessions,
                suspended = json.string("status") == "SUSPENDED",
            )
        }
    }
}
