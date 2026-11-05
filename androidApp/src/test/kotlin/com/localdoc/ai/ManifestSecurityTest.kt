package com.localdoc.ai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Аудит безопасности 2.0.0: что разрешено манифестом. Тест читает исходный AndroidManifest.xml. */
class ManifestSecurityTest {

    private val manifest: String by lazy {
        listOf("src/main/AndroidManifest.xml", "androidApp/src/main/AndroidManifest.xml")
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
    fun `only the launcher activity and model provider are exported and backups are off`() {
        assertTrue("""android:allowBackup="false"""" in manifest)
        val exported = Regex("""android:exported="true"""").findAll(manifest).count()
        assertTrue(exported == 2, "экспортированных компонентов: $exported")
    }

    @Test
    fun `model provider is exported under the models authority and grants uri permission`() {
        val providers = Regex("""<provider\b.*?</provider>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest).map { it.value }.toList()
        val modelProvider = providers.singleOrNull { "ModelProvider" in it }
        assertTrue(modelProvider != null, "ModelProvider не найден в манифесте")
        assertTrue("""android:name=".share.ModelProvider"""" in (modelProvider ?: ""))
        assertTrue("""android:authorities="com.localdoc.ai.models"""" in (modelProvider ?: ""))
        assertTrue("""android:exported="true"""" in (modelProvider ?: ""))
        assertTrue("""android:grantUriPermissions="true"""" in (modelProvider ?: ""))
    }

    @Test
    fun `cleartext traffic is not enabled globally`() {
        assertFalse("usesCleartextTraffic=\"true\"" in manifest)
    }
}
