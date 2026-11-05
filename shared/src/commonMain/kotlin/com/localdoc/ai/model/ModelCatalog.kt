package com.localdoc.ai.model

import com.localdoc.ai.download.Downloadable
import com.localdoc.ai.resources.Res
import com.localdoc.ai.resources.model_deepseek_r1_1_5b_desc
import com.localdoc.ai.resources.model_gemma_2_2b_desc
import com.localdoc.ai.resources.model_gemma_3_1b_desc
import com.localdoc.ai.resources.model_gemma_3_4b_desc
import com.localdoc.ai.resources.model_llama3_2_1b_desc
import com.localdoc.ai.resources.model_llama3_2_3b_desc
import com.localdoc.ai.resources.model_phi3_5_mini_desc
import com.localdoc.ai.resources.model_qwen2_5_0_5b_desc
import com.localdoc.ai.resources.model_qwen2_5_1_5b_desc
import com.localdoc.ai.resources.model_qwen2_5_3b_desc
import com.localdoc.ai.resources.model_qwen2_5_7b_desc
import com.localdoc.ai.resources.model_qwen3_1_7b_desc
import com.localdoc.ai.resources.model_qwen3_4b_desc
import com.localdoc.ai.resources.model_qwen3_5_0_8b_desc
import com.localdoc.ai.resources.model_qwen3_5_2b_desc
import com.localdoc.ai.resources.model_qwen3_5_4b_desc
import com.localdoc.ai.resources.model_smollm2_1_7b_desc
import com.localdoc.ai.resources.model_tag_analysis
import com.localdoc.ai.resources.model_tag_balance
import com.localdoc.ai.resources.model_tag_battery_saver
import com.localdoc.ai.resources.model_tag_best_russian
import com.localdoc.ai.resources.model_tag_compact
import com.localdoc.ai.resources.model_tag_english
import com.localdoc.ai.resources.model_tag_fast
import com.localdoc.ai.resources.model_tag_google
import com.localdoc.ai.resources.model_tag_max_quality
import com.localdoc.ai.resources.model_tag_new
import com.localdoc.ai.resources.model_tag_open
import com.localdoc.ai.resources.model_tag_quality
import com.localdoc.ai.resources.model_tag_reasoning
import com.localdoc.ai.resources.model_tag_weak_devices
import com.localdoc.ai.resources.model_vikhr_1_5b_desc
import org.jetbrains.compose.resources.StringResource

/**
 * Шаблон диалога модели. BOS добавляет токенизатор C-моста (add_special = true),
 * поэтому здесь его нет.
 */
enum class ChatTemplate {
    CHATML, QWEN3, LLAMA3, GEMMA, PHI3, DEEPSEEK_R1;

    fun build(system: String, user: String): String = when (this) {
        CHATML -> chatMl(system, user)
        // Qwen 3 по умолчанию «думает» вслух; мягкое отключение — «/no_think» в system-сообщении.
        // К тексту пользователя его приклеивать нельзя: для перевода он попадал в переводимый текст
        // и ломал запрос (например, с китайского).
        QWEN3 -> chatMl("$system\n/no_think", user)
        LLAMA3 -> "<|start_header_id|>system<|end_header_id|>\n\n$system<|eot_id|>" +
            "<|start_header_id|>user<|end_header_id|>\n\n$user<|eot_id|>" +
            "<|start_header_id|>assistant<|end_header_id|>\n\n"
        // У Gemma нет роли system — инструкция идёт в начале сообщения пользователя
        GEMMA -> "<start_of_turn>user\n$system\n\n$user<end_of_turn>\n<start_of_turn>model\n"
        PHI3 -> "<|system|>\n$system<|end|>\n<|user|>\n$user<|end|>\n<|assistant|>\n"
        DEEPSEEK_R1 -> "$system<｜User｜>$user<｜Assistant｜>"
    }

    private fun chatMl(system: String, user: String) =
        "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
}

/** Семейство модели — цвет и подпись на карточке каталога. */
enum class ModelFamily(val label: String, val vendor: String) {
    QWEN("Qwen", "Alibaba"),
    GEMMA("Gemma", "Google"),
    LLAMA("Llama", "Meta"),
    PHI("Phi", "Microsoft"),
    SMOL("SmolLM", "Hugging Face"),
    DEEPSEEK("DeepSeek", "DeepSeek"),
    VIKHR("Vikhr", "Vikhr Models")
}

/** Описание GGUF-модели в каталоге. */
data class ModelSpec(
    override val id: String,
    val name: String,
    val descriptionRes: StringResource,
    override val url: String,
    val sizeMb: Long,
    val family: ModelFamily,
    val parameters: String,
    /** Минимум ОЗУ телефона для комфортной работы, ГБ. */
    val minRamGb: Int,
    /** Оценки 1..5 для карточки: качество ответов, скорость, русский язык. */
    val quality: Int,
    val speed: Int,
    val russian: Int,
    val license: String,
    val template: ChatTemplate,
    val tags: List<StringResource> = emptyList(),
    val recommended: Boolean = false,
    val defaultContext: Int = 8192,
    val defaultGpuLayers: Int = 99
) : Downloadable {
    override val sizeBytes: Long get() = sizeMb * 1024L * 1024L
}

