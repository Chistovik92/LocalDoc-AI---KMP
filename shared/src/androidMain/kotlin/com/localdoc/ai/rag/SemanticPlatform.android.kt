package com.localdoc.ai.rag

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import com.localdoc.ai.platform.AppContextHolder
import java.io.File
import java.nio.LongBuffer

private class SharedOrtEnvironment {
    val value: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var references = 0
    private var closed = false

    @Synchronized fun acquire(): OrtEnvironment {
        check(!closed) { "Общий OrtEnvironment уже закрыт" }
        references++
        return value
    }

    @Synchronized fun release() {
        check(references > 0 && !closed) { "OrtEnvironment закрыт повторно" }
        if (--references == 0) {
            closed = true
            value.close()
        }
    }
}

private val sharedOrtEnvironment = SharedOrtEnvironment()

actual fun totalDeviceMemoryMb(): Long {
    val manager = AppContextHolder.appContext.getSystemService(ActivityManager::class.java) ?: return 0L
    val info = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
    return info.totalMem / (1024 * 1024)
}

actual val onnxRuntimeAvailable: Boolean = true

actual fun createOnnxTextEncoder(modelPath: String): OnnxTextEncoder = OrtTextEncoder(modelPath)

actual fun readFileChunks(path: String, chunkSize: Int, onChunk: (buffer: ByteArray, length: Int) -> Unit): Boolean {
    require(chunkSize > 0) { "Размер буфера чтения должен быть положительным" }
    val file = File(path)
    if (!file.isFile) return false
    file.inputStream().use { input ->
        val buffer = ByteArray(chunkSize)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            onChunk(buffer, read)
        }
    }
    return true
}

/** ONNX Runtime для Android: одна сессия, батч из одного текста. */
private class OrtTextEncoder(modelPath: String) : OnnxTextEncoder {
    private val env = sharedOrtEnvironment.acquire()
    private val session: OrtSession = try {
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            env.createSession(modelPath, options)
        }
    } catch (t: Throwable) {
        sharedOrtEnvironment.release()
        throw t
    }
    private val wantsTokenTypes = "token_type_ids" in session.inputNames

    @Synchronized override fun run(inputIds: LongArray, attentionMask: LongArray): FloatArray {
        val shape = longArrayOf(1, inputIds.size.toLong())
        val tensors = mutableListOf<OnnxTensor>()
        try {
            fun tensor(values: LongArray) = OnnxTensor.createTensor(env, LongBuffer.wrap(values), shape).also { tensors += it }
            val inputs = mutableMapOf("input_ids" to tensor(inputIds), "attention_mask" to tensor(attentionMask))
            if (wantsTokenTypes) inputs["token_type_ids"] = tensor(LongArray(inputIds.size))
            session.run(inputs).use { result ->
                val output = result.get(0) as OnnxTensor
                val buffer = output.floatBuffer
                return FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        } finally {
            tensors.forEach { it.close() }
        }
    }

    @Synchronized override fun close() {
        session.close()
        sharedOrtEnvironment.release()
    }
}
