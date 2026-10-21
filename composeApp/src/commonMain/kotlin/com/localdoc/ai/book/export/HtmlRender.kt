package com.localdoc.ai.book.export

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType

/** Блоки книги в (X)HTML — общая часть экспорта в EPUB и HTML. */
internal object HtmlRender {

    /** Инлайн-содержимое блока с `<b>`, `<i>`, `<code>`. */
    fun inline(block: Block): String = block.runs().joinToString("") { run ->
        var s = xmlEscape(run.text)
        if (run.code) s = "<code>$s</code>"
        if (run.italic) s = "<i>$s</i>"
        if (run.bold) s = "<b>$s</b>"
        s
    }

    /**
     * XHTML-разметка блоков. [imageSrc] превращает ссылку на картинку в адрес для атрибута `src`
     * (null — картинку пропустить). Пункты списка пишутся по одному, вложенность — числом `<ul>`.
     */
    fun blocks(blocks: List<Block>, imageSrc: (String) -> String?): String {
        val out = StringBuilder()
        for (block in blocks) {
            when (block.type) {
                BlockType.HEADING -> {
                    val n = block.level.coerceIn(1, 6)
                    out.append("<h").append(n).append('>').append(inline(block)).append("</h").append(n).append(">\n")
                }
                BlockType.PARAGRAPH -> out.append("<p>").append(inline(block)).append("</p>\n")
                BlockType.QUOTE -> out.append("<blockquote><p>").append(inline(block)).append("</p></blockquote>\n")
                BlockType.CODE -> out.append("<pre>").append(xmlEscape(block.text)).append("</pre>\n")
                BlockType.LIST_ITEM -> {
                    val depth = block.level.coerceIn(0, 8) + 1
                    out.append("<ul>".repeat(depth)).append("<li>").append(inline(block)).append("</li>")
                        .append("</ul>".repeat(depth)).append('\n')
                }
                BlockType.IMAGE -> {
                    val src = block.image?.let(imageSrc) ?: continue
                    out.append("<p><img src=\"").append(xmlEscape(src)).append("\" alt=\"").append(xmlEscape(block.text)).append("\"/></p>\n")
                }
            }
        }
        return out.toString()
    }
}
