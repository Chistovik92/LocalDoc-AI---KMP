package com.localdoc.ai.parser

/**
 * Платформенный извлекатель PDF с фоллбэком на локальный OCR:
 * - Android: PDFBox-Android + Google ML Kit (офлайн, Hexagon NPU);
 * - iOS: системный PDFKit + Apple Vision (Neural Engine).
 */
expect class PdfExtractor() {
    /**
     * pathOrUri: на Android — Uri (content://) или путь к файлу, на iOS — путь к файлу.
     * [withOcr] = false — только текстовый слой (быстро, для импорта); true — распознавание
     * сканов через локальный OCR (медленно, по требованию из читалки).
     */
    suspend fun extract(pathOrUri: String, withOcr: Boolean = false): ParsedDoc
}
