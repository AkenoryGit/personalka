package com.akenory.personalka.domain.report1c

/**
 * Распаковка сжатия Deflate (RFC 1950, 1951) на чистом Kotlin — ею сжаты страницы и шрифты PDF из 1С (`FlateDecode`).
 * Своя, а не `java.util.zip`: `commonMain` соберётся и для iPhone (шаг 5.1).
 */
internal object Inflate {
    class Broken(message: String) : Exception(message)

    /** Данные с заголовком zlib (как в PDF) или без него. */
    fun inflate(data: ByteArray): ByteArray {
        val zlib = data.size >= 2 && (data[0].toInt() and 0x0f) == 8 &&
            ((data[0].toInt() and 0xff) * 256 + (data[1].toInt() and 0xff)) % 31 == 0
        return Reader(data, if (zlib) 2 else 0).run()
    }

    private val LENGTH_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LENGTH_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DIST_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
    private val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
    private val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    /** Код Хаффмана по длинам: [counts] — сколько кодов каждой длины, [symbols] — символы по порядку кодов. */
    private class Huffman(lengths: IntArray) {
        val counts = IntArray(16)
        val symbols: IntArray

        init {
            for (l in lengths) counts[l]++
            counts[0] = 0
            val offsets = IntArray(16)
            for (i in 1 until 16) offsets[i] = offsets[i - 1] + counts[i - 1]
            symbols = IntArray(lengths.size)
            for (s in lengths.indices) if (lengths[s] != 0) symbols[offsets[lengths[s]]++] = s
        }
    }

    private val FIXED_LIT = Huffman(IntArray(288) { if (it < 144) 8 else if (it < 256) 9 else if (it < 280) 7 else 8 })
    private val FIXED_DIST = Huffman(IntArray(30) { 5 })

    private class Reader(val data: ByteArray, var pos: Int) {
        var bitBuf = 0
        var bitCount = 0
        var out = ByteArray(maxOf(64, data.size * 4))
        var size = 0

        fun bits(n: Int): Int {
            while (bitCount < n) {
                if (pos >= data.size) throw Broken("сжатые данные оборваны")
                bitBuf = bitBuf or ((data[pos++].toInt() and 0xff) shl bitCount)
                bitCount += 8
            }
            val v = bitBuf and ((1 shl n) - 1)
            bitBuf = bitBuf ushr n
            bitCount -= n
            return v
        }

        fun decode(h: Huffman): Int {
            var code = 0
            var first = 0
            var index = 0
            for (len in 1 until 16) {
                code = code or bits(1)
                val count = h.counts[len]
                if (code - count < first) return h.symbols[index + (code - first)]
                index += count
                first = (first + count) shl 1
                code = code shl 1
            }
            throw Broken("неверный код Хаффмана")
        }

        fun put(b: Int) {
            if (size == out.size) out = out.copyOf(out.size * 2)
            out[size++] = b.toByte()
        }

        fun run(): ByteArray {
            do {
                val last = bits(1) == 1
                when (bits(2)) {
                    0 -> stored()
                    1 -> block(FIXED_LIT, FIXED_DIST)
                    2 -> dynamic()
                    else -> throw Broken("неверный тип блока")
                }
            } while (!last)
            return out.copyOf(size)
        }

        fun stored() {
            bitBuf = 0
            bitCount = 0
            if (pos + 4 > data.size) throw Broken("сжатые данные оборваны")
            val len = (data[pos].toInt() and 0xff) or ((data[pos + 1].toInt() and 0xff) shl 8)
            pos += 4
            if (pos + len > data.size) throw Broken("сжатые данные оборваны")
            repeat(len) { put(data[pos++].toInt()) }
        }

        fun dynamic() {
            val nLit = bits(5) + 257
            val nDist = bits(5) + 1
            val nCode = bits(4) + 4
            val codeLengths = IntArray(19)
            for (i in 0 until nCode) codeLengths[CODE_LENGTH_ORDER[i]] = bits(3)
            val codeHuffman = Huffman(codeLengths)
            val lengths = IntArray(nLit + nDist)
            var i = 0
            while (i < lengths.size) {
                val sym = decode(codeHuffman)
                when {
                    sym < 16 -> lengths[i++] = sym
                    else -> {
                        val (value, times) = when (sym) {
                            16 -> {
                                if (i == 0) throw Broken("повтор без предыдущей длины")
                                lengths[i - 1] to 3 + bits(2)
                            }
                            17 -> 0 to 3 + bits(3)
                            else -> 0 to 11 + bits(7)
                        }
                        if (i + times > lengths.size) throw Broken("лишние длины кодов")
                        repeat(times) { lengths[i++] = value }
                    }
                }
            }
            block(Huffman(lengths.copyOfRange(0, nLit)), Huffman(lengths.copyOfRange(nLit, lengths.size)))
        }

        fun block(lit: Huffman, dist: Huffman) {
            while (true) {
                val sym = decode(lit)
                when {
                    sym < 256 -> put(sym)
                    sym == 256 -> return
                    else -> {
                        val li = sym - 257
                        if (li >= 29) throw Broken("неверная длина")
                        val len = LENGTH_BASE[li] + bits(LENGTH_EXTRA[li])
                        val di = decode(dist)
                        if (di >= 30) throw Broken("неверное расстояние")
                        val d = DIST_BASE[di] + bits(DIST_EXTRA[di])
                        if (d > size) throw Broken("расстояние за началом данных")
                        repeat(len) { put(out[size - d].toInt()) }
                    }
                }
            }
        }
    }
}
