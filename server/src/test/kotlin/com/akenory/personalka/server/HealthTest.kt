package com.akenory.personalka.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/** `/health` (ТЗ 7.4): по нему выкладка переключает версию или откатывает. */
class HealthTest {

    @Test
    fun okWithVersionAndSchema() = testApplication {
        val database = Database.connect(TestDb.fresh())
        application { trener(database, version = "0.1.77") }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok\nверсия 0.1.77\nсхема 7\n", response.bodyAsText())
    }

    @Test
    fun unavailableWhenDatabaseIsGone() = testApplication {
        val database = Database.connect(TestDb.fresh())
        application { trener(database, version = "0.1.77") }
        database.close()
        val response = client.get("/health")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("база не отвечает\nверсия 0.1.77\n", response.bodyAsText())
    }

    @Test
    fun unknownAddressIsNotFound() = testApplication {
        val database = Database.connect(TestDb.fresh())
        application { trener(database) }
        assertEquals(HttpStatusCode.NotFound, client.get("/").status)
    }
}
