package com.localdoc.ai

import android.app.ActivityManager
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.ai.rag.HashingEmbedder
import com.localdoc.ai.rag.RagRepository
import com.localdoc.ai.rag.VectorStore
import com.localdoc.ai.rag.totalDeviceMemoryMb
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Повторяемый device-замер без скачивания модели и без порогов, выдуманных по эмулятору.
 * Результат пишется в JSON; физическое устройство и условия указываются снаружи скриптом.
 */
@RunWith(AndroidJUnit4::class)
class SemanticSearchBenchmark {
    @Test
    fun measureHashingIndexAndQuery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val paragraphs = (1..1_000).joinToString("\n\n") { index ->
            "Раздел $index. " + List(60) { word -> "слово${(index + word) % 500}" }.joinToString(" ")
        }
        val storage = InMemoryRagStorage()
        val repository = RagRepository(VectorStore(storage), HashingEmbedder())
        val docId = "benchmark-${UUID.randomUUID()}"
        val stats = BenchmarkStats(context)

        val indexTimes = LongArray(3)
        var chunks = 0
        for (run in indexTimes.indices) {
            repository.removeDocument(docId)
            val started = SystemClock.elapsedRealtimeNanos()
            chunks = repository.indexDocument(docId, paragraphs)
            indexTimes[run] = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
            stats.sample()
        }
        val queryTimes = LongArray(10)
        for (run in queryTimes.indices) {
            val started = SystemClock.elapsedRealtimeNanos()
            val result = repository.retrieveTopK(docId, "слово${run + 1}", topK = 4)
            queryTimes[run] = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
            check(result.isNotEmpty())
            stats.sample()
        }
        repository.removeDocument(docId)

        val report = JSONObject()
            .put("schema", 1)
            .put("kind", "hashing-rag")
            .put("platform", "android")
            .put("api", android.os.Build.VERSION.SDK_INT)
            .put("model", android.os.Build.MODEL)
            .put("physicalDevice", !isProbablyEmulator())
            .put("totalMemoryMb", totalDeviceMemoryMb())
            .put("documentChars", paragraphs.length)
            .put("chunks", chunks)
            .put("indexRunsMs", indexTimes.toJsonArray())
            .put("queryRunsMs", queryTimes.toJsonArray())
            .put("indexMedianMs", indexTimes.sorted()[indexTimes.size / 2])
            .put("queryMedianMs", queryTimes.sorted()[queryTimes.size / 2])
            .put("memory", stats.json())
        val destination = File(context.getExternalFilesDir(null), "benchmarks")
            .apply { mkdirs() }
            .resolve("hashing-${System.currentTimeMillis()}.json")
        destination.writeText(report.toString(2))
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("localdoc.benchmark", destination.absolutePath) })
    }

    private class BenchmarkStats(private val context: android.content.Context) {
        var peakPssKb = 0
        var maxJavaHeapBytes = 0L
        fun sample() {
            val memory = Debug.MemoryInfo()
            Debug.getMemoryInfo(memory)
            peakPssKb = maxOf(peakPssKb, memory.totalPss)
            maxJavaHeapBytes = maxOf(maxJavaHeapBytes, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
            val activityManager = context.getSystemService(ActivityManager::class.java)
            activityManager?.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))?.forEach {
                peakPssKb = maxOf(peakPssKb, it.totalPss)
            }
        }
        fun json() = JSONObject().put("peakPssKb", peakPssKb).put("maxJavaHeapBytes", maxJavaHeapBytes)
    }

    private class InMemoryRagStorage : com.localdoc.ai.rag.RagStorage {
        private val values = HashMap<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }
}

private fun LongArray.toJsonArray() = JSONArray().also { forEach(it::put) }

private fun isProbablyEmulator(): Boolean = listOf(
    android.os.Build.FINGERPRINT,
    android.os.Build.MODEL,
    android.os.Build.PRODUCT,
    android.os.Build.HARDWARE
).any { value -> listOf("generic", "sdk", "emulator", "goldfish", "ranchu").any(value::contains) }
