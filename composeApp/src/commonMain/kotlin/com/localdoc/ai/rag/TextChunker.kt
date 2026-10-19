package com.localdoc.ai.rag

/** Разбиение длинного текста на перекрывающиеся чанки (sliding window по словам). */
object TextChunker {

    fun chunk(
        text: String,
        chunkSizeWords: Int = 280,
        overlapWords: Int = 40
    ): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        if (words.size <= chunkSizeWords) return listOf(words.joinToString(" "))

        val result = mutableListOf<String>()
        var start = 0
        while (start < words.size) {
            val end = minOf(start + chunkSizeWords, words.size)
            result += words.subList(start, end).joinToString(" ")
            if (end == words.size) break
            start += chunkSizeWords - overlapWords
        }
        return result
    }
}
