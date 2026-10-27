package com.localdoc.ai.rag

/**
 * Токенизатор SentencePiece Unigram (как у XLM-R / multilingual-e5): словарь читается из
 * `tokenizer.json` модели, разбиение — алгоритм Витерби по логарифмам вероятностей.
 *
 * Упрощения относительно эталона: нет Unicode-нормализации NFKC (precompiled charsmap) —
 * только схлопывание пробелов; для обычного русского и английского текста результат совпадает.
 */
class UnigramTokenizer private constructor(
    private val pieceIds: HashMap<String, Int>,
    private val scores: FloatArray,
    private val maxPieceLength: Int,
    private val clsId: Int,
    private val sepId: Int,
    private val unkId: Int,
    val maxLength: Int
) {
    private val unkScore = (scores.minOrNull() ?: 0f) - UNKNOWN_PENALTY

    /** `[CLS] токены [SEP]`, не длиннее [maxLength]. */
    fun encode(text: String): LongArray {
        val ids = ArrayList<Int>()
        ids += clsId
        val room = maxLength - 2
        var count = 0
        for (word in text.split(WHITESPACE).filter { it.isNotEmpty() }) {
            if (count >= room) break
            for (id in segment(WORD_MARK + word)) {
                if (count >= room) break
                ids += id
                count++
            }
        }
        ids += sepId
        return LongArray(ids.size) { ids[it].toLong() }
    }

    private fun segment(word: String): List<Int> {
        val n = word.length
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val from = IntArray(n + 1)
        val piece = IntArray(n + 1)
        best[0] = 0f
        for (end in 1..n) {
            if (word[end - 1].isHighSurrogate()) continue // не режем суррогатную пару
            val lowest = maxOf(0, end - maxPieceLength)
            var matched = false
            for (start in lowest until end) {
                if (best[start] == Float.NEGATIVE_INFINITY || word[start].isLowSurrogate()) continue
                val id = pieceIds[word.substring(start, end)] ?: continue
                matched = true
                val total = best[start] + scores[id]
                if (total > best[end]) {
                    best[end] = total; from[end] = start; piece[end] = id
                }
            }
            // Символ вне словаря — один <unk> на кодовую точку
            val charStart = if (end >= 2 && word[end - 1].isLowSurrogate() && word[end - 2].isHighSurrogate()) end - 2 else end - 1
            if (best[charStart] != Float.NEGATIVE_INFINITY && (!matched || best[end] == Float.NEGATIVE_INFINITY)) {
                val total = best[charStart] + unkScore
                if (total > best[end]) {
                    best[end] = total; from[end] = charStart; piece[end] = unkId
                }
            }
        }
        val out = ArrayList<Int>()
        var pos = n
        while (pos > 0) {
            out += piece[pos]
            pos = from[pos]
        }
        out.reverse()
        return out
    }

    companion object {
        private const val WORD_MARK = "\u2581"
        private const val BACKSLASH = '\\'
        private val WHITESPACE = Regex("\\s+")
        private val VOCAB_KEY = Regex("\"vocab\"\\s*:\\s*\\[")
        private const val UNKNOWN_PENALTY = 10f

        /** Разбирает `tokenizer.json` (HF tokenizers, модель Unigram). */
        fun fromTokenizerJson(json: String, maxLength: Int = 512): UnigramTokenizer {
            val (pieces, scoreList) = parseVocab(json)
            val ids = HashMap<String, Int>(pieces.size * 2)
            var longest = 1
            pieces.forEachIndexed { index, p ->
                ids[p] = index
                if (p.length > longest) longest = p.length
            }
            fun special(name: String) = requireNotNull(ids[name]) { "в словаре нет токена $name" }
            return UnigramTokenizer(
                pieceIds = ids,
                scores = scoreList,
                maxPieceLength = longest,
                clsId = special("<s>"),
                sepId = special("</s>"),
                unkId = special("<unk>"),
                maxLength = maxLength
            )
        }

        /** Читает массив `"vocab": [["piece", score], …]` без построения полного JSON-дерева (файл ~17 МБ). */
        private fun parseVocab(json: String): Pair<List<String>, FloatArray> {
            val key = VOCAB_KEY.find(json)
            requireNotNull(key) { "в tokenizer.json нет словаря vocab" }
            var i = key.range.last + 1
            val pieces = ArrayList<String>()
            val scores = ArrayList<Float>()
            while (i < json.length) {
                when (json[i]) {
                    ']' -> break
                    '[' -> {
                        i++
                        while (json[i] != '"') i++
                        val (piece, afterString) = readString(json, i)
                        i = afterString
                        while (json[i] != ',') i++
                        i++
                        val numberStart = i
                        while (json[i] != ']') i++
                        pieces += piece
                        scores += json.substring(numberStart, i).trim().toFloat()
                        i++
                    }
                    else -> i++
                }
            }
            require(pieces.isNotEmpty()) { "словарь tokenizer.json пуст" }
            return pieces to scores.toFloatArray()
        }

        /** Читает JSON-строку, начинающуюся с кавычки в [start]; возвращает значение и позицию после неё. */
        private fun readString(s: String, start: Int): Pair<String, Int> {
            val sb = StringBuilder()
            var i = start + 1
            while (s[i] != '"') {
                if (s[i] == BACKSLASH) {
                    i++
                    when (val c = s[i]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(c) // \" \ \/
                    }
                } else {
                    sb.append(s[i])
                }
                i++
            }
            return sb.toString() to i + 1
        }
    }
}
