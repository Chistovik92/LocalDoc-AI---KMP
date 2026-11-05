package com.localdoc.ai.tts

import kotlin.test.Test
import kotlin.test.assertEquals

class SentenceSplitterTest {
    private fun split(text: String): List<String> =
        SentenceSplitter.ranges(text).map { (s, e) -> text.substring(s, e) }

    @Test
    fun `plain russian and english sentences`() {
        assertEquals(listOf("Он пришёл.", "Она ушла!", "Кто там?"), split("Он пришёл. Она ушла! Кто там?"))
        assertEquals(listOf("It rained.", "We stayed home."), split("It rained. We stayed home."))
    }

    @Test
    fun `abbreviations do not end a sentence`() {
        assertEquals(listOf("Это важно, т. е. нужно сделать сейчас.", "Потом отдых."), split("Это важно, т. е. нужно сделать сейчас. Потом отдых."))
        assertEquals(listOf("Купил хлеб, молоко и т. д.", "Потом ушёл."), split("Купил хлеб, молоко и т. д. Потом ушёл."))
        assertEquals(listOf("Он жил в г. Москве на ул. Ленина.", "Работал много."), split("Он жил в г. Москве на ул. Ленина. Работал много."))
        assertEquals(listOf("Мы жили в г. Москва."), split("Мы жили в г. Москва."))
        assertEquals(listOf("Mr. Smith met Dr. Jones.", "They talked."), split("Mr. Smith met Dr. Jones. They talked."))
        assertEquals(listOf("Apples, pears, etc.", "Then we left."), split("Apples, pears, etc. Then we left."))
    }

    @Test
    fun `initials stay with the surname`() {
        assertEquals(listOf("А. С. Пушкин родился в Москве.", "Он писал стихи."), split("А. С. Пушкин родился в Москве. Он писал стихи."))
        assertEquals(listOf("J. R. R. Tolkien wrote it."), split("J. R. R. Tolkien wrote it."))
    }

    @Test
    fun `ellipsis splits only before a capital`() {
        assertEquals(listOf("Он думал... и молчал.", "Потом ушёл."), split("Он думал... и молчал. Потом ушёл."))
        assertEquals(listOf("Он замолчал…", "Потом заговорил."), split("Он замолчал… Потом заговорил."))
    }

    @Test
    fun `dash dialogue keeps attribution and splits replies`() {
        assertEquals(
            listOf("— Привет, — сказал он.", "— Как дела?", "— Хорошо."),
            split("— Привет, — сказал он. — Как дела? — Хорошо.")
        )
        assertEquals(listOf("— Куда? — спросил он.", "— Домой."), split("— Куда? — спросил он. — Домой."))
        assertEquals(listOf("— Стой! — крикнул он."), split("— Стой! — крикнул он."))
    }

    @Test
    fun `quotes after the full stop stay with their sentence`() {
        assertEquals(listOf("Он сказал: «Привет.»", "Она кивнула."), split("Он сказал: «Привет.» Она кивнула."))
        assertEquals(listOf("Он сказал: „Привет.“", "Она кивнула."), split("Он сказал: „Привет.“ Она кивнула."))
        assertEquals(listOf("He said \"Hello.\"", "She nodded."), split("He said \"Hello.\" She nodded."))
        assertEquals(listOf("«Уходи!» — сказала она."), split("«Уходи!» — сказала она."))
    }

    @Test
    fun `decimals versions and links are not split`() {
        assertEquals(listOf("Число пи равно 3.14 примерно.", "Дальше."), split("Число пи равно 3.14 примерно. Дальше."))
        assertEquals(listOf("Вышла версия v2.0.1 вчера.", "Скачайте её."), split("Вышла версия v2.0.1 вчера. Скачайте её."))
        assertEquals(listOf("Смотрите https://example.com/a.b?x=1 сегодня.", "Там всё."), split("Смотрите https://example.com/a.b?x=1 сегодня. Там всё."))
        assertEquals(listOf("Сайт www.example.com работает.", "Заходите."), split("Сайт www.example.com работает. Заходите."))
    }

    @Test
    fun `chapter and list numbers are not sentence ends`() {
        assertEquals(listOf("Глава 5. Начало пути."), split("Глава 5. Начало пути."))
        assertEquals(listOf("Chapter 12. The return home."), split("Chapter 12. The return home."))
        assertEquals(listOf("XIV. Последняя встреча."), split("XIV. Последняя встреча."))
        assertEquals(listOf("1. Первый пункт списка."), split("1. Первый пункт списка."))
        assertEquals(listOf("Это было в 1990.", "Потом наступил 1991."), split("Это было в 1990. Потом наступил 1991."))
    }

    @Test
    fun `lowercase continuation is not a boundary`() {
        assertEquals(listOf("Русск. дореф. название книги.", "Конец."), split("Русск. дореф. название книги. Конец."))
        assertEquals(listOf("Он сказал это. и всё."), split("Он сказал это. и всё."))
    }

    @Test
    fun `offsets point into the original text`() {
        val text = "Первое.  Второе!\n\nТретье?"
        val ranges = SentenceSplitter.ranges(text)
        assertEquals(listOf("Первое.", "Второе!", "Третье?"), ranges.map { text.substring(it.first, it.second) })
    }

    @Test
    fun `empty and blank text give no sentences`() {
        assertEquals(emptyList(), split(""))
        assertEquals(emptyList(), split("   \n "))
    }
}
