package com.akenory.personalka.data.backup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Шифр, хеш и zip копий одинаковы на всех платформах (шаг 5.1): на JVM и Android — Java, на iPhone — CommonCrypto,
 * свой GCM и zip. Копия, сделанная на одном телефоне, открывается на другом. Векторы — NIST (GCM, тесты 13–15).
 */
class BackupPlatformTest {
    @Test
    fun gcmEmptyMessage() {
        val sealed = BackupPlatform.seal(ByteArray(32), ByteArray(12), ByteArray(0))
        assertEquals("530f8afbc74536b9a963b4f1c4cb738b", sealed.hex())
    }

    @Test
    fun gcmOneBlock() {
        val sealed = BackupPlatform.seal(ByteArray(32), ByteArray(12), ByteArray(16))
        assertEquals("cea7403d4d606b6e074ec5d3baf39d18" + "d0d1c8a799996bf0265b98b5d48ab919", sealed.hex())
    }

    @Test
    fun gcmNistCase15() {
        val key = "feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308".bytes()
        val nonce = "cafebabefacedbaddecaf888".bytes()
        val plain = ("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
            "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255").bytes()
        val sealed = BackupPlatform.seal(key, nonce, plain)
        assertEquals(
            "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
                "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662898015ad" +
                "b094dac5d93471bdec1a502270e3cc6c",
            sealed.hex(),
        )
        assertContentEquals(plain, BackupPlatform.open(key, nonce, sealed))
    }

    @Test
    fun gcmWrongKeyOrDamage() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { 7 }
        val plain = "Копия за октябрь, 37 байт и ещё немного".encodeToByteArray()
        val sealed = BackupPlatform.seal(key, nonce, plain)
        assertContentEquals(plain, BackupPlatform.open(key, nonce, sealed))
        assertNull(BackupPlatform.open(ByteArray(32), nonce, sealed))
        sealed[3] = (sealed[3].toInt() xor 1).toByte()
        assertNull(BackupPlatform.open(key, nonce, sealed))
    }

    @Test
    fun sha256() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", BackupPlatform.sha256(ByteArray(0)).hex())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", BackupPlatform.sha256("abc".encodeToByteArray()).hex())
    }

    @Test
    fun zipRoundTrip() {
        val files = mapOf(
            "meta.json" to "{\"версия\":1}".encodeToByteArray(),
            "data.bin" to ByteArray(5000) { (it % 7).toByte() },
            "empty" to ByteArray(0),
        )
        val back = BackupPlatform.unzip(BackupPlatform.zip(files))!!
        assertEquals(files.keys, back.keys)
        files.forEach { (name, bytes) -> assertContentEquals(bytes, back[name], name) }
    }

    @Test
    fun zipFromOtherTool() {
        // Архив Python zipfile (deflate): чужой zip читается так же, как свой.
        val zip = ("504b03041400000008008cb55b5dabe38a642300000057000000090000006d6574612e6a736f6eab562a53b232d451ca4bcc4d55b252" +
            "ba30ff62c3851d17f65cd87ab141a9b69a6c4900504b03041400000008008cb55b5d00000000020000000000000009000000656d7074792e62" +
            "696e0300504b010214031400000008008cb55b5dabe38a6423000000570000000900000000000000000000008001000000006d6574612e6a73" +
            "6f6e504b010214031400000008008cb55b5d00000000020000000000000009000000000000000000000080014a000000656d7074792e62696e" +
            "504b050600000000020002006e000000730000000000").bytes()
        val files = BackupPlatform.unzip(zip)!!
        assertEquals("{\"v\":1,\"name\":\"Пример\"}".repeat(3), files["meta.json"]!!.decodeToString())
        assertEquals(0, files["empty.bin"]!!.size)
    }

    @Test
    fun notZip() {
        assertNull(BackupPlatform.unzip("не архив".encodeToByteArray()))
    }

    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun String.bytes() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
