package com.localdoc.ai.ui.reader

import androidx.compose.ui.unit.Density
import com.localdoc.ai.domain.ReaderSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Геометрия страницы: ширина колонки совпадает с реальной у Compose, высота всегда несёт запас. */
class PageGeometryTest {

    private val density = Density(2f, 2f)

    @Test
    fun `page height always keeps the safety reserve`() {
        val area = 1600f
        val content = pageContentHeightPx(area, density)
        assertTrue(content < area, "запас должен уменьшать высоту области")
        // Запас — константные dp, а не доля высоты: и на большом, и на маленьком экране он одинаков
        assertEquals(1592f, pageContentHeightPx(area, density), 0.01f)
        assertEquals(1596f, pageContentHeightPx(1600f, Density(1f, 1f)), 0.01f)
        assertEquals(0f, pageContentHeightPx(0f, density), 0.01f)
    }

    @Test
    fun `content width is computed the way compose renders it`() {
        // margin отдельной книги даёт 8dp: по 16px с каждой стороны при density 2
        val settings = ReaderSettings(margin = 8f)
        assertEquals(968f, pageContentWidthPx(1000f, settings, density), 0.01f)
        // Колонка ограничена 720dp целиком, а не только текст в ней
        assertEquals(1408f, pageContentWidthPx(8000f, settings, density), 0.01f)
        // Большие поля и узкая область: ширина не падает ниже единицы, чтобы измерение не сломалось
        val clamped = ReaderSettings(margin = 100f)
        assertEquals(1f, pageContentWidthPx(100f, clamped, density), 0.01f)
    }
}
