package com.akenory.personalka.server

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Argon2id (ТЗ 7.3): настоящие параметры сервера, соль у каждого пароля своя. */
class PasswordsTest {
    private val passwords = Passwords()

    @Test
    fun hashVerifiesOnlyTheSamePassword() {
        val stored = passwords.hash("плавание-2026")
        assertTrue(stored.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"))
        assertTrue(passwords.verify("плавание-2026", stored))
        assertFalse(passwords.verify("плавание-2027", stored))
        assertNotEquals(stored, passwords.hash("плавание-2026"))
    }

    @Test
    fun knownVectorFromReferenceImplementation() {
        // argon2 "password" -id -t 2 -m 16 -p 1 с солью "somesalt" (эталонная программа argon2, PHC).
        val stored = "\$argon2id\$v=19\$m=65536,t=2,p=1\$c29tZXNhbHQ\$CTFhFdXPJO1aFaMaO6Mm5c8y7cJHAph8ArZWb2GRPPc"
        assertTrue(passwords.verify("password", stored))
    }

    @Test
    fun brokenStringIsNotAMatch() {
        assertFalse(passwords.verify("x", "не хэш"))
        assertFalse(passwords.verify("x", "\$argon2i\$v=19\$m=1024,t=1,p=1\$c29tZXNhbHQ\$AAAA"))
    }
}
