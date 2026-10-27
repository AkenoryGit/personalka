package com.akenory.personalka.data.backup

/**
 * AES-GCM (NIST SP 800-38D) поверх блока AES из CommonCrypto: метка 16 байт в конце шифра — как у Java
 * `AES/GCM/NoPadding`, без дополнительных данных (AAD). Счётчики шифруются одним вызовом, GHASH — здесь.
 */
internal class Gcm(private val key: ByteArray) {
    private val h: Block = Block.of(BackupPlatform.aesBlocks(key, ByteArray(16)), 0)

    fun seal(nonce: ByteArray, plain: ByteArray): ByteArray {
        val j0 = j0(nonce)
        val cipher = ctr(j0, plain)
        return cipher + tag(j0, cipher)
    }

    fun open(nonce: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < TAG) return null
        val cipher = sealed.copyOfRange(0, sealed.size - TAG)
        val j0 = j0(nonce)
        val expected = tag(j0, cipher)
        var diff = 0
        for (i in 0 until TAG) diff = diff or (expected[i].toInt() xor sealed[cipher.size + i].toInt())
        return if (diff == 0) ctr(j0, cipher) else null
    }

    /** Начальный счётчик: для nonce 12 байт — он и 00 00 00 01, иначе GHASH от nonce. */
    private fun j0(nonce: ByteArray): ByteArray {
        if (nonce.size == 12) return nonce + byteArrayOf(0, 0, 0, 1)
        var y = ghash(Block(0, 0), nonce)
        y = y.xor(Block(0, nonce.size.toLong() * 8)).times(h)
        return y.bytes()
    }

    /** Шифр счётчиком: блоки inc32(J0), inc32²(J0), … */
    private fun ctr(j0: ByteArray, data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val blocks = (data.size + 15) / 16
        val counters = ByteArray(blocks * 16)
        val start = counterOf(j0)
        for (b in 0 until blocks) {
            j0.copyInto(counters, b * 16, 0, 12)
            val c = start + 1 + b
            counters[b * 16 + 12] = (c ushr 24).toByte()
            counters[b * 16 + 13] = (c ushr 16).toByte()
            counters[b * 16 + 14] = (c ushr 8).toByte()
            counters[b * 16 + 15] = c.toByte()
        }
        val stream = BackupPlatform.aesBlocks(key, counters)
        return ByteArray(data.size) { (data[it].toInt() xor stream[it].toInt()).toByte() }
    }

    private fun tag(j0: ByteArray, cipher: ByteArray): ByteArray {
        var s = ghash(Block(0, 0), cipher)
        s = s.xor(Block(0, cipher.size.toLong() * 8)).times(h)
        val mask = BackupPlatform.aesBlocks(key, j0)
        val t = s.bytes()
        return ByteArray(TAG) { (t[it].toInt() xor mask[it].toInt()).toByte() }
    }

    /** GHASH данных, дополненных нулями до целого блока. */
    private fun ghash(start: Block, data: ByteArray): Block {
        var y = start
        var i = 0
        while (i < data.size) {
            val block = if (i + 16 <= data.size) Block.of(data, i) else Block.of(data.copyOfRange(i, data.size).copyOf(16), 0)
            y = y.xor(block).times(h)
            i += 16
        }
        return y
    }

    private fun counterOf(j0: ByteArray): Int =
        ((j0[12].toInt() and 0xFF) shl 24) or ((j0[13].toInt() and 0xFF) shl 16) or ((j0[14].toInt() and 0xFF) shl 8) or (j0[15].toInt() and 0xFF)

    /** 128 бит: [hi] — первые 8 байт (старшие биты — первые по порядку GCM). */
    private class Block(val hi: Long, val lo: Long) {
        fun xor(o: Block) = Block(hi xor o.hi, lo xor o.lo)

        /** Умножение в GF(2¹²⁸) по алгоритму 1 SP 800-38D. */
        fun times(y: Block): Block {
            var zHi = 0L
            var zLo = 0L
            var vHi = y.hi
            var vLo = y.lo
            for (i in 0 until 128) {
                val bit = if (i < 64) (hi ushr (63 - i)) and 1L else (lo ushr (127 - i)) and 1L
                if (bit != 0L) {
                    zHi = zHi xor vHi
                    zLo = zLo xor vLo
                }
                val carry = vLo and 1L
                vLo = (vLo ushr 1) or (vHi shl 63)
                vHi = vHi ushr 1
                if (carry != 0L) vHi = vHi xor R
            }
            return Block(zHi, zLo)
        }

        fun bytes(): ByteArray = ByteArray(16) { i ->
            if (i < 8) (hi ushr (56 - 8 * i)).toByte() else (lo ushr (56 - 8 * (i - 8))).toByte()
        }

        companion object {
            const val R = -0x1f00000000000000L // 0xE1 << 56

            fun of(b: ByteArray, at: Int): Block {
                var hi = 0L
                var lo = 0L
                for (i in 0 until 8) hi = (hi shl 8) or (b[at + i].toLong() and 0xFF)
                for (i in 8 until 16) lo = (lo shl 8) or (b[at + i].toLong() and 0xFF)
                return Block(hi, lo)
            }
        }
    }

    private companion object {
        const val TAG = 16
    }
}
