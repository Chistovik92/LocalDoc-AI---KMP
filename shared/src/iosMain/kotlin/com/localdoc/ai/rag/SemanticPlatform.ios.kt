@file:OptIn(ExperimentalForeignApi::class)

package com.localdoc.ai.rag

import com.localdoc.ai.onnxruntime.OrtApi
import com.localdoc.ai.onnxruntime.OrtEnv
import com.localdoc.ai.onnxruntime.OrtGetApiBase
import com.localdoc.ai.onnxruntime.OrtMemoryInfo
import com.localdoc.ai.onnxruntime.OrtSession
import com.localdoc.ai.onnxruntime.OrtSessionOptions
import com.localdoc.ai.onnxruntime.OrtStatus
import com.localdoc.ai.onnxruntime.OrtTensorTypeAndShapeInfo
import com.localdoc.ai.onnxruntime.ORT_API_VERSION
import com.localdoc.ai.onnxruntime.ORT_ENABLE_ALL
import com.localdoc.ai.onnxruntime.ORT_LOGGING_LEVEL_WARNING
import com.localdoc.ai.onnxruntime.ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64
import com.localdoc.ai.onnxruntime.OrtArenaAllocator
import com.localdoc.ai.onnxruntime.OrtMemTypeDefaultimport com.localdoc.ai.onnxruntime.OrtValue
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSProcessInfo
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread

actual fun totalDeviceMemoryMb(): Long = (NSProcessInfo.processInfo.physicalMemory / (1024u * 1024u)).toLong()

actual val onnxRuntimeAvailable: Boolean = true

actual fun createOnnxTextEncoder(modelPath: String): OnnxTextEncoder = IosOrtTextEncoder(modelPath)

actual fun readFileChunks(path: String, chunkSize: Int, onChunk: (buffer: ByteArray, length: Int) -> Unit): Boolean {
    val file = fopen(path, "rb") ?: return false
    try {
        val buffer = ByteArray(chunkSize)
        while (true) {
            val read = buffer.usePinned { fread(it.addressOf(0), 1.convert(), chunkSize.convert(), file) }.toInt()
            if (read <= 0) break
            onChunk(buffer, read)
        }
    } finally {
        fclose(file)
    }
    return true
}

/** Официальный ONNX Runtime C API: сессия multilingual-e5-small, батч из одного текста. */
private class IosOrtTextEncoder(modelPath: String) : OnnxTextEncoder {
    private val api: CPointer<OrtApi> = OrtGetApiBase()?.let { base ->
        base.pointed.GetApi?.invoke(base, ORT_API_VERSION.convert())
    } ?: throw IllegalStateException("ONNX Runtime API $ORT_API_VERSION недоступен")
    private val env: CPointer<OrtEnv>
    private val memory: CPointer<OrtMemoryInfo>
    private val session: CPointer<OrtSession>
    private val allocator: CPointer<com.localdoc.ai.onnxruntime.OrtAllocator>
    private val wantsTokenTypes: Boolean

    init {
        memScoped {
            val envOut = alloc<CPointerVar<OrtEnv>>()
            checkStatus(api.pointed.CreateEnv!!.invoke(ORT_LOGGING_LEVEL_WARNING, "localdoc-e5".cstr.getPointer(this), envOut.ptr))
            env = requireNotNull(envOut.value)
            val memoryOut = alloc<CPointerVar<OrtMemoryInfo>>()
            try {
                checkStatus(api.pointed.CreateCpuMemoryInfo!!.invoke(OrtArenaAllocator, OrtMemTypeDefault, memoryOut.ptr))
                memory = requireNotNull(memoryOut.value)
                val optionsOut = alloc<CPointerVar<OrtSessionOptions>>()
                checkStatus(api.pointed.CreateSessionOptions!!(optionsOut.ptr))
                val options = requireNotNull(optionsOut.value)
                try {
                    checkStatus(api.pointed.SetIntraOpNumThreads!!(options, 2))
                    checkStatus(api.pointed.SetSessionGraphOptimizationLevel!!(options, ORT_ENABLE_ALL))
                    val sessionOut = alloc<CPointerVar<OrtSession>>()
                    try {
                        checkStatus(api.pointed.CreateSession!!(env, modelPath.cstr.getPointer(this), options, sessionOut.ptr))
                        session = requireNotNull(sessionOut.value)
                    } catch (t: Throwable) {
                        api.pointed.ReleaseMemoryInfo!!(memory)
                        api.pointed.ReleaseEnv!!(env)
                        throw t
                    }
                } finally {
                    api.pointed.ReleaseSessionOptions!!(options)
                }
            } catch (t: Throwable) {
                api.pointed.ReleaseEnv!!(env)
                throw t
            }
            val allocatorOut = alloc<CPointerVar<com.localdoc.ai.onnxruntime.OrtAllocator>>()
            checkStatus(api.pointed.GetAllocatorWithDefaultOptions!!(allocatorOut.ptr))
            allocator = requireNotNull(allocatorOut.value)
            val count = alloc<kotlinx.cinterop.ULongVar>()
            checkStatus(api.pointed.SessionGetInputCount!!(session, count.ptr))
            var found = false
            for (i in 0u until count.value) {
                val name = alloc<CPointerVar<kotlinx.cinterop.ByteVar>>()
                checkStatus(api.pointed.SessionGetInputName!!(session, i.convert(), allocator, name.ptr))
                found = found || name.value?.toKString() == "token_type_ids"
                api.pointed.AllocatorFree!!(allocator, name.value)
            }
            wantsTokenTypes = found
        }
    }

