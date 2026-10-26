package com.akenory.personalka.data.sync

import android.content.Context
import com.akenory.personalka.data.account.AccountApi
import com.akenory.personalka.data.account.KeystoreTokens
import com.akenory.personalka.data.account.serverClient
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.prefs.DevicePrefs

/**
 * Синхронизация на Android (шаг 2.10а): тот же адрес сервера и тот же токен в Android Keystore, что у входа
 * (`androidAccount`), — вышел из аккаунта, и обмен останавливается сам ([com.akenory.personalka.domain.sync.SyncProblem.SIGNED_OUT]).
 * [owner] — почта вошедшего аккаунта: вошли в другой — обмен стоит.
 */
fun androidSync(
    context: Context,
    database: PersonalkaDatabase,
    prefs: DevicePrefs,
    owner: () -> String?,
    serverUrl: suspend () -> String?,
): SyncRepository {
    val api = SyncApi(serverClient()) { serverUrl()?.takeIf { it.isNotBlank() } ?: AccountApi.DEFAULT_URL }
    return SyncRepository(database, api, KeystoreTokens(context), prefs, owner, System::currentTimeMillis)
}
