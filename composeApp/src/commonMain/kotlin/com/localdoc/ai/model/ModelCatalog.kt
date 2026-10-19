package com.localdoc.ai.model

/** Описание GGUF-модели в каталоге. */
data class ModelSpec(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val sizeMb: Long,
    val defaultContext: Int = 8192,
    val defaultGpuLayers: Int = 99
)

/**
 * Каталог моделей: всё работает офлайн, модели скачиваются напрямую с Hugging Face
 * в 4-битном квантовании (Q4_K_M) — оптимально для флагманских смартфонов.
 */
object ModelCatalog {

    val models: List<ModelSpec> = listOf(
        ModelSpec(
            id = "qwen2.5-1.5b-instruct-q4km",
            name = "Qwen 2.5 1.5B Instruct",
            description = "Мгновенный отклик, минимальный нагрев. Быстрые выжимки заметок на ходу.",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeMb = 1138,
            defaultContext = 8192
        ),
        ModelSpec(
            id = "qwen2.5-3b-instruct-q4km",
            name = "Qwen 2.5 3B Instruct",
            description = "Оптимальный баланс: чистый русский язык, точные выжимки и ответы по документам.",
            url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            sizeMb = 2014,
            defaultContext = 8192
        ),
        ModelSpec(
            id = "llama3.2-3b-instruct-q4km",
            name = "Llama 3.2 3B Instruct",
            description = "Быстрая модель, оптимизированная под мобильные чипы Qualcomm / Apple.",
            url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sizeMb = 2014,
            defaultContext = 8192
        ),
        ModelSpec(
            id = "qwen2.5-7b-instruct-q4km",
            name = "Qwen 2.5 7B Instruct",
            description = "Качество уровня серверных моделей. Требует 12+ ГБ ОЗУ, сильнее греет батарею.",
            url = "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/main/qwen2.5-7b-instruct-q4_k_m.gguf",
            sizeMb = 4677,
            defaultContext = 8192
        )
    )

    fun byId(id: String): ModelSpec? = models.firstOrNull { it.id == id }
}
