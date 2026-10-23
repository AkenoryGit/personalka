package com.akenory.personalka.data.backup

/**
 * Argon2id версии 1.3 (RFC 9106): ключ шифрования копии из пароля (ТЗ 7.6). Медленный и требует памяти
 * нарочно — перебирать пароли к украденной копии долго. Полосы считаются по очереди: на телефоне их одна.
 */
internal object Argon2id {
    private const val BLOCK_WORDS = 128
    private const val BLOCK_BYTES = 1024
    private const val SYNC_POINTS = 4
    private const val VERSION = 0x13
    private const val TYPE_ID = 2

    /**
     * Ключ длиной [length] байт из [password] и [salt]: [memoryKiB] килобайт памяти, [iterations] проходов,
     * [parallelism] полос. [secret] и [extra] — необязательные поля стандарта (нужны для проверки по RFC).
     */
    fun hash(
        password: ByteArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int,
        length: Int,
        secret: ByteArray = ByteArray(0),
        extra: ByteArray = ByteArray(0),
    ): ByteArray {
        require(parallelism >= 1 && iterations >= 1 && length >= 4 && salt.size >= 8)
        require(memoryKiB >= 8 * parallelism)
        val h0 = Blake2b(64)
            .update(int32(parallelism)).update(int32(length)).update(int32(memoryKiB)).update(int32(iterations))
            .update(int32(VERSION)).update(int32(TYPE_ID))
            .update(int32(password.size)).update(password)
            .update(int32(salt.size)).update(salt)
            .update(int32(secret.size)).update(secret)
            .update(int32(extra.size)).update(extra)
            .digest()

        val segment = memoryKiB / (SYNC_POINTS * parallelism)
        val laneLength = segment * SYNC_POINTS
        val blocks = laneLength * parallelism
        val memory = LongArray(blocks * BLOCK_WORDS)
        for (lane in 0 until parallelism) {
            for (i in 0..1) {
                val bytes = hashLong(BLOCK_BYTES, h0 + int32(i) + int32(lane))
                val at = (lane * laneLength + i) * BLOCK_WORDS
                for (w in 0 until BLOCK_WORDS) memory[at + w] = readLong(bytes, w * 8)
            }
        }

        val filler = Filler(memory, laneLength, segment, parallelism, iterations, blocks)
        for (pass in 0 until iterations) {
            for (slice in 0 until SYNC_POINTS) {
                for (lane in 0 until parallelism) filler.segment(pass, lane, slice)
            }
        }

        val last = LongArray(BLOCK_WORDS)
        for (lane in 0 until parallelism) {
            val at = (lane * laneLength + laneLength - 1) * BLOCK_WORDS
            for (w in 0 until BLOCK_WORDS) last[w] = last[w] xor memory[at + w]
        }
        val bytes = ByteArray(BLOCK_BYTES)
        for (w in 0 until BLOCK_WORDS) writeLong(bytes, w * 8, last[w])
        return hashLong(length, bytes)
    }

    /** H' стандарта: хеш любой длины из цепочки BLAKE2b по 64 байта. */
    private fun hashLong(length: Int, input: ByteArray): ByteArray {
        if (length <= 64) return Blake2b.hash(length, int32(length), input)
        val out = ByteArray(length)
        var v = Blake2b.hash(64, int32(length), input)
        v.copyInto(out, 0, 0, 32)
        var pos = 32
        while (length - pos > 64) {
            v = Blake2b.hash(64, v)
            v.copyInto(out, pos, 0, 32)
            pos += 32
        }
        Blake2b.hash(length - pos, v).copyInto(out, pos)
        return out
    }

