package com.akenory.personalka.data.cabinet

import android.content.Context
import com.akenory.personalka.data.account.AccountApi
import com.akenory.personalka.data.account.KeystoreBox
import com.akenory.personalka.data.account.KeystoreTokens
import com.akenory.personalka.data.account.serverClient
import com.akenory.personalka.data.db.PersonalkaDatabase

/** Токены и коды ссылок кабинетов под ключом Android Keystore (3.7), в своём файле настроек. */
class KeystoreSecrets(context: Context) : SecretStore {
    private val box = KeystoreBox(context, "personalka-cabinets")

    override fun read(name: String): String? = box.read(name)

    override fun write(name: String, value: String?) = box.write(name, value)
}

/** Ссылки кабинетов на Android (шаг 2.13): тот же адрес сервера и тот же токен сессии, что у входа. */
fun androidCabinets(context: Context, database: PersonalkaDatabase, serverUrl: suspend () -> String?): CabinetRepository {
    val api = CabinetApi(serverClient()) { serverUrl()?.takeIf { it.isNotBlank() } ?: AccountApi.DEFAULT_URL }
    return CabinetRepository(database, api, KeystoreTokens(context), KeystoreSecrets(context), System::currentTimeMillis)
}
