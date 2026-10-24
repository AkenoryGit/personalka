package com.akenory.personalka.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

// Запросы — обычный JDBC (как в Planovo): их немного, и так видно, что именно уходит в базу.

/** Транзакция: либо целиком, либо ничего. JDBC блокирует поток — поэтому вне потоков сервера. */
suspend fun <T> Database.tx(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
    dataSource.connection.use { c ->
        c.autoCommit = false
        try {
            val result = block(c)
            c.commit()
            result
        } catch (e: Throwable) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }
}

/** Запрос с параметрами по порядку. */
fun Connection.prepare(sql: String, vararg params: Any?): PreparedStatement {
    val ps = prepareStatement(sql)
    params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
    return ps
}

/** Все строки ответа. */
fun <T> Connection.query(sql: String, vararg params: Any?, row: (ResultSet) -> T): List<T> =
    prepare(sql, *params).use { ps ->
        ps.executeQuery().use { rs ->
            val out = ArrayList<T>()
            while (rs.next()) out += row(rs)
            out
        }
    }

/** Первая строка ответа или null. */
fun <T> Connection.queryOne(sql: String, vararg params: Any?, row: (ResultSet) -> T): T? =
    query(sql, *params, row = row).firstOrNull()

/** Изменить строки; ответ — сколько изменено. */
fun Connection.update(sql: String, vararg params: Any?): Int = prepare(sql, *params).use { it.executeUpdate() }
