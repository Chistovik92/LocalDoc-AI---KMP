package com.localdoc.ai.rag

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {

    private fun hex(text: String, chunk: Int = Int.MAX_VALUE): String {
        val bytes = text.encodeToByteArray()
        val sha = Sha256()
        var pos = 0
        while (pos < bytes.size) {
            val n = minOf(chunk, bytes.size - pos)
            sha.update(bytes.copyOfRange(pos, pos + n), n)
            pos += n
        }
        return sha.hexDigest()
    }

    @Test
    fun emptyInput() =
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(""))

    @Test
    fun abc() =
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex("abc"))

    @Test
    fun twoBlockMessage() = assertEquals(
        "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
        hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")
    )

    @Test
    fun millionAs() = assertEquals(
        "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
        hex("a".repeat(1_000_000), chunk = 65_536)
    )

    @Test
    fun chunkingDoesNotChangeResult() {
        val text = "Привет, мир! ".repeat(100)
        assertEquals(hex(text), hex(text, chunk = 7))
        assertEquals(hex(text), hex(text, chunk = 64))
    }
}
