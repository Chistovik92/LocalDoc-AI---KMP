package com.localdoc.ai.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Тестовая fetch-функция не приостанавливается, поэтому Unconfined выполняет блок синхронно. */
private fun <T> runNow(block: suspend () -> T): T {
    var result: Result<T>? = null
    CoroutineScope(Dispatchers.Unconfined).launch { result = runCatching { block() } }
    return checkNotNull(result) { "Блок приостановился" }.getOrThrow()
}

class UpdateCheckerTest {

    private fun release(tag: String, body: String = "", assets: String = "[]") =
        """{"tag_name":"$tag","name":"LocalDoc AI $tag","body":${kotlinx.serialization.json.JsonPrimitive(body)},"html_url":"https://example/r/$tag","assets":$assets}"""

    private fun checker(json: String) = UpdateChecker(repo = "x/y", fetch = { _, _ -> json })

    @Test
    fun `release notes lose markdown markup`() {
        val plain = releaseNotesToPlain("## LocalDoc AI 1.6.5\n\nГлавное: **виджет** и `UI`\n\n### Новое\n- пункт один\n  * вложенный")

        assertEquals("LocalDoc AI 1.6.5\n\nГлавное: виджет и UI\n\nНовое\n• пункт один\n  • вложенный", plain)
    }

    @Test
    fun `newer release exposes apk url and size`() = runNow {
        val assets = """[{"name":"LocalDoc-AI-1.6.5.apk","browser_download_url":"https://example/a.apk","size":123456}]"""

        val update = checker(release("v1.6.5", "## Новое", assets)).check("1.6.0", allowApk = true)

        assertEquals("1.6.5", update?.version)
        assertEquals("https://example/a.apk", update?.downloadUrl)
        assertEquals(123456L, update?.sizeBytes)
        assertEquals("Новое", update?.notes)
    }

    @Test
    fun `apk is hidden when the platform cannot install it`() = runNow {
        val assets = """[{"name":"a.apk","browser_download_url":"https://example/a.apk","size":1}]"""

        val update = checker(release("v1.6.5", assets = assets)).check("1.6.0", allowApk = false)

        assertNull(update?.downloadUrl)
        assertEquals(0L, update?.sizeBytes)
    }

    @Test
    fun `same or older release means no update`() = runNow {
        assertNull(checker(release("v1.6.5")).check("1.6.5", allowApk = true))
        assertNull(checker(release("v1.6.0")).check("1.6.5", allowApk = true))
    }

    @Test
    fun `version comparison handles multi digit parts`() {
        assertTrue(UpdateChecker.compareVersions("1.10.0", "1.9.9") > 0)
        assertTrue(UpdateChecker.compareVersions("1.6.5", "1.6.0") > 0)
    }
}
