package com.localdoc.ai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Аудит безопасности 2.0.0: что разрешено манифестом. Тест читает исходный AndroidManifest.xml. */
class ManifestSecurityTest {

    private val manifest: String by lazy {
        listOf("src/androidMain/AndroidManifest.xml", "composeApp/src/androidMain/AndroidManifest.xml")
            .map(::File).first { it.isFile }.readText()
    }

    private val filters: List<String> by lazy {
        Regex("<intent-filter>.*?</intent-filter>", RegexOption.DOT_MATCHES_ALL).findAll(manifest).map { it.value }.toList()
    }

    @Test
    fun `web pages cannot launch the app on local files`() {
        val fileFilters = filters.filter { """android:scheme="file"""" in it || """android:scheme="content"""" in it }
        assertTrue(fileFilters.isNotEmpty())
        for (filter in fileFilters) assertFalse("category.BROWSABLE" in filter, filter)
    }

    @Test
    fun `only the launcher activity is exported and backups are off`() {
        assertTrue("""android:allowBackup="false"""" in manifest)
        val exported = Regex("""android:exported="true"""").findAll(manifest).count()
        assertTrue(exported == 1, "экспортированных компонентов: $exported")
    }

    @Test
    fun `cleartext traffic is not enabled globally`() {
        assertFalse("usesCleartextTraffic=\"true\"" in manifest)
    }
}
