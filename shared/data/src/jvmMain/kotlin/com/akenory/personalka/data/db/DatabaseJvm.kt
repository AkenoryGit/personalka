package com.akenory.personalka.data.db

import androidx.room3.Room
import kotlinx.coroutines.Dispatchers

/** База в файле [path] на компьютере — для утилиты импорта, которая собирает файл копии (1.18), и для тестов. */
fun personalkaDatabase(path: String): PersonalkaDatabase =
    Room.databaseBuilder<PersonalkaDatabase>(name = path)
        .buildPersonalka(Dispatchers.IO)

/** База в памяти — для тестов. */
fun inMemoryPersonalkaDatabase(): PersonalkaDatabase =
    Room.inMemoryDatabaseBuilder<PersonalkaDatabase>()
        .buildPersonalka(Dispatchers.IO)
