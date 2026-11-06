@file:OptIn(ExperimentalForeignApi::class)

package com.localdoc.ai.rag

import cnames.structs.OrtEnv
import cnames.structs.OrtMemoryInfo
import cnames.structs.OrtSession
import cnames.structs.OrtSessionOptions
import cnames.structs.OrtStatus
import cnames.structs.OrtTensorTypeAndShapeInfo
import cnames.structs.OrtValue
import com.localdoc.ai.onnxruntime.ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64
import com.localdoc.ai.onnxruntime.ORT_ENABLE_ALL
import com.localdoc.ai.onnxruntime.ORT_LOGGING_LEVEL_WARNING
import com.localdoc.ai.onnxruntime.OrtArenaAllocator
import com.localdoc.ai.onnxruntime.OrtAllocator
import com.localdoc.ai.onnxruntime.OrtApi
import com.localdoc.ai.onnxruntime.OrtGetApiBase
import com.localdoc.ai.onnxruntime.OrtMemTypeDefault
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
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
    private val api: CPointer<OrtApi>
    private val env: CPointer<OrtEnv>
    private val memory: CPointer<OrtMemoryInfo>
    private val session: CPointer<OrtSession>
    private val allocator: CPointer<OrtAllocator>
    private val wantsTokenTypes: Boolean

    init {
        val base = OrtGetApiBase() ?: throw IllegalStateException("ONNX Runtime недоступен")
        api = base.pointed.GetApi?.invoke(24u)
            ?: throw IllegalStateException("ONNX Runtime API не поддерживается")

        val handles = createHandles(modelPath)
        env = handles.env
        memory = handles.memory
        session = handles.session
        allocator = handles.allocator
        wantsTokenTypes = handles.wantsTokenTypes
    }

    private fun createHandles(modelPath: String): OrtHandles = memScoped {
        val envOut = alloc<CPointerVar<OrtEnv>>()
        checkStatus(api.pointed.CreateEnv?.invoke(ORT_LOGGING_LEVEL_WARNING, "localdoc-e5", envOut.ptr))
        val env = envOut.value ?: throw IllegalStateException("не создан OrtEnv")

        val memoryOut = alloc<CPointerVar<OrtMemoryInfo>>()
        checkStatus(api.pointed.CreateCpuMemoryInfo?.invoke(OrtArenaAllocator, OrtMemTypeDefault, memoryOut.ptr))
        val memory = memoryOut.value ?: throw IllegalStateException("не создан OrtMemoryInfo")

        val optionsOut = alloc<CPointerVar<OrtSessionOptions>>()
        checkStatus(api.pointed.CreateSessionOptions?.invoke(optionsOut.ptr))
        val options = optionsOut.value ?: throw IllegalStateException("не создан OrtSessionOptions")
        val session: CPointer<OrtSession>
        try {
            checkStatus(api.pointed.SetIntraOpNumThreads?.invoke(options, 2))
            checkStatus(api.pointed.SetSessionGraphOptimizationLevel?.invoke(options, ORT_ENABLE_ALL))
            val sessionOut = alloc<CPointerVar<OrtSession>>()
            checkStatus(api.pointed.CreateSession?.invoke(env, modelPath, options, sessionOut.ptr))
            session = sessionOut.value ?: throw IllegalStateException("не создан OrtSession")
        } finally {
            api.pointed.ReleaseSessionOptions?.invoke(options)
        }

        val allocatorOut = alloc<CPointerVar<OrtAllocator>>()
        checkStatus(api.pointed.GetAllocatorWithDefaultOptions?.invoke(allocatorOut.ptr))
        val allocator = allocatorOut.value ?: throw IllegalStateException("не создан OrtAllocator")

        val count = alloc<ULongVar>()
        checkStatus(api.pointed.SessionGetInputCount?.invoke(session, count.ptr))
        var wantsTokenTypes = false
        var i = 0uL
        while (i < count.value) {
            val name = alloc<CPointerVar<ByteVar>>()
            checkStatus(api.pointed.SessionGetInputName?.invoke(session, i, allocator, name.ptr))
            wantsTokenTypes = wantsTokenTypes || name.value?.toKString() == "token_type_ids"
            api.pointed.AllocatorFree?.invoke(allocator, name.value)
            i++
        }

        OrtHandles(env, memory, session, allocator, wantsTokenTypes)
    }

    override fun run(inputIds: LongArray, attentionMask: LongArray): FloatArray = memScoped {
        val inputCount = if (wantsTokenTypes) 3 else 2
        val idArrays = if (wantsTokenTypes) arrayOf(inputIds, attentionMask, LongArray(inputIds.size))
        else arrayOf(inputIds, attentionMask)
        val nameStrings = if (wantsTokenTypes) arrayOf("input_ids", "attention_mask", "token_type_ids")
        else arrayOf("input_ids", "attention_mask")

        val shape = allocArray<LongVar>(2)
        shape[0] = 1
        shape[1] = inputIds.size.toLong()

        val inputs = allocArray<CPointerVar<OrtValue>>(inputCount)
        val names = allocArray<CPointerVar<ByteVar>>(inputCount)
        val created = ArrayList<CPointer<OrtValue>>(inputCount + 1)
        try {
            for (i in 0 until inputCount) {
                names[i] = nameStrings[i].cstr.ptr
                val values = idArrays[i]
                val out = alloc<CPointerVar<OrtValue>>()
                values.usePinned { pinned ->
                    checkStatus(api.pointed.CreateTensorWithDataAsOrtValue?.invoke(
                        memory,
                        pinned.addressOf(0),
                        (values.size * Long.SIZE_BYTES).toULong(),
                        shape,
                        2uL,
                        ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64,
                        out.ptr
                    ))
                }
                val tensor = out.value ?: throw IllegalStateException("не создан входной тензор")
                inputs[i] = tensor
                created += tensor
            }

            val outputNames = allocArray<CPointerVar<ByteVar>>(1)
            outputNames[0] = "last_hidden_state".cstr.ptr
            val outputOut = alloc<CPointerVar<OrtValue>>()
            checkStatus(api.pointed.Run?.invoke(
                session, null, names, inputs, inputCount.toULong(), outputNames, 1uL, outputOut.ptr
            ))
            val output = outputOut.value ?: throw IllegalStateException("нет выхода модели")
            created += output

            val info = alloc<CPointerVar<OrtTensorTypeAndShapeInfo>>()
            checkStatus(api.pointed.GetTensorTypeAndShape?.invoke(output, info.ptr))
            val count = alloc<ULongVar>()
            try {
                checkStatus(api.pointed.GetTensorShapeElementCount?.invoke(info.value, count.ptr))
            } finally {
                api.pointed.ReleaseTensorTypeAndShapeInfo?.invoke(info.value)
            }

            val data = alloc<COpaquePointerVar>()
            checkStatus(api.pointed.GetTensorMutableData?.invoke(output, data.ptr))
            val floats = data.value?.reinterpret<FloatVar>()
                ?: throw IllegalStateException("нет данных выходного тензора")
            return FloatArray(count.value.toInt()) { floats[it] }
        } finally {
            for (tensor in created) {
                api.pointed.ReleaseValue?.invoke(tensor)
            }
        }
    }

    override fun close() {
        api.pointed.ReleaseSession?.invoke(session)
        api.pointed.ReleaseMemoryInfo?.invoke(memory)
        api.pointed.ReleaseEnv?.invoke(env)
    }

    private fun checkStatus(status: CPointer<OrtStatus>?) {
        if (status == null) return
        val message = api.pointed.GetErrorMessage?.invoke(status)?.toKString() ?: "ошибка ONNX Runtime"
        api.pointed.ReleaseStatus?.invoke(status)
        throw IllegalStateException(message)
    }

    private class OrtHandles(
        val env: CPointer<OrtEnv>,
        val memory: CPointer<OrtMemoryInfo>,
        val session: CPointer<OrtSession>,
        val allocator: CPointer<OrtAllocator>,
        val wantsTokenTypes: Boolean
    )
}
