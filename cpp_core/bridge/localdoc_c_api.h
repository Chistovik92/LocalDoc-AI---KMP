#ifndef LOCALDOC_C_API_H
#define LOCALDOC_C_API_H

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Непрозрачный указатель на сессию инференса
typedef struct LocalDocSession LocalDocSession;

// Коллбэк для передачи каждого сгенерированного токена обратно в Kotlin.
// token_utf8 всегда содержит только завершённые UTF-8 последовательности.
typedef void (*token_callback_t)(const char* token_utf8, void* user_data);

// Коды результата localdoc_generate
#define LOCALDOC_OK                    0
#define LOCALDOC_ERR_INVALID_ARGS     -1
#define LOCALDOC_ERR_BUSY             -2
#define LOCALDOC_ERR_TOKENIZE         -3
#define LOCALDOC_ERR_PROMPT_TOO_LONG  -4
#define LOCALDOC_ERR_DECODE           -5

// Инициализация модели из GGUF-файла.
// n_gpu_layers: количество слоёв, выгружаемых на GPU (Vulkan на Android, Metal на iOS).
// Возвращает NULL при ошибке загрузки.
LocalDocSession* localdoc_init(const char* model_path, int n_ctx, int n_gpu_layers);

// Синхронная потоковая генерация: callback вызывается на каждый валидный UTF-8 фрагмент.
// max_tokens ограничивает длину ответа (<= 0 означает 1536).
// Возвращает LOCALDOC_OK или один из кодов LOCALDOC_ERR_*.
int localdoc_generate(
    LocalDocSession* session,
    const char* prompt,
    int max_tokens,
    token_callback_t callback,
    void* user_data
);

// Прерывание генерации на лету (из любого потока)
void localdoc_stop(LocalDocSession* session);

// Признак выполнения генерации
bool localdoc_is_generating(LocalDocSession* session);

// Выгрузка весов из памяти и освобождение сессии.
// Если генерация ещё идёт — прерывает её и дожидается завершения.
void localdoc_free(LocalDocSession* session);

// Версия C-моста (для диагностики)
const char* localdoc_version(void);

#ifdef __cplusplus
}
#endif

#endif // LOCALDOC_C_API_H
