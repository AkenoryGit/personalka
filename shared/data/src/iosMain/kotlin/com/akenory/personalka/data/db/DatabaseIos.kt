package com.akenory.personalka.data.db

import androidx.room3.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * База приложения на iPhone (шаг 5.1): файл `personalka.db` в папке Application Support приложения ([folder]);
 * перед переходом с прежней версии — копия ([beforeMigration], ТЗ 10.2).
 */
fun personalkaDatabase(folder: String, beforeMigration: BeforeMigration? = null): PersonalkaDatabase =
    Room.databaseBuilder<PersonalkaDatabase>(name = "$folder/${PersonalkaDatabase.FILE_NAME}")
        .buildPersonalka(Dispatchers.IO, beforeMigration)
