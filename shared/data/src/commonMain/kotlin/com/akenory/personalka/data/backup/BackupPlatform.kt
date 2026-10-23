package com.akenory.personalka.data.backup

/**
 * Что копии берёт у платформы: случайные байты, AES-GCM, SHA-256 и zip. На телефоне и компьютере —
 * стандартная библиотека Java (`src/javaShared`), iPhone получит своё на шаге 5.1.
 */
internal expect object BackupPlatform {
    /** Криптографически случайные байты: соль пароля и nonce шифра. */
    fun randomBytes(size: Int): ByteArray

    fun sha256(data: ByteArray): ByteArray

    /** AES-256-GCM: шифр с меткой подлинности 16 байт в конце. */
    fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray

    /** Расшифровать [sealed]; `null` — неверный ключ (пароль) или файл повреждён. */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray): ByteArray?

    /** Архив zip из файлов «имя → содержимое», со сжатием. */
    fun zip(files: Map<String, ByteArray>): ByteArray

    /** Файлы архива; `null` — это не zip. */
    fun unzip(bytes: ByteArray): Map<String, ByteArray>?
}