    override fun run(inputIds: LongArray, attentionMask: LongArray): FloatArray = memScoped {
        val shape = allocArray<LongVar>(2)
        shape[0] = 1
        shape[1] = inputIds.size.toLong()
        val inputs = allocArray<CPointerVar<OrtValue>>(if (wantsTokenTypes) 3 else 2)
        val names = if (wantsTokenTypes) arrayOf("input_ids", "attention_mask", "token_type_ids") else arrayOf("input_ids", "attention_mask")
        var made = 0
        var output: CPointer<OrtValue>? = null
        try {
            fun add(index: Int, values: LongArray) {
                val out = alloc<CPointerVar<OrtValue>>()
                values.usePinned { pinned ->
                    checkStatus(api.pointed.CreateTensorWithDataAsOrtValue!!(
                        memory, pinned.addressOf(0), (values.size * Long.SIZE_BYTES).convert(), shape, 2.convert(),
                        ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, out.ptr
                    ))
                }
                inputs[index] = requireNotNull(out.value)
                made++
            }
            add(0, inputIds)
            add(1, attentionMask)
            if (wantsTokenTypes) add(2, LongArray(inputIds.size))
            val outputOut = alloc<CPointerVar<OrtValue>>()
            checkStatus(api.pointed.Run!!(session, null, names.map { it.cstr.getPointer(this) }.toCValues().getPointer(this), inputs, made.convert(), arrayOf("last_hidden_state").map { it.cstr.getPointer(this) }.toCValues().getPointer(this), 1.convert(), outputOut.ptr))
            output = requireNotNull(outputOut.value)
            val info = alloc<CPointerVar<OrtTensorTypeAndShapeInfo>>()
            checkStatus(api.pointed.GetTensorTypeAndShape!!(output, info.ptr))
            val count = alloc<kotlinx.cinterop.ULongVar>()
            try {
                checkStatus(api.pointed.GetTensorShapeElementCount!!(info.value, count.ptr))
            } finally {
                api.pointed.ReleaseTensorTypeAndShapeInfo!!(info.value)
            }
            val data = alloc<CPointerVar<COpaquePointer>>()
            checkStatus(api.pointed.GetTensorMutableData!!(output, data.ptr))
            val floats = requireNotNull(data.value)?.reinterpret<kotlinx.cinterop.FloatVar>()
            return FloatArray(count.value.toInt()) { floats!![it] }
        } finally {
            if (output != null) api.pointed.ReleaseValue!!(output)
            for (i in 0 until made) api.pointed.ReleaseValue!!(inputs[i])
        }
    }

    override fun close() {
        api.pointed.ReleaseSession!!(session)
        api.pointed.ReleaseMemoryInfo!!(memory)
        api.pointed.ReleaseEnv!!(env)
    }

    private fun checkStatus(status: CPointer<OrtStatus>?) {
        if (status == null) return
        val message = api.pointed.GetErrorMessage!!(status)?.toKString() ?: "ошибка ONNX Runtime"
        api.pointed.ReleaseStatus!!(status)
        throw IllegalStateException(message)
    }
}
