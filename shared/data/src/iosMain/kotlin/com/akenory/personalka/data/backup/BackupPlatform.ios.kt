package com.akenory.personalka.data.backup

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCSuccess
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import platform.posix.size_tVar
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import platform.posix.memset
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream
import platform.zlib.zlibVersion
import platform.zlib.compress2
import platform.zlib.compressBound
import platform.zlib.crc32
import platform.zlib.uLongfVar

/**
 * Копии на iPhone (шаг 5.1): случайные байты — Security, SHA-256 и блок AES — CommonCrypto, сжатие и CRC — zlib.
 * Режима GCM в открытом CommonCrypto нет — он собран здесь из блока AES ([Gcm]), файлы совпадают с Java до байта.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object BackupPlatform {
    actual fun randomBytes(size: Int): ByteArray {
        val out = ByteArray(size)
        if (size == 0) return out
        val status = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0)) }
        check(status == errSecSuccess) { "SecRandomCopyBytes: $status" }
        return out
    }

    actual fun sha256(data: ByteArray): ByteArray {
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        out.asUBytes { digest ->
            if (data.isEmpty()) {
                CC_SHA256(null, 0u, digest)
            } else {
                data.usePinned { CC_SHA256(it.addressOf(0), data.size.convert(), digest) }
            }
        }
        return out
    }

    actual fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray = Gcm(key).seal(nonce, plain)

    actual fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray): ByteArray? = Gcm(key).open(nonce, sealed)

    actual fun zip(files: Map<String, ByteArray>): ByteArray = Zip.write(files) { deflateRaw(it) }

    actual fun unzip(bytes: ByteArray): Map<String, ByteArray>? = Zip.read(bytes)

    /** CRC-32 файла в архиве. */
    internal fun crc(bytes: ByteArray): Long =
        if (bytes.isEmpty()) 0L else bytes.asUBytesRead { crc32(0u, it, bytes.size.convert()).toLong() }

    /**
     * Сжатие deflate без обёртки zlib: `compress2` даёт 2 байта заголовка, сам deflate и 4 байта Adler-32 —
     * в zip идёт только середина.
     */
    private fun deflateRaw(bytes: ByteArray): ByteArray = memScoped {
        val bound = compressBound(bytes.size.convert()).toInt()
        val out = ByteArray(bound)
        val size = alloc<uLongfVar>().apply { value = bound.convert() }
        val status = out.asUBytes { dest ->
            if (bytes.isEmpty()) {
                compress2(dest, size.ptr, null, 0u, 9)
            } else {
                bytes.asUBytesRead { compress2(dest, size.ptr, it, bytes.size.convert(), 9) }
            }
        }
        check(status == Z_OK) { "compress2: $status" }
        out.copyOfRange(2, size.value.toInt() - 4)
    }

    /** Распаковка deflate без обёртки размером [size] (размер — из каталога zip); `null` — данные повреждены. */
    internal fun inflateRaw(packed: ByteArray, size: Int): ByteArray? = memScoped {
        val out = ByteArray(size)
        if (size == 0) return out
        val stream = alloc<z_stream>()
        memset(stream.ptr, 0, sizeOf<z_stream>().convert())
        if (inflateInit2_(stream.ptr, -15, zlibVersion()?.toKString(), sizeOf<z_stream>().toInt()) != Z_OK) return null
        val status = packed.usePinned { input ->
            out.usePinned { output ->
                stream.next_in = input.addressOf(0).reinterpret()
                stream.avail_in = packed.size.convert()
                stream.next_out = output.addressOf(0).reinterpret()
                stream.avail_out = size.convert()
                inflate(stream.ptr, Z_FINISH)
            }
        }
        inflateEnd(stream.ptr)
        if (status == Z_STREAM_END && stream.total_out.toInt() == size) out else null
    }

    /** AES-256 по блокам (режим ECB): ключевой поток GCM — шифр счётчиков одним вызовом. */
    internal fun aesBlocks(key: ByteArray, blocks: ByteArray): ByteArray {
        val out = ByteArray(blocks.size)
        if (blocks.isEmpty()) return out
        memScoped {
            val moved = alloc<size_tVar>()
            val status = key.usePinned { k ->
                blocks.usePinned { input ->
                    out.usePinned { output ->
                        CCCrypt(
                            kCCEncrypt, kCCAlgorithmAES, kCCOptionECBMode,
                            k.addressOf(0), key.size.convert(), null,
                            input.addressOf(0), blocks.size.convert(),
                            output.addressOf(0), out.size.convert(), moved.ptr,
                        )
                    }
                }
            }
            check(status == kCCSuccess) { "CCCrypt: $status" }
        }
        return out
    }
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <R> ByteArray.asUBytes(block: (kotlinx.cinterop.CPointer<UByteVar>) -> R): R =
    usePinned { block(it.addressOf(0).reinterpret()) }

@OptIn(ExperimentalForeignApi::class)
private inline fun <R> ByteArray.asUBytesRead(block: (kotlinx.cinterop.CPointer<UByteVar>) -> R): R =
    usePinned { block(it.addressOf(0).reinterpret()) }
