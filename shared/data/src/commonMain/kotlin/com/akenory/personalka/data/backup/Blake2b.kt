package com.akenory.personalka.data.backup

/**
 * BLAKE2b (RFC 7693) — хеш, на котором стоит Argon2 ([Argon2id]). Без ключа, длина ответа 1–64 байта.
 * На чистом Kotlin: один и тот же код на телефоне, на компьютере и потом на iPhone.
 */
internal class Blake2b(private val outLength: Int) {
    private val h = IV.copyOf()
    private val block = ByteArray(BLOCK)
    private var filled = 0
    private var counterLow = 0L
    private var counterHigh = 0L

    init {
        require(outLength in 1..64) { "Длина BLAKE2b — от 1 до 64 байт" }
        h[0] = h[0] xor (0x01010000L or outLength.toLong())
    }

    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Blake2b {
        var from = offset
        var left = length
        while (left > 0) {
            // Последний блок сжимается в digest() с отметкой конца, поэтому полный блок ждёт следующих байт.
            if (filled == BLOCK) {
                count(BLOCK)
                compress(last = false)
                filled = 0
            }
            val take = minOf(left, BLOCK - filled)
            data.copyInto(block, filled, from, from + take)
            filled += take
            from += take
            left -= take
        }
        return this
    }

    fun digest(): ByteArray {
        count(filled)
        block.fill(0, filled, BLOCK)
        compress(last = true)
        return ByteArray(outLength) { i -> (h[i / 8] ushr (8 * (i % 8))).toByte() }
    }

    private fun count(bytes: Int) {
        counterLow += bytes
        if (counterLow.toULong() < bytes.toULong()) counterHigh++
    }

    private fun compress(last: Boolean) {
        val m = LongArray(16) { readLong(block, it * 8) }
        val v = LongArray(16)
        h.copyInto(v, 0)
        IV.copyInto(v, 8)
        v[12] = v[12] xor counterLow
        v[13] = v[13] xor counterHigh
        if (last) v[14] = v[14].inv()
        for (round in 0 until 12) {
            val s = SIGMA[round % 10]
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
            g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
            g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
            g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
            g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    private fun g(v: LongArray, a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
        v[a] = v[a] + v[b] + x
        v[d] = (v[d] xor v[a]).rotateRight(32)
        v[c] = v[c] + v[d]
        v[b] = (v[b] xor v[c]).rotateRight(24)
        v[a] = v[a] + v[b] + y
        v[d] = (v[d] xor v[a]).rotateRight(16)
        v[c] = v[c] + v[d]
        v[b] = (v[b] xor v[c]).rotateRight(63)
    }

    companion object {
        private const val BLOCK = 128

        private val IV = longArrayOf(
            0x6a09e667f3bcc908uL.toLong(), 0xbb67ae8584caa73buL.toLong(), 0x3c6ef372fe94f82buL.toLong(), 0xa54ff53a5f1d36f1uL.toLong(),
            0x510e527fade682d1uL.toLong(), 0x9b05688c2b3e6c1fuL.toLong(), 0x1f83d9abfb41bd6buL.toLong(), 0x5be0cd19137e2179uL.toLong(),
        )

        private val SIGMA = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
            intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
            intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
            intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
            intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
            intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
            intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
            intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
            intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
        )

        /** Хеш [data] длиной [outLength] байт. */
        fun hash(outLength: Int, vararg data: ByteArray): ByteArray {
            val b = Blake2b(outLength)
            data.forEach { b.update(it) }
            return b.digest()
        }
    }
}

/** Число из 8 байт [bytes] с [offset], младший байт первым. */
internal fun readLong(bytes: ByteArray, offset: Int): Long {
    var r = 0L
    for (i in 7 downTo 0) r = (r shl 8) or (bytes[offset + i].toLong() and 0xFF)
    return r
}

/** Число в 8 байт [bytes] с [offset], младший байт первым. */
internal fun writeLong(bytes: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 8) bytes[offset + i] = (value ushr (8 * i)).toByte()
}

/** Число в 4 байта, младший первым. */
internal fun int32(value: Int): ByteArray = ByteArray(4) { i -> (value ushr (8 * i)).toByte() }