/**
 * Каталог моделей: всё работает офлайн, модели скачиваются напрямую с Hugging Face
 * в 4-битном квантовании Q4_K_M — оптимально для смартфонов.
 * id существующих моделей не меняются: по ним хранятся скачанные файлы.
 */
object ModelCatalog {

    private const val HF = "https://huggingface.co"

    val models: List<ModelSpec> = listOf(
        ModelSpec(
            id = "qwen2.5-0.5b-instruct-q4km",
            name = "Qwen 2.5 0.5B Instruct",
            descriptionRes = Res.string.model_qwen2_5_0_5b_desc,
            url = "$HF/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeMb = 469, family = ModelFamily.QWEN, parameters = "0.5B", minRamGb = 3,
            quality = 2, speed = 5, russian = 3, license = "Apache 2.0", template = ChatTemplate.CHATML,
            tags = listOf(Res.string.model_tag_weak_devices)
        ),
        ModelSpec(
            id = "qwen3.5-0.8b-q4km",
            name = "Qwen 3.5 0.8B",
            descriptionRes = Res.string.model_qwen3_5_0_8b_desc,
            url = "$HF/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf",
            sizeMb = 508, family = ModelFamily.QWEN, parameters = "0.8B", minRamGb = 3,
            quality = 3, speed = 5, russian = 4, license = "Apache 2.0", template = ChatTemplate.QWEN3,
            tags = listOf(Res.string.model_tag_weak_devices, Res.string.model_tag_new)
        ),
        ModelSpec(
            id = "gemma-3-1b-it-q4km",
            name = "Gemma 3 1B",
            descriptionRes = Res.string.model_gemma_3_1b_desc,
            url = "$HF/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
            sizeMb = 769, family = ModelFamily.GEMMA, parameters = "1B", minRamGb = 4,
            quality = 3, speed = 5, russian = 3, license = "Gemma", template = ChatTemplate.GEMMA,
            tags = listOf(Res.string.model_tag_google, Res.string.model_tag_fast)
        ),
        ModelSpec(
            id = "llama3.2-1b-instruct-q4km",
            name = "Llama 3.2 1B Instruct",
            descriptionRes = Res.string.model_llama3_2_1b_desc,
            url = "$HF/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sizeMb = 770, family = ModelFamily.LLAMA, parameters = "1B", minRamGb = 4,
            quality = 2, speed = 5, russian = 2, license = "Llama 3.2", template = ChatTemplate.LLAMA3,
            tags = listOf(Res.string.model_tag_battery_saver)
        ),
        ModelSpec(
            id = "vikhr-qwen2.5-1.5b-instruct-q4km",
            name = "Vikhr Qwen 2.5 1.5B",
            descriptionRes = Res.string.model_vikhr_1_5b_desc,
            url = "$HF/Vikhrmodels/Vikhr-Qwen-2.5-1.5B-Instruct-GGUF/resolve/main/Vikhr-Qwen-2.5-1.5b-Instruct-Q4_K_M.gguf",
            sizeMb = 940, family = ModelFamily.VIKHR, parameters = "1.5B", minRamGb = 4,
            quality = 3, speed = 4, russian = 5, license = "Apache 2.0", template = ChatTemplate.CHATML,
            tags = listOf(Res.string.model_tag_best_russian, Res.string.model_tag_compact)
        ),
        ModelSpec(
            id = "smollm2-1.7b-instruct-q4km",
            name = "SmolLM2 1.7B Instruct",
            descriptionRes = Res.string.model_smollm2_1_7b_desc,
            url = "$HF/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
            sizeMb = 1007, family = ModelFamily.SMOL, parameters = "1.7B", minRamGb = 4,
            quality = 3, speed = 4, russian = 1, license = "Apache 2.0", template = ChatTemplate.CHATML,
            tags = listOf(Res.string.model_tag_open, Res.string.model_tag_english)
        ),
        ModelSpec(
            id = "qwen2.5-1.5b-instruct-q4km",
            name = "Qwen 2.5 1.5B Instruct",
            descriptionRes = Res.string.model_qwen2_5_1_5b_desc,
            url = "$HF/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeMb = 1066, family = ModelFamily.QWEN, parameters = "1.5B", minRamGb = 4,
            quality = 3, speed = 4, russian = 4, license = "Apache 2.0", template = ChatTemplate.CHATML,
            tags = listOf(Res.string.model_tag_fast)
        ),
        ModelSpec(
            id = "qwen3-1.7b-q4km",
            name = "Qwen 3 1.7B",
            descriptionRes = Res.string.model_qwen3_1_7b_desc,
            url = "$HF/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
            sizeMb = 1056, family = ModelFamily.QWEN, parameters = "1.7B", minRamGb = 4,
            quality = 4, speed = 4, russian = 4, license = "Apache 2.0", template = ChatTemplate.QWEN3,
            tags = listOf(Res.string.model_tag_new)
        ),
        ModelSpec(
            id = "qwen3.5-2b-q4km",
            name = "Qwen 3.5 2B",
            descriptionRes = Res.string.model_qwen3_5_2b_desc,
            url = "$HF/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf",
            sizeMb = 1221, family = ModelFamily.QWEN, parameters = "2B", minRamGb = 4,
            quality = 4, speed = 4, russian = 5, license = "Apache 2.0", template = ChatTemplate.QWEN3,
            tags = listOf(Res.string.model_tag_new)
        ),
        ModelSpec(
            id = "deepseek-r1-distill-qwen-1.5b-q4km",
            name = "DeepSeek R1 Distill 1.5B",
            descriptionRes = Res.string.model_deepseek_r1_1_5b_desc,
            url = "$HF/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            sizeMb = 1066, family = ModelFamily.DEEPSEEK, parameters = "1.5B", minRamGb = 4,
            quality = 3, speed = 3, russian = 2, license = "MIT", template = ChatTemplate.DEEPSEEK_R1,
            tags = listOf(Res.string.model_tag_reasoning)
        ),
        ModelSpec(
            id = "gemma-2-2b-it-q4km",
            name = "Gemma 2 2B",
            descriptionRes = Res.string.model_gemma_2_2b_desc,
            url = "$HF/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            sizeMb = 1629, family = ModelFamily.GEMMA, parameters = "2B", minRamGb = 6,
            quality = 3, speed = 4, russian = 3, license = "Gemma", template = ChatTemplate.GEMMA,
            tags = listOf(Res.string.model_tag_google)
        ),
        ModelSpec(
            id = "llama3.2-3b-instruct-q4km",
            name = "Llama 3.2 3B Instruct",
            descriptionRes = Res.string.model_llama3_2_3b_desc,
            url = "$HF/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sizeMb = 1926, family = ModelFamily.LLAMA, parameters = "3B", minRamGb = 6,
            quality = 4, speed = 3, russian = 3, license = "Llama 3.2", template = ChatTemplate.LLAMA3
        ),
        ModelSpec(
            id = "qwen2.5-3b-instruct-q4km",
            name = "Qwen 2.5 3B Instruct",
            descriptionRes = Res.string.model_qwen2_5_3b_desc,
            url = "$HF/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            sizeMb = 2007, family = ModelFamily.QWEN, parameters = "3B", minRamGb = 6,
            quality = 4, speed = 3, russian = 4, license = "Qwen Research", template = ChatTemplate.CHATML,
            tags = listOf(Res.string.model_tag_balance), recommended = true
        ),
        ModelSpec(
            id = "phi3.5-mini-instruct-q4km",
            name = "Phi 3.5 Mini",
            descriptionRes = Res.string.model_phi3_5_mini_desc,
            url = "$HF/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            sizeMb = 2282, family = ModelFamily.PHI, parameters = "3.8B", minRamGb = 8,
            quality = 4, speed = 2, russian = 2, license = "MIT", template = ChatTemplate.PHI3,
            tags = listOf(Res.string.model_tag_analysis)
        ),
        ModelSpec(
            id = "gemma-3-4b-it-q4km",
            name = "Gemma 3 4B",
            descriptionRes = Res.string.model_gemma_3_4b_desc,
            url = "$HF/unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf",
            sizeMb = 2375, family = ModelFamily.GEMMA, parameters = "4B", minRamGb = 8,
            quality = 5, speed = 2, russian = 4, license = "Gemma", template = ChatTemplate.GEMMA,
            tags = listOf(Res.string.model_tag_google, Res.string.model_tag_quality)
        ),
        ModelSpec(
            id = "qwen3-4b-q4km",
            name = "Qwen 3 4B",
            descriptionRes = Res.string.model_qwen3_4b_desc,
            url = "$HF/unsloth/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
            sizeMb = 2382, family = ModelFamily.QWEN, parameters = "4B", minRamGb = 8,
            quality = 5, speed = 2, russian = 5, license = "Apache 2.0", template = ChatTemplate.QWEN3,
            tags = listOf(Res.string.model_tag_quality)
        ),
        ModelSpec(
            id = "qwen3.5-4b-q4km",
            name = "Qwen 3.5 4B",
            descriptionRes = Res.string.model_qwen3_5_4b_desc,
            url = "$HF/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf",
            sizeMb = 2614, family = ModelFamily.QWEN, parameters = "4B", minRamGb = 8,
            quality = 5, speed = 2, russian = 5, license = "Apache 2.0", template = ChatTemplate.QWEN3,
            tags = listOf(Res.string.model_tag_quality, Res.string.model_tag_new), recommended = true
        ),
        ModelSpec(
            id = "qwen2.5-7b-instruct-q4km",
            name = "Qwen 2.5 7B Instruct",
            descriptionRes = Res.string.model_qwen2_5_7b_desc,
            url = "$HF/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            sizeMb = 4466, family = ModelFamily.QWEN, parameters = "7B", minRamGb = 12,
            quality = 5, speed = 1, russian = 5, license = "Apache 2.0", template = ChatTemplate.CHATML,
            tags = listOf(Res.string.model_tag_max_quality)
        )
    )

    fun byId(id: String): ModelSpec? = models.firstOrNull { it.id == id }
}
