package com.akenory.personalka.data.db

import androidx.room3.Room
import kotlinx.coroutines.Dispatchers

/**
 * База в файле [path] на компьютере — для утилиты импорта, которая собирает файл копии (1.18), и для тестов;
 * [beforeMigration] — копия перед переходом с прежней версии.
 */
fun personalkaDatabase(path: String, beforeMigration: BeforeMigration? = null): PersonalkaDatabase =
    Room.databaseBuilder<PersonalkaDatabase>(name = path)
        .buildPersonalka(Dispatchers.IO, beforeMigration)

/** База в памяти — для тестов. */
fun inMemoryPersonalkaDatabase(): PersonalkaDatabase =
    Room.inMemoryDatabaseBuilder<PersonalkaDatabase>()
        .buildPersonalka(Dispatchers.IO)
