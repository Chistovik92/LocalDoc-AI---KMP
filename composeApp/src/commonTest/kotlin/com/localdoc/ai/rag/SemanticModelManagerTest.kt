package com.localdoc.ai.rag

import com.localdoc.ai.download.DownloadState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SemanticModelManagerTest {

    private class FakeFiles : SemanticFiles {
        val present = mutableSetOf<String>()
        override fun isDownloaded(artifact: SemanticArtifact) = artifact.id in present
        override fun pathFor(artifact: SemanticArtifact) = "/models/${artifact.id}"
        override fun download(artifact: SemanticArtifact): Flow<DownloadState> {
            present += artifact.id
            return flowOf(DownloadState.Progress(1, artifact.sizeBytes), DownloadState.Done(pathFor(artifact)))
        }
        override fun cancel(artifact: SemanticArtifact) = Unit
        override suspend fun delete(artifact: SemanticArtifact) {
            present -= artifact.id
        }
    }

    private class FakeRuntime : SemanticRuntime {
        val provider = FakeEmbedder("e5", 384)
        val unloaded = mutableListOf<EmbeddingProvider>()
        override fun load(modelPath: String, tokenizerPath: String): EmbeddingProvider = provider
        override fun unload(provider: EmbeddingProvider) {
            unloaded += provider
        }
    }

    private fun correctSha(path: String): String? =
        SemanticModelCatalog.all.first { path.endsWith(it.id) }.sha256

    private class Env(
        scope: TestScope,
        sha: (String) -> String?,
        memoryMb: Long,
        benchmark: (EmbeddingProvider) -> Long
    ) {
        val files = FakeFiles()
        val storage = MemoryRagStorage()
        val runtime = FakeRuntime()
        val manager = SemanticModelManager(
            files, storage, runtime, sha, { memoryMb }, runtimeAvailable = true,
            scope = scope, io = StandardTestDispatcher(scope.testScheduler),
            benchmark = benchmark
        )
    }

    private fun TestScope.env(
        sha: (String) -> String? = ::correctSha,
        memoryMb: Long = 8000,
        benchmarkMillis: Long = 100,
        benchmark: (EmbeddingProvider) -> Long = { benchmarkMillis }
    ) = Env(this, sha, memoryMb, benchmark)

    @Test
    fun downloadVerifiesLoadsAndSwitchesProvider() = runTest {
        val env = env()
        assertEquals("hash-384", env.manager.embedder().signature)

        env.manager.download()
        advanceUntilIdle()

        assertEquals(SemanticModelState.Ready, env.manager.state.value)
        assertSame(env.runtime.provider, env.manager.embedder())
        assertTrue(env.manager.isInstalled())
    }

    @Test
    fun corruptedFileIsDeletedAndNotUsed() = runTest {
        val env = env(sha = { "00" })
        env.manager.download()
        advanceUntilIdle()

        assertIs<SemanticModelState.Failed>(env.manager.state.value)
        assertTrue(env.files.present.isEmpty() || env.files.present.size < SemanticModelCatalog.all.size)
        assertFalse(env.manager.isInstalled())
        assertEquals("hash-384", env.manager.embedder().signature)
    }

    @Test
    fun weakDeviceRejectsModelByMemory() = runTest {
        val env = env(memoryMb = 1500)
        env.manager.download()
        advanceUntilIdle()

        assertIs<SemanticModelState.Rejected>(env.manager.state.value)
        assertEquals("hash-384", env.manager.embedder().signature)
    }

    @Test
    fun slowDeviceRejectsModelBySpeedAndUnloadsIt() = runTest {
        val env = env(benchmarkMillis = SemanticModelPolicy.MAX_PASSAGE_MILLIS + 1)
        env.manager.download()
        advanceUntilIdle()

        assertIs<SemanticModelState.Rejected>(env.manager.state.value)
        assertEquals(listOf<EmbeddingProvider>(env.runtime.provider), env.runtime.unloaded)
        assertEquals("hash-384", env.manager.embedder().signature)
    }

    @Test
    fun deleteReturnsToHashingAndForgetsFiles() = runTest {
        val env = env()
        env.manager.download()
        advanceUntilIdle()

        env.manager.delete()
        advanceUntilIdle()

        assertEquals(SemanticModelState.NotInstalled, env.manager.state.value)
        assertEquals("hash-384", env.manager.embedder().signature)
        assertTrue(env.files.present.isEmpty())
        assertFalse(env.manager.isInstalled())
    }

    @Test
    fun disablingKeepsFilesButUsesHashing() = runTest {
        val env = env()
        env.manager.download()
        advanceUntilIdle()

        env.manager.setEnabled(false)
        advanceUntilIdle()
        assertEquals(SemanticModelState.Disabled, env.manager.state.value)
        assertEquals("hash-384", env.manager.embedder().signature)
        assertTrue(env.manager.isInstalled())

        env.manager.setEnabled(true)
        advanceUntilIdle()
        assertSame(env.runtime.provider, env.manager.embedder())
    }

    @Test
    fun runtimeFailureFallsBackToHashing() = runTest {
        val env = env()
        env.manager.download()
        advanceUntilIdle()

        env.manager.onEmbedderFailure(IllegalStateException("boom"))

        assertIs<SemanticModelState.Failed>(env.manager.state.value)
        assertEquals("hash-384", env.manager.embedder().signature)
    }

    @Test
    fun benchmarkFailureUnloadsLoadedProvider() = runTest {
        val env = env(benchmark = { error("inference failed") })
        env.manager.download()
        advanceUntilIdle()

        assertIs<SemanticModelState.Failed>(env.manager.state.value)
        assertEquals(listOf<EmbeddingProvider>(env.runtime.provider), env.runtime.unloaded)
    }
}
