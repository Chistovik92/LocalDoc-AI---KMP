package com.localdoc.ai.sync

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom

/** Данные не расшифровались: неверный пароль, файл повреждён или оборван при загрузке. */
class SyncDecryptException(message: String) : Exception(message)

/** Шифрование документа синхронизации на устройстве, до отправки. */
interface SyncCipher {
    suspend fun seal(plain: ByteArray): ByteArray
    suspend fun open(sealed: ByteArray): ByteArray
}

/**
 * Пароль → ключ через PBKDF2-HMAC-SHA256, шифр — AES-256-GCM (библиотека cryptography-kotlin: JCA на Android,
 * CryptoKit/CommonCrypto на iOS; своей криптографии нет). Файл:
 * `"LDSY" | версия(1) | KDF(1) | итерации(4, big-endian) | соль(16) | IV(12) | шифртекст | тег(16)`.
 * Заголовок (до IV) подписывается GCM как дополнительные данные, поэтому подмена числа итераций или соли замечается.
 * Пароль нигде не хранится и в облако не уходит.
 */
class PasswordCipher(
    password: String,
    private val iterations: Int = DEFAULT_ITERATIONS
) : SyncCipher {

    private val password = password.encodeToByteArray()

    init {
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Число итераций вне допустимых границ" }
    }

    override suspend fun seal(plain: ByteArray): ByteArray {
        val salt = CryptographyRandom.nextBytes(SALT_SIZE)
        val header = header(iterations, salt)
        val cipher = key(iterations, salt).cipher()
        return header + cipher.encrypt(plaintext = plain, associatedData = header)
    }

    override suspend fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < HEADER_SIZE + MIN_BODY || !sealed.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw SyncDecryptException("Это не зашифрованный файл синхронизации LocalDoc")
        }
        if (sealed[4] != VERSION || sealed[5] != KDF_PBKDF2_SHA256) {
            throw SyncDecryptException("Файл синхронизации создан более новой версией LocalDoc")
        }
        val iters = ((sealed[6].toInt() and 0xFF) shl 24) or ((sealed[7].toInt() and 0xFF) shl 16) or
            ((sealed[8].toInt() and 0xFF) shl 8) or (sealed[9].toInt() and 0xFF)
        if (iters !in MIN_ITERATIONS..MAX_ITERATIONS) throw SyncDecryptException("Файл синхронизации повреждён")
        val header = sealed.copyOfRange(0, HEADER_SIZE)
        val salt = sealed.copyOfRange(10, HEADER_SIZE)
        return try {
            key(iters, salt).cipher().decrypt(ciphertext = sealed.copyOfRange(HEADER_SIZE, sealed.size), associatedData = header)
        } catch (e: Exception) {
            throw SyncDecryptException("Неверный пароль шифрования или файл повреждён")
        }
    }

    private suspend fun key(iterations: Int, salt: ByteArray): AES.GCM.Key {
        val provider = CryptographyProvider.Default
        val raw = provider.get(PBKDF2).secretDerivation(
            digest = SHA256, iterations = iterations, outputSize = 256.bits, salt = salt
        ).deriveSecretToByteArray(password)
        return provider.get(AES.GCM).keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, raw)
    }

    private fun header(iterations: Int, salt: ByteArray): ByteArray =
        MAGIC + byteArrayOf(
            VERSION, KDF_PBKDF2_SHA256,
            (iterations ushr 24).toByte(), (iterations ushr 16).toByte(), (iterations ushr 8).toByte(), iterations.toByte()
        ) + salt

    companion object {
        /** Рекомендация OWASP для PBKDF2-HMAC-SHA256. */
        const val DEFAULT_ITERATIONS = 600_000
        const val MIN_ITERATIONS = 1_000
        const val MAX_ITERATIONS = 5_000_000
        private val MAGIC = byteArrayOf('L'.code.toByte(), 'D'.code.toByte(), 'S'.code.toByte(), 'Y'.code.toByte())
        private const val VERSION: Byte = 1
        private const val KDF_PBKDF2_SHA256: Byte = 1
        private const val SALT_SIZE = 16
        private const val HEADER_SIZE = 10 + SALT_SIZE
        /** IV (12) + тег (16). */
        private const val MIN_BODY = 28
    }
}
