package com.akenory.personalka.data.cabinet

import com.akenory.personalka.data.account.AccountApi
import com.akenory.personalka.data.account.KeychainBox
import com.akenory.personalka.data.account.KeychainTokens
import com.akenory.personalka.data.account.serverClient
import com.akenory.personalka.data.db.PersonalkaDatabase
import kotlin.time.Clock

/** Токены и коды ссылок кабинетов в Связке ключей (3.7), своей службой. */
class KeychainSecrets : SecretStore {
    private val box = KeychainBox("personalka-cabinets")

    override fun read(name: String): String? = box.read(name)

    override fun write(name: String, value: String?) = box.write(name, value)
}

/** Ссылки кабинетов на iPhone: тот же адрес сервера и токен сессии, что у входа. */
fun iosCabinets(database: PersonalkaDatabase, serverUrl: suspend () -> String?): CabinetRepository {
    val api = CabinetApi(serverClient()) { serverUrl()?.takeIf { it.isNotBlank() } ?: AccountApi.DEFAULT_URL }
    return CabinetRepository(database, api, KeychainTokens(), KeychainSecrets(), clock = { Clock.System.now().toEpochMilliseconds() })
}
