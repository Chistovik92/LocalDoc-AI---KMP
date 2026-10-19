#ifndef LOCALDOC_C_API_H
#define LOCALDOC_C_API_H

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Непрозрачный указатель на сессию инференса
typedef struct LocalDocSession LocalDocSession;

// Коллбэк для передачи каждого сгенерированного токена обратно в Kotlin
typedef void (*token_callback_t)(const char* token_utf8, void* user_data);

// Инициализация модели из GGUF-файла.
// n_gpu_layers: количество слоёв, выгружаемых на GPU (Vulkan на Android, Metal на iOS).
// Возвращает NULL при ошибке загрузки.
LocalDocSession* localdoc_init(const char* model_path, int n_ctx, int n_gpu_layers);

// Синхронная потоковая генерация: callback вызывается на каждый валидный UTF-8 токен.
// max_tokens ограничивает длину ответа (<= 0 означает 1536).
void localdoc_generate(
    LocalDocSession* session,
    const char* prompt,
    int max_tokens,
    token_callback_t callback,
    void* user_data
);

// Прерывание генерации на лету (из любого потока)
void localdoc_stop(LocalDocSession* session);

// Признак завершения текущей генерации
bool localdoc_is_generating(LocalDocSession* session);

// Выгрузка весов из памяти и освобождение сессии
void localdoc_free(LocalDocSession* session);

// Версия C-моста (для диагностики)
const char* localdoc_version(void);

#ifdef __cplusplus
}
#endif

#endif // LOCALDOC_C_API_H
