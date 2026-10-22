package com.akenory.personalka.server

import com.akenory.personalka.domain.ClubTime

/** Каркас сервера. Ktor, PostgreSQL и выкладка на VPS — шаг 2.7. */
fun main() {
    println("Сервер «Персоналки»: каркас. Пояс клуба по умолчанию — ${ClubTime.DEFAULT_ZONE_ID}.")
}
