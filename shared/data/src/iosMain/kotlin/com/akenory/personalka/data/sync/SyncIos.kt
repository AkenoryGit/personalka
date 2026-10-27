package com.akenory.personalka.data.sync

import com.akenory.personalka.data.account.AccountApi
import com.akenory.personalka.data.account.KeychainTokens
import com.akenory.personalka.data.account.serverClient
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.prefs.DevicePrefs
import kotlin.time.Clock

/** Синхронизация на iPhone: тот же адрес сервера и токен в Связке ключей, что у входа (`iosAccount`). */
fun iosSync(
    database: PersonalkaDatabase,
    prefs: DevicePrefs,
    owner: () -> String?,
    serverUrl: suspend () -> String?,
): SyncRepository {
    val api = SyncApi(serverClient()) { serverUrl()?.takeIf { it.isNotBlank() } ?: AccountApi.DEFAULT_URL }
    return SyncRepository(database, api, KeychainTokens(), prefs, owner) { Clock.System.now().toEpochMilliseconds() }
}
