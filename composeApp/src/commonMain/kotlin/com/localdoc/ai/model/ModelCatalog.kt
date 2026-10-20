package com.localdoc.ai.model

import com.localdoc.ai.download.Downloadable

/**
 * Шаблон диалога модели. BOS добавляет токенизатор C-моста (add_special = true),
 * поэтому здесь его нет.
 */
enum class ChatTemplate {
    CHATML, QWEN3, LLAMA3, GEMMA, PHI3, DEEPSEEK_R1;

    fun build(system: String, user: String): String = when (this) {
        CHATML -> chatMl(system, user)
        // Qwen 3 по умолчанию «думает» вслух — для читалки нужен быстрый прямой ответ
        QWEN3 -> chatMl(system, "$user /no_think")
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
    val description: String,
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
    val tags: List<String> = emptyList(),
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
            description = "Самая лёгкая модель: работает даже на бюджетных телефонах. Подходит для коротких выжимок.",
            url = "$HF/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeMb = 469, family = ModelFamily.QWEN, parameters = "0.5B", minRamGb = 3,
            quality = 2, speed = 5, russian = 3, license = "Apache 2.0", template = ChatTemplate.CHATML,
            tags = listOf("Для слабых устройств")
        ),
        ModelSpec(
            id = "gemma-3-1b-it-q4km",
            name = "Gemma 3 1B",
            description = "Компактная модель Google: быстрые ответы, аккуратный стиль, поддержка 140 языков.",
            url = "$HF/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
            sizeMb = 769, family = ModelFamily.GEMMA, parameters = "1B", minRamGb = 4,
            quality = 3, speed = 5, russian = 3, license = "Gemma", template = ChatTemplate.GEMMA,
            tags = listOf("Google", "Быстрая")
        ),
        ModelSpec(
            id = "llama3.2-1b-instruct-q4km",
            name = "Llama 3.2 1B Instruct",
            description = "Мобильная модель Meta: минимальный нагрев и расход батареи.",
            url = "$HF/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sizeMb = 770, family = ModelFamily.LLAMA, parameters = "1B", minRamGb = 4,
            quality = 2, speed = 5, russian = 2, license = "Llama 3.2", template = ChatTemplate.LLAMA3,
            tags = listOf("Экономит батарею")
        ),
        ModelSpec(
            id = "vikhr-qwen2.5-1.5b-instruct-q4km",
            name = "Vikhr Qwen 2.5 1.5B",
            description = "Дообучена на русскоязычных данных: естественный русский язык при малом размере.",
            url = "$HF/Vikhrmodels/Vikhr-Qwen-2.5-1.5B-Instruct-GGUF/resolve/main/Vikhr-Qwen-2.5-1.5b-Instruct-Q4_K_M.gguf",
            sizeMb = 940, family = ModelFamily.VIKHR, parameters = "1.5B", minRamGb = 4,
            quality = 3, speed = 4, russian = 5, license = "Apache 2.0", template = ChatTemplate.CHATML,
            tags = listOf("Лучший русский", "Компактная")
        ),
        ModelSpec(
            id = "smollm2-1.7b-instruct-q4km",
            name = "SmolLM2 1.7B Instruct",
            description = "Открытая модель Hugging Face, обученная на открытых данных. Лучше всего — на английском.",
            url = "$HF/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
            sizeMb = 1007, family = ModelFamily.SMOL, parameters = "1.7B", minRamGb = 4,
            quality = 3, speed = 4, russian = 1, license = "Apache 2.0", template = ChatTemplate.CHATML,
            tags = listOf("Полностью открытая", "English")
        ),
        ModelSpec(
            id = "qwen2.5-1.5b-instruct-q4km",
            name = "Qwen 2.5 1.5B Instruct",
            description = "Мгновенный отклик, минимальный нагрев. Быстрые выжимки глав на ходу.",
            url = "$HF/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeMb = 1066, family = ModelFamily.QWEN, parameters = "1.5B", minRamGb = 4,
            quality = 3, speed = 4, russian = 4, license = "Apache 2.0", template = ChatTemplate.CHATML,
            tags = listOf("Быстрая")
        ),
        ModelSpec(
            id = "qwen3-1.7b-q4km",
            name = "Qwen 3 1.7B",
            description = "Новое поколение Qwen: заметно умнее моделей того же размера, отличный русский.",
            url = "$HF/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
            sizeMb = 1056, family = ModelFamily.QWEN, parameters = "1.7B", minRamGb = 4,
            quality = 4, speed = 4, russian = 4, license = "Apache 2.0", template = ChatTemplate.QWEN3,
            tags = listOf("Новинка"), recommended = true
        ),
        ModelSpec(
            id = "deepseek-r1-distill-qwen-1.5b-q4km",
            name = "DeepSeek R1 Distill 1.5B",
            description = "Рассуждающая модель: сначала «думает», затем отвечает. Хороша для логики и задач, медленнее в чате.",
            url = "$HF/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            sizeMb = 1066, family = ModelFamily.DEEPSEEK, parameters = "1.5B", minRamGb = 4,
            quality = 3, speed = 3, russian = 2, license = "MIT", template = ChatTemplate.DEEPSEEK_R1,
            tags = listOf("Рассуждения")
        ),
        ModelSpec(
            id = "gemma-2-2b-it-q4km",
            name = "Gemma 2 2B",
            description = "Проверенная модель Google: связные пересказы и аккуратные формулировки.",
            url = "$HF/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            sizeMb = 1629, family = ModelFamily.GEMMA, parameters = "2B", minRamGb = 6,
            quality = 3, speed = 4, russian = 3, license = "Gemma", template = ChatTemplate.GEMMA,
            tags = listOf("Google")
        ),
        ModelSpec(
            id = "llama3.2-3b-instruct-q4km",
            name = "Llama 3.2 3B Instruct",
            description = "Быстрая модель, оптимизированная под мобильные чипы Qualcomm / Apple.",
            url = "$HF/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sizeMb = 1926, family = ModelFamily.LLAMA, parameters = "3B", minRamGb = 6,
            quality = 4, speed = 3, russian = 3, license = "Llama 3.2", template = ChatTemplate.LLAMA3
        ),
        ModelSpec(
            id = "qwen2.5-3b-instruct-q4km",
            name = "Qwen 2.5 3B Instruct",
            description = "Оптимальный баланс: чистый русский язык, точные выжимки и ответы по книгам.",
            url = "$HF/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            sizeMb = 2007, family = ModelFamily.QWEN, parameters = "3B", minRamGb = 6,
            quality = 4, speed = 3, russian = 4, license = "Qwen Research", template = ChatTemplate.CHATML,
            tags = listOf("Баланс"), recommended = true
        ),
        ModelSpec(
            id = "phi3.5-mini-instruct-q4km",
            name = "Phi 3.5 Mini",
            description = "Модель Microsoft с длинным контекстом: сильна в анализе и рассуждениях, лучше на английском.",
            url = "$HF/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            sizeMb = 2282, family = ModelFamily.PHI, parameters = "3.8B", minRamGb = 8,
            quality = 4, speed = 2, russian = 2, license = "MIT", template = ChatTemplate.PHI3,
            tags = listOf("Анализ")
        ),
        ModelSpec(
            id = "gemma-3-4b-it-q4km",
            name = "Gemma 3 4B",
            description = "Сильнейшая компактная модель Google: глубокие пересказы и ответы по сложным текстам.",
            url = "$HF/unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf",
            sizeMb = 2375, family = ModelFamily.GEMMA, parameters = "4B", minRamGb = 8,
            quality = 5, speed = 2, russian = 4, license = "Gemma", template = ChatTemplate.GEMMA,
            tags = listOf("Google", "Качество")
        ),
        ModelSpec(
            id = "qwen3-4b-q4km",
            name = "Qwen 3 4B",
            description = "Флагман для смартфонов: качество уровня крупных моделей, отличный русский язык.",
            url = "$HF/unsloth/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
            sizeMb = 2382, family = ModelFamily.QWEN, parameters = "4B", minRamGb = 8,
            quality = 5, speed = 2, russian = 5, license = "Apache 2.0", template = ChatTemplate.QWEN3,
            tags = listOf("Качество", "Новинка"), recommended = true
        ),
        ModelSpec(
            id = "qwen2.5-7b-instruct-q4km",
            name = "Qwen 2.5 7B Instruct",
            description = "Качество уровня серверных моделей. Требует 12+ ГБ ОЗУ, сильнее греет батарею.",
            url = "$HF/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            sizeMb = 4466, family = ModelFamily.QWEN, parameters = "7B", minRamGb = 12,
            quality = 5, speed = 1, russian = 5, license = "Apache 2.0", template = ChatTemplate.CHATML,
            tags = listOf("Максимум качества")
        )
    )

    fun byId(id: String): ModelSpec? = models.firstOrNull { it.id == id }
}
