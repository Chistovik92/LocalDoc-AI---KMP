package com.localdoc.ai.notes

import kotlin.test.Test
import kotlin.test.assertEquals

class WordLinksTest {

    @Test
    fun `cyrillic word is percent encoded as utf-8`() {
        assertEquals("https://duckduckgo.com/?q=%D0%BA%D0%BD%D0%B8%D0%B3%D0%B0", WordLinks.webSearch("книга"))
    }

    @Test
    fun `spaces and reserved characters are escaped`() {
        assertEquals("https://duckduckgo.com/?q=a%20b%26c", WordLinks.webSearch(" a b&c "))
    }

    @Test
    fun `wikipedia uses the language of the word`() {
        assertEquals("https://ru.wikipedia.org/w/index.php?search=%D0%BA%D0%BD%D0%B8%D0%B3%D0%B0", WordLinks.wikipedia("книга", "ru"))
        assertEquals("https://pt.wikipedia.org/w/index.php?search=livro", WordLinks.wikipedia("livro", "pt-BR"))
    }

    @Test
    fun `wikipedia falls back to english for unknown or invalid language`() {
        assertEquals("https://en.wikipedia.org/w/index.php?search=word", WordLinks.wikipedia("word", null))
        assertEquals("https://en.wikipedia.org/w/index.php?search=word", WordLinks.wikipedia("word", "evil.com/"))
    }
}
