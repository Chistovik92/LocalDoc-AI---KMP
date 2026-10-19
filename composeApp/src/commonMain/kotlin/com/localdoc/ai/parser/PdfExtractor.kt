package com.localdoc.ai.parser

/**
 * Платформенный извлекатель PDF с фоллбэком на локальный OCR:
 * - Android: PDFBox-Android + Google ML Kit (офлайн, Hexagon NPU);
 * - iOS: системный PDFKit + Apple Vision (Neural Engine).
 */
expect class PdfExtractor() {
    /** pathOrUri: на Android — Uri (content://), на iOS — путь к файлу. */
    suspend fun extract(pathOrUri: String): ParsedDoc
}
