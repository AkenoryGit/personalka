package com.akenory.personalka.data.backup

/**
 * Архив zip без Java (iPhone, шаг 5.1): запись — файлы со сжатием deflate, чтение — по центральному каталогу
 * (Java пишет размеры после данных, в локальных заголовках их нет). Читаются «без сжатия» и deflate.
 */
internal object Zip {
    fun write(files: Map<String, ByteArray>, deflate: (ByteArray) -> ByteArray): ByteArray {
        val out = Bytes()
        val central = Bytes()
        files.forEach { (name, bytes) ->
            val nameBytes = name.encodeToByteArray()
            val packed = deflate(bytes)
            val crc = BackupPlatform.crc(bytes)
            val offset = out.size
            out.int(0x04034b50); out.short(20); out.short(FLAG_UTF8); out.short(8); out.short(0); out.short(0x21)
            out.int(crc.toInt()); out.int(packed.size); out.int(bytes.size); out.short(nameBytes.size); out.short(0)
            out.put(nameBytes); out.put(packed)
            central.int(0x02014b50); central.short(20); central.short(20); central.short(FLAG_UTF8); central.short(8)
            central.short(0); central.short(0x21); central.int(crc.toInt()); central.int(packed.size); central.int(bytes.size)
            central.short(nameBytes.size); central.short(0); central.short(0); central.short(0); central.short(0)
            central.int(0); central.int(offset); central.put(nameBytes)
        }
        val dirAt = out.size
        out.put(central.toByteArray())
        out.int(0x06054b50); out.short(0); out.short(0); out.short(files.size); out.short(files.size)
        out.int(central.size); out.int(dirAt); out.short(0)
        return out.toByteArray()
    }

    fun read(bytes: ByteArray): Map<String, ByteArray>? = runCatching {
        var end = bytes.size - 22
        while (end >= 0 && int(bytes, end) != 0x06054b50) end--
        if (end < 0) return null
        val count = short(bytes, end + 10)
        var at = int(bytes, end + 16)
        val files = mutableMapOf<String, ByteArray>()
        repeat(count) {
            if (int(bytes, at) != 0x02014b50) return null
            val method = short(bytes, at + 10)
            val crc = int(bytes, at + 16)
            val packedSize = int(bytes, at + 20)
            val size = int(bytes, at + 24)
            val nameLen = short(bytes, at + 28)
            val extraLen = short(bytes, at + 30)
            val commentLen = short(bytes, at + 32)
            val local = int(bytes, at + 42)
            val name = bytes.decodeToString(at + 46, at + 46 + nameLen)
            at += 46 + nameLen + extraLen + commentLen
            if (name.endsWith("/")) return@repeat
            val dataAt = local + 30 + short(bytes, local + 26) + short(bytes, local + 28)
            val packed = bytes.copyOfRange(dataAt, dataAt + packedSize)
            val plain = when (method) {
                0 -> packed
                8 -> BackupPlatform.inflateRaw(packed, size) ?: return null
                else -> return null
            }
            if (plain.size != size || BackupPlatform.crc(plain).toInt() != crc) return null
            files[name] = plain
        }
        files.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun short(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun int(b: ByteArray, at: Int): Int = short(b, at) or (short(b, at + 2) shl 16)

    /** Флаг 11: имена в UTF-8. */
    private const val FLAG_UTF8 = 0x0800

    private class Bytes {
        private var data = ByteArray(1024)
        var size = 0
            private set

        fun put(b: ByteArray) {
            if (size + b.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + b.size))
            b.copyInto(data, size)
            size += b.size
        }

        fun short(v: Int) = put(byteArrayOf(v.toByte(), (v ushr 8).toByte()))

        fun int(v: Int) = put(byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte()))

        fun toByteArray(): ByteArray = data.copyOf(size)
    }
}