    private class Filler(
        private val memory: LongArray,
        private val laneLength: Int,
        private val segmentLength: Int,
        private val lanes: Int,
        private val passes: Int,
        private val blocks: Int,
    ) {
        private val r = LongArray(BLOCK_WORDS)
        private val tmp = LongArray(BLOCK_WORDS)
        private val input = LongArray(BLOCK_WORDS)
        private val address = LongArray(BLOCK_WORDS)
        private val zero = LongArray(BLOCK_WORDS)

        fun segment(pass: Int, lane: Int, slice: Int) {
            // Argon2id: первая половина первого прохода — адреса не зависят от данных (как Argon2i), дальше — зависят.
            val independent = pass == 0 && slice < SYNC_POINTS / 2
            if (independent) {
                input.fill(0)
                input[0] = pass.toLong()
                input[1] = lane.toLong()
                input[2] = slice.toLong()
                input[3] = blocks.toLong()
                input[4] = passes.toLong()
                input[5] = TYPE_ID.toLong()
            }
            var start = 0
            if (pass == 0 && slice == 0) {
                start = 2
                if (independent) nextAddresses()
            }
            var current = lane * laneLength + slice * segmentLength + start
            var previous = if (current % laneLength == 0) current + laneLength - 1 else current - 1
            for (index in start until segmentLength) {
                if (current % laneLength == 1) previous = current - 1
                val random = if (independent) {
                    if (index % BLOCK_WORDS == 0) nextAddresses()
                    address[index % BLOCK_WORDS]
                } else {
                    memory[previous * BLOCK_WORDS]
                }
                val refLane = if (pass == 0 && slice == 0) lane else ((random ushr 32) % lanes).toInt()
                val refIndex = referenceIndex(pass, slice, index, random and 0xFFFFFFFFL, refLane == lane)
                fill(
                    memory, previous * BLOCK_WORDS,
                    memory, (laneLength * refLane + refIndex) * BLOCK_WORDS,
                    memory, current * BLOCK_WORDS,
                    withXor = pass != 0,
                )
                current++
                previous++
            }
        }

        private fun referenceIndex(pass: Int, slice: Int, index: Int, random: Long, sameLane: Boolean): Int {
            val area: Long = if (pass == 0) {
                when {
                    slice == 0 -> index - 1L
                    sameLane -> slice.toLong() * segmentLength + index - 1
                    else -> slice.toLong() * segmentLength + if (index == 0) -1 else 0
                }
            } else {
                if (sameLane) laneLength.toLong() - segmentLength + index - 1 else laneLength.toLong() - segmentLength + if (index == 0) -1 else 0
            }
            var relative = (random * random) ushr 32
            relative = area - 1 - ((area * relative) ushr 32)
            val startPosition = if (pass != 0 && slice != SYNC_POINTS - 1) (slice + 1).toLong() * segmentLength else 0L
            return ((startPosition + relative) % laneLength).toInt()
        }

        private fun nextAddresses() {
            input[6]++
            fill(zero, 0, input, 0, address, 0, withXor = false)
            fill(zero, 0, address, 0, address, 0, withXor = false)
        }

        /** Сжатие G: next = P(prev ⊕ ref) ⊕ prev ⊕ ref, с [withXor] — ещё ⊕ прежний next (проходы после первого). */
        private fun fill(
            prev: LongArray, prevAt: Int,
            ref: LongArray, refAt: Int,
            next: LongArray, nextAt: Int,
            withXor: Boolean,
        ) {
            for (w in 0 until BLOCK_WORDS) r[w] = ref[refAt + w] xor prev[prevAt + w]
            r.copyInto(tmp)
            if (withXor) for (w in 0 until BLOCK_WORDS) tmp[w] = tmp[w] xor next[nextAt + w]
            for (i in 0 until 8) {
                val b = 16 * i
                round(b, b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7, b + 8, b + 9, b + 10, b + 11, b + 12, b + 13, b + 14, b + 15)
            }
            for (i in 0 until 8) {
                val b = 2 * i
                round(b, b + 1, b + 16, b + 17, b + 32, b + 33, b + 48, b + 49, b + 64, b + 65, b + 80, b + 81, b + 96, b + 97, b + 112, b + 113)
            }
            for (w in 0 until BLOCK_WORDS) next[nextAt + w] = tmp[w] xor r[w]
        }

        private fun round(
            v0: Int, v1: Int, v2: Int, v3: Int, v4: Int, v5: Int, v6: Int, v7: Int,
            v8: Int, v9: Int, v10: Int, v11: Int, v12: Int, v13: Int, v14: Int, v15: Int,
        ) {
            gb(v0, v4, v8, v12)
            gb(v1, v5, v9, v13)
            gb(v2, v6, v10, v14)
            gb(v3, v7, v11, v15)
            gb(v0, v5, v10, v15)
            gb(v1, v6, v11, v12)
            gb(v2, v7, v8, v13)
            gb(v3, v4, v9, v14)
        }

        private fun gb(a: Int, b: Int, c: Int, d: Int) {
            r[a] = mix(r[a], r[b])
            r[d] = (r[d] xor r[a]).rotateRight(32)
            r[c] = mix(r[c], r[d])
            r[b] = (r[b] xor r[c]).rotateRight(24)
            r[a] = mix(r[a], r[b])
            r[d] = (r[d] xor r[a]).rotateRight(16)
            r[c] = mix(r[c], r[d])
            r[b] = (r[b] xor r[c]).rotateRight(63)
        }

        private fun mix(x: Long, y: Long): Long = x + y + 2 * (x and 0xFFFFFFFFL) * (y and 0xFFFFFFFFL)
    }
}
