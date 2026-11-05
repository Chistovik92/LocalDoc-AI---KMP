package com.localdoc.ai.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SyncCryptoTest {
    private val plain = """{"records":[{"id":"bookmark/ab/1","data":"Цитата из книги"}]}""".encodeToByteArray()

    @Test
    fun `sealed data opens with the same password and holds no plain text`() = runTest {
        val sealed = FastCipher("правильный пароль").seal(plain)
        assertFalse(sealed.decodeToString().contains("Цитата"))
        assertContentEquals(plain, FastCipher("правильный пароль").open(sealed))
    }

    @Test
    fun `wrong password and tampered data are rejected`() = runTest {
        val sealed = FastCipher("pw1").seal(plain)
        assertFailsWith<SyncDecryptException> { FastCipher("pw2").open(sealed) }
        val flipped = sealed.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        assertFailsWith<SyncDecryptException> { FastCipher("pw1").open(flipped) }
        // подмена числа итераций в заголовке замечается
        val header = sealed.copyOf().also { it[9] = (it[9].toInt() xor 1).toByte() }
        assertFailsWith<SyncDecryptException> { FastCipher("pw1").open(header) }
    }

    @Test
    fun `truncated or foreign files are rejected`() = runTest {
        val sealed = FastCipher("pw").seal(plain)
        assertFailsWith<SyncDecryptException> { FastCipher("pw").open(sealed.copyOf(sealed.size / 2)) }
        assertFailsWith<SyncDecryptException> { FastCipher("pw").open("просто текст, не наш файл......".encodeToByteArray()) }
    }

    @Test
    fun `every seal uses a new salt and nonce`() = runTest {
        val cipher = FastCipher("pw")
        assertFalse(cipher.seal(plain).contentEquals(cipher.seal(plain)))
    }
}
