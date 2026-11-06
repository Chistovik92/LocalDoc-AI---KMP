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
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
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
        api = base.pointed.GetApi!!(24u)
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
        checkStatus(api.pointed.CreateEnv!!(ORT_LOGGING_LEVEL_WARNING, "localdoc-e5".cstr.ptr, envOut.ptr))
        val env = envOut.value ?: throw IllegalStateException("не создан OrtEnv")

        val memoryOut = alloc<CPointerVar<OrtMemoryInfo>>()
        checkStatus(api.pointed.CreateCpuMemoryInfo!!(OrtArenaAllocator, OrtMemTypeDefault, memoryOut.ptr))
        val memory = memoryOut.value ?: throw IllegalStateException("не создан OrtMemoryInfo")

        val optionsOut = alloc<CPointerVar<OrtSessionOptions>>()
        checkStatus(api.pointed.CreateSessionOptions!!(optionsOut.ptr))
        val options = optionsOut.value ?: throw IllegalStateException("не создан OrtSessionOptions")
        val session: CPointer<OrtSession>
        try {
            checkStatus(api.pointed.SetIntraOpNumThreads!!(options, 2))
            checkStatus(api.pointed.SetSessionGraphOptimizationLevel!!(options, ORT_ENABLE_ALL))
            val sessionOut = alloc<CPointerVar<OrtSession>>()
            checkStatus(api.pointed.CreateSession!!(env, modelPath.cstr.ptr, options, sessionOut.ptr))
            session = sessionOut.value ?: throw IllegalStateException("не создан OrtSession")
        } finally {
            api.pointed.ReleaseSessionOptions!!(options)
        }

        val allocatorOut = alloc<CPointerVar<OrtAllocator>>()
        checkStatus(api.pointed.GetAllocatorWithDefaultOptions!!(allocatorOut.ptr))
        val allocator = allocatorOut.value ?: throw IllegalStateException("не создан OrtAllocator")

        val count = alloc<ULongVar>()
        checkStatus(api.pointed.SessionGetInputCount!!(session, count.ptr))
        var wantsTokenTypes = false
        var i = 0uL
        while (i < count.value) {
            val name = alloc<CPointerVar<ByteVar>>()
            checkStatus(api.pointed.SessionGetInputName!!(session, i, allocator, name.ptr))
            wantsTokenTypes = wantsTokenTypes || name.value?.toKString() == "token_type_ids"
            checkStatus(api.pointed.AllocatorFree!!(allocator, name.value))
            i++
        }

        OrtHandles(env, memory, session, allocator, wantsTokenTypes)
    }

    override fun run(inputIds: LongArray, attentionMask: LongArray): FloatArray {
        val tokenTypes = if (wantsTokenTypes) LongArray(inputIds.size) else LongArray(0)
        // Пиннуем входные массивы на весь Run — CreateTensorWithDataAsOrtValue не копирует данные.
        return inputIds.usePinned { ids ->
            attentionMask.usePinned { mask ->
                tokenTypes.usePinned { tokens ->
                    encode(
                        ids.addressOf(0),
                        mask.addressOf(0),
                        if (wantsTokenTypes) tokens.addressOf(0) else null,
                        inputIds.size
                    )
                }
            }
        }
    }

    private fun encode(
        ids: CPointer<LongVar>,
        mask: CPointer<LongVar>,
        tokenTypes: CPointer<LongVar>?,
        seqLen: Int
    ): FloatArray = memScoped {
        val inputCount = if (tokenTypes != null) 3 else 2
        val nameStrings = if (tokenTypes != null) {
            arrayOf("input_ids", "attention_mask", "token_type_ids")
        } else {
            arrayOf("input_ids", "attention_mask")
        }
        val dataPtrs: List<CPointer<LongVar>?> = if (tokenTypes != null) {
            listOf(ids, mask, tokenTypes)
        } else {
            listOf(ids, mask)
        }

        val shape = allocArray<LongVar>(2)
        shape[0].value = 1L
        shape[1].value = seqLen.toLong()

        val inputs = allocArray<CPointerVar<OrtValue>>(inputCount)
        val names = allocArray<CPointerVar<ByteVar>>(inputCount)
        val created = ArrayList<CPointer<OrtValue>>(inputCount + 1)
        try {
            for (i in 0 until inputCount) {
                names[i].value = nameStrings[i].cstr.ptr
                val out = alloc<CPointerVar<OrtValue>>()
                checkStatus(api.pointed.CreateTensorWithDataAsOrtValue!!(
                    memory,
                    dataPtrs[i],
                    (seqLen * Long.SIZE_BYTES).toULong(),
                    shape,
                    2uL,
                    ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64,
                    out.ptr
                ))
                val tensor = out.value ?: throw IllegalStateException("не создан входной тензор")
                inputs[i].value = tensor
                created += tensor
            }

            val outputNames = allocArray<CPointerVar<ByteVar>>(1)
            outputNames[0].value = "last_hidden_state".cstr.ptr
            val outputOut = alloc<CPointerVar<OrtValue>>()
            checkStatus(api.pointed.Run!!(
                session, null, names, inputs, inputCount.toULong(), outputNames, 1uL, outputOut.ptr
            ))
            val output = outputOut.value ?: throw IllegalStateException("нет выхода модели")
            created += output

            val info = alloc<CPointerVar<OrtTensorTypeAndShapeInfo>>()
            checkStatus(api.pointed.GetTensorTypeAndShape!!(output, info.ptr))
            val count = alloc<ULongVar>()
            try {
                checkStatus(api.pointed.GetTensorShapeElementCount!!(info.value, count.ptr))
            } finally {
                api.pointed.ReleaseTensorTypeAndShapeInfo!!(info.value)
            }

            val data = alloc<COpaquePointerVar>()
            checkStatus(api.pointed.GetTensorMutableData!!(output, data.ptr))
            val floats = data.value?.reinterpret<FloatVar>()
                ?: throw IllegalStateException("нет данных выходного тензора")
            return FloatArray(count.value.toInt()) { floats[it].value }
        } finally {
            for (tensor in created) {
                api.pointed.ReleaseValue!!(tensor)
            }
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

    private class OrtHandles(
        val env: CPointer<OrtEnv>,
        val memory: CPointer<OrtMemoryInfo>,
        val session: CPointer<OrtSession>,
        val allocator: CPointer<OrtAllocator>,
        val wantsTokenTypes: Boolean
    )
}
