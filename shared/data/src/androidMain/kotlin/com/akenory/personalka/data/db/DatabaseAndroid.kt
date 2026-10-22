package com.akenory.personalka.data.db

import android.content.Context
import androidx.room3.Room
import kotlinx.coroutines.Dispatchers

/** База приложения на телефоне: файл `personalka.db` в личной папке приложения. */
fun personalkaDatabase(context: Context): PersonalkaDatabase {
    val app = context.applicationContext
    val file = app.getDatabasePath(PersonalkaDatabase.FILE_NAME)
    return Room.databaseBuilder<PersonalkaDatabase>(context = app, name = file.absolutePath)
        .buildPersonalka(Dispatchers.IO)
}
