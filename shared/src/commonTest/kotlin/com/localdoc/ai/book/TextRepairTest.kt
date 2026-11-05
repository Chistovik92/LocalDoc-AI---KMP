package com.localdoc.ai.book

import com.localdoc.ai.notes.AnchorResolver
import com.localdoc.ai.notes.TextAnchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextRepairTest {
    private fun p(text: String) = Block(BlockType.PARAGRAPH, text)
    private fun texts(blocks: List<Block>) = blocks.map { it.text }
    private fun fix(vararg lines: String) = texts(TextRepair.repair(lines.map(::p)).blocks)

    @Test
    fun `line cut in the middle of a sentence is glued`() {
        assertEquals(listOf("Он пошёл в магазин за хлебом."), fix("Он пошёл в", "магазин за хлебом."))
        assertEquals(listOf("She walked to the market"), fix("She walked to the", "market"))
        assertEquals(listOf("Он сказал, что придёт, и пришёл."), fix("Он сказал, что придёт,", "и пришёл."))
    }

    @Test
    fun `word hyphenated at the line end is restored`() {
        assertEquals(listOf("Это очень важное событие."), fix("Это очень важ-", "ное событие."))
        assertEquals(listOf("An international meeting."), fix("An inter-", "national meeting."))
        assertEquals(listOf("Это очень важное событие."), fix("Это очень важ-\nное событие."))
        assertEquals(listOf("Слово слово"), fix("Слово\nслово"))
    }

    @Test
    fun `real hyphens and dashes survive`() {
        assertEquals(listOf("Кто-то пришёл."), fix("Кто-", "то пришёл."))
        assertEquals(listOf("Он сказал по-русски."), fix("Он сказал по-", "русски."))
        assertEquals(listOf("A self-made man."), fix("A self-", "made man."))
        assertEquals(listOf("Он знал — слово держать."), fix("Он знал —", "слово держать."))
        assertEquals(listOf("Бежать-бежать."), fix("Бежать-бежать."))
    }

    @Test
    fun `glued paragraphs are separated`() {
        assertEquals(listOf("Это конец первого.", "Начало второго абзаца."), fix("Это конец первого.Начало второго абзаца."))
        assertEquals(listOf("Что случилось?", "Ничего не случилось."), fix("Что случилось?Ничего не случилось."))
    }

    @Test
    fun `dots inside names numbers and links are not paragraph breaks`() {
        for (s in listOf("Мистер Mr.Smith приехал.", "Число 3.14 известно.", "Откройте example.Com сегодня.", "Файл Node.js готов.", "Это т.е.Иван.")) {
            assertEquals(listOf(s), fix(s), s)
        }
    }

    @Test
    fun `extra spaces and spaces before punctuation are removed`() {
        assertEquals(listOf("Привет, мир! Как дела?"), fix("Привет , мир !  Как дела ?"))
        assertEquals(listOf("Он сказал (тихо) и ушёл."), fix("Он  сказал (тихо ) и   ушёл ."))
        assertEquals(listOf("Файл .txt открыт."), fix("Файл .txt открыт."))
    }

    @Test
    fun `page numbers and running headers are dropped and the sentence is mended`() {
        val raw = listOf(
            "Война и мир", "Он долго шёл по дороге и вдруг увидел, что", "12", "Война и мир",
            "впереди стоит дом.", "Потом он вошёл внутрь.", "Война и мир", "13", "Война и мир",
            "Там было тихо."
        )
        assertEquals(
            listOf("Он долго шёл по дороге и вдруг увидел, что впереди стоит дом.", "Потом он вошёл внутрь.", "Там было тихо."),
            fix(*raw.toTypedArray())
        )
    }

    @Test
    fun `numbered page lines in a row are removed but a lone number stays`() {
        val result = TextRepair.repair(listOf("Текст раз.", "- 7 -", "Текст два.", "Страница 8").map(::p))
        assertEquals(listOf("Текст раз.", "Текст два."), texts(result.blocks))
        assertEquals(1, result.newIndex(1))
        assertEquals(1, result.newIndex(3))
        assertEquals(listOf("Итого получилось.", "42", "Конец."), fix("Итого получилось.", "42", "Конец."))
    }

    @Test
    fun `headings lists dialogue and capitalised lines are not glued`() {
        val blocks = listOf(
            Block(BlockType.HEADING, "Введение", level = 1),
            p("Он вошёл"),
            Block(BlockType.LIST_ITEM, "первый пункт"),
            p("— Привет, — сказал он"),
            p("— Привет"),
            p("Пётр Иванович улыбнулся"),
            p("Конец")
        )
        assertEquals(texts(blocks), texts(TextRepair.repair(blocks).blocks))
    }

    @Test
    fun `repeated chapter headings are not mistaken for running headers`() {
        val raw = (1..5).flatMap { listOf("Глава $it", "Текст главы номер $it здесь.") }
        assertEquals(raw, fix(*raw.toTypedArray()))
    }

    @Test
    fun `repeated short replies are not mistaken for running headers`() {
        val raw = listOf("Да.", "Он ушёл.", "Да.", "Она ждала.", "Да.", "Все ушли.", "Да.")
        assertEquals(raw, fix(*raw.toTypedArray()))
    }

    @Test
    fun `ragged english sample from a pdf`() {
        val raw = listOf(
            "It was a bright cold day in April, and the clocks were",
            "striking thirteen. Winston Smith slipped quickly through",
            "the glass doors of Victory Man-",
            "sions, though not quickly enough to prevent a swirl of",
            "gritty dust from entering along with him."
        )
        assertEquals(
            listOf(
                "It was a bright cold day in April, and the clocks were striking thirteen. " +
                    "Winston Smith slipped quickly through the glass doors of Victory Mansions, though not quickly enough " +
                    "to prevent a swirl of gritty dust from entering along with him."
            ),
            fix(*raw.toTypedArray())
        )
    }

    @Test
    fun `ragged russian sample from a txt`() {
        val raw = listOf(
            "Всё смешалось в доме Облонских. Жена узнала, что муж был в связи с бывшею в их доме француженкой, гувер-",
            "нанткой, и объявила мужу, что не может жить с ним в одном",
            "доме.",
            "— Как же быть? — спросил он.",
            "— Не знаю , — ответила она.Больше они не говорили."
        )
        assertEquals(
            listOf(
                "Всё смешалось в доме Облонских. Жена узнала, что муж был в связи с бывшею в их доме француженкой, гувернанткой, " +
                    "и объявила мужу, что не может жить с ним в одном доме.",
                "— Как же быть? — спросил он.",
                "— Не знаю, — ответила она.",
                "Больше они не говорили."
            ),
            fix(*raw.toTypedArray())
        )
    }

    @Test
    fun `blocks with spans keep their text and shift spans when glued`() {
        val bold = Block(BlockType.PARAGRAPH, "Это  жирно", spans = listOf(Span(4, 10, SpanStyleType.BOLD)))
        assertEquals("Это  жирно", TextRepair.repair(listOf(bold)).blocks.single().text)

        val a = p("Начало фразы")
        val b = Block(BlockType.PARAGRAPH, "и жирный хвост.", spans = listOf(Span(2, 8, SpanStyleType.BOLD)))
        val glued = TextRepair.repair(listOf(a, b)).blocks.single()
        assertEquals("Начало фразы и жирный хвост.", glued.text)
        assertEquals("жирный", glued.text.substring(glued.spans.single().start, glued.spans.single().end))
    }

    @Test
    fun `old block numbers map to the new ones`() {
        val result = TextRepair.repair(listOf(p("Раз два"), p("три четыре."), p("Пять."), p("Шесть.")))
        assertEquals(listOf("Раз два три четыре.", "Пять.", "Шесть."), texts(result.blocks))
        assertEquals(0, result.newIndex(0))
        assertEquals(0, result.newIndex(1))
        assertEquals(1, result.newIndex(2))
        assertEquals(2, result.newIndex(3))
    }

    @Test
    fun `repair is idempotent and reports changes`() {
        val raw = listOf("Он пошёл в", "магазин за хлебом .", "Потом домой.Там ждали.")
        val once = TextRepair.repair(raw.map(::p))
        assertTrue(once.changes.isNotEmpty())
        val twice = TextRepair.repair(once.blocks)
        assertEquals(texts(once.blocks), texts(twice.blocks))
        assertTrue(twice.changes.isEmpty())
    }

    @Test
    fun `paragraph sized blocks without punctuation are not glued into one`() {
        val blocks = (0 until 200).map { p("заметка про слово " + "текст ".repeat(25) + it) }
        assertEquals(blocks.size, TextRepair.repair(blocks).blocks.size)
    }

    @Test
    fun `short hard wrapped lines are glued even in a long text`() {
        val lines = (0 until 400).map { p("строка номера " + "слово ".repeat(8) + it) }
        val result = TextRepair.repair(lines)
        assertTrue(result.blocks.size < 20)
        assertTrue(result.blocks.all { it.text.length <= 8_000 })
    }

    @Test
    fun `bare number chapter headings are kept`() {
        val blocks = listOf(p("1"), p("Текст первой главы."), p("2"), p("Текст второй главы."), p("3"), p("Текст третьей главы."))
        val book = Book(title = "T", format = BookFormat.TEXT, blocks = blocks, chapters = listOf(Chapter("1", 0), Chapter("2", 2), Chapter("3", 4)))
        assertEquals(blocks, TextRepair.repairBook(book).blocks)
    }

    @Test
    fun `pdf pages are not glued together`() {
        val blocks = listOf(p("Предложение начинается на первой"), p("странице и продолжается на второй."))
        val book = Book(title = "T", format = BookFormat.PDF, blocks = blocks, chapters = listOf(Chapter("Страница 1", 0), Chapter("Страница 2", 1)))
        val fixed = TextRepair.repairBook(book)
        assertEquals(2, fixed.blocks.size)
        assertEquals(listOf(0, 1), fixed.chapters.map { it.startBlock })
    }

    @Test
    fun `clean text is returned untouched`() {
        val clean = listOf(p("Первый абзац."), p("Второй абзац."), Block(BlockType.CODE, "val  x =  1"))
        val result = TextRepair.repair(clean)
        assertEquals(clean, result.blocks)
        assertTrue(result.changes.isEmpty())
    }

    @Test
    fun `book chapters follow the moved blocks`() {
        val book = Book(
            title = "T", format = BookFormat.TEXT,
            blocks = listOf(p("Раз два"), p("три."), Block(BlockType.HEADING, "Глава 2", level = 1), p("Текст.")),
            chapters = listOf(Chapter("Начало", 0), Chapter("Глава 2", 2))
        )
        val fixed = TextRepair.repairBook(book)
        assertEquals(3, fixed.blocks.size)
        assertEquals(listOf(0, 1), fixed.chapters.map { it.startBlock })
    }

    @Test
    fun `quotes and bookmarks still find their place after repair`() {
        val blocks = listOf(
            p("Вступление идёт в"), p("первом абзаце."), p("Дальше идёт вторая часть истории, она длинная и интересная."),
            p("Третья часть истории заканчивается здесь.")
        )
        val chapters = listOf(Chapter("Часть", 2))
        val quote = "вторая часть истории"
        val from = blocks[2].text.indexOf(quote)
        val anchor = TextAnchor.create(blocks, chapters, 2, from, 2, from + quote.length)
        val bookmark = TextAnchor.ofBlock(blocks, chapters, 3)

        val result = TextRepair.repair(blocks)
        val newChapters = chapters.map { Chapter(it.title, result.newIndex(it.startBlock)) }

        val hit = AnchorResolver.resolve(anchor, result.blocks, newChapters)
        assertTrue(hit.exact)
        assertEquals(result.newIndex(2), hit.block)
        assertEquals(quote, result.blocks[hit.block].text.substring(hit.start, hit.end))

        val mark = AnchorResolver.resolve(bookmark, result.blocks, newChapters)
        assertTrue(mark.exact)
        assertEquals(result.newIndex(3), mark.block)
    }

    @Test
    fun `quote inside a joined tail is found at the shifted offset`() {
        val blocks = listOf(p("Вступление идёт в"), p("первом абзаце, где важна фраза про море."))
        val quote = "фраза про море"
        val from = blocks[1].text.indexOf(quote)
        val anchor = TextAnchor.create(blocks, emptyList(), 1, from, 1, from + quote.length)
        val result = TextRepair.repair(blocks)
        val hit = AnchorResolver.resolve(anchor, result.blocks, emptyList())
        assertTrue(hit.exact)
        assertEquals(0, hit.block)
        assertEquals(quote, result.blocks[0].text.substring(hit.start, hit.end))
    }

    @Test
    fun `uncertain breaks are offered for a closer look`() {
        val blocks = listOf(
            p("Вчера вечером я долго разговаривал с моим старым другом"), p("Иваном о том, что произошло"), p("Конец."),
            p("— Привет"), p("Тихо.")
        )
        val automatic = TextRepair.repair(blocks)
        assertEquals(texts(blocks), texts(automatic.blocks))
        val suspects = TextRepair.suspects(automatic.blocks)
        assertTrue(0 in suspects)
        assertTrue(2 !in suspects && 3 !in suspects)

        val joined = TextRepair.edit(automatic.blocks, joinAfter = setOf(0))
        assertEquals(blocks.size - 1, joined.blocks.size)
        assertEquals("Вчера вечером я долго разговаривал с моим старым другом Иваном о том, что произошло", joined.blocks[0].text)
        assertEquals(0, joined.newIndex(1))
    }

    @Test
    fun `a block can be split at chosen sentences`() {
        val text = "Первое предложение абзаца. Второе предложение абзаца. Третье предложение абзаца."
        val cut = text.indexOf("Третье")
        val result = TextRepair.edit(listOf(p(text), p("Дальше.")), splits = mapOf(0 to listOf(cut)))
        assertEquals(listOf("Первое предложение абзаца. Второе предложение абзаца.", "Третье предложение абзаца.", "Дальше."), texts(result.blocks))
        assertEquals(2, result.newIndex(1))
    }

    @Test
    fun `edits compose into one map`() {
        val first = TextRepair.repair(listOf(p("Раз два"), p("три."), p("Четыре")))
        val second = TextRepair.edit(first.blocks, joinAfter = setOf(0))
        val total = first.then(second)
        assertEquals(second.blocks, total.blocks)
        assertEquals(0, total.newIndex(1))
        assertEquals(0, total.newIndex(2))
    }
}
