#include "localdoc_c_api.h"

#include "llama.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

struct LocalDocSession {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_sampler* sampler = nullptr;
    // Отмена привязана к «эпохе» генерации: stop() прерывает только текущую генерацию
    // и не может ни потеряться, ни «залипнуть» и оборвать следующую.
    std::atomic<uint64_t> generation_id{0};
    std::atomic<uint64_t> aborted_id{0};
    std::atomic<bool> closing{false}; // выставляется localdoc_free, не сбрасывается
    std::atomic<bool> generating{false};
    // Захват сессии и выдача эпохи — один переход: stop() не может «попасть между» ними
    std::mutex state_mutex;

    bool should_abort(uint64_t id) const {
        return closing.load() || aborted_id.load() == id;
    }
};

namespace {

constexpr int kDefaultMaxTokens = 1536;
constexpr int kMaxThreads = 6;

// Длина префикса buf, заканчивающегося на границе завершённого UTF-8 символа.
// Хвост (начатая, но не завершённая последовательность) остаётся в буфере
// до прихода следующего токена — иначе кириллица рвётся посередине символа.
size_t utf8_complete_prefix(const std::string& buf) {
    const size_t len = buf.size();
    // Ищем ведущий байт последнего символа (не дальше 4 байт от конца)
    for (size_t back = 1; back <= 4 && back <= len; ++back) {
        const auto byte = static_cast<unsigned char>(buf[len - back]);
        if ((byte & 0xC0) == 0x80) continue; // байт продолжения 10xxxxxx

        size_t expected = 1;
        if ((byte & 0xE0) == 0xC0) expected = 2;
        else if ((byte & 0xF0) == 0xE0) expected = 3;
        else if ((byte & 0xF8) == 0xF0) expected = 4;
        return back >= expected ? len : len - back;
    }
    return len; // некорректная последовательность — не задерживаем вывод
}

int thread_count() {
    const unsigned hw = std::thread::hardware_concurrency();
    if (hw == 0) return 4;
    return std::clamp(static_cast<int>(hw), 2, kMaxThreads);
}

// Декодирование промпта порциями не больше n_batch:
// llama_decode завершает процесс через GGML_ASSERT, если батч больше n_batch.
// Между порциями проверяем отмену — prefill длинного документа может идти секундами.
// Возвращает false при ошибке декодирования; aborted = true, если генерацию прервали.
bool decode_prompt(LocalDocSession* session, uint64_t id, std::vector<llama_token>& tokens, bool& aborted) {
    const auto n_batch = static_cast<size_t>(llama_n_batch(session->ctx));
    for (size_t pos = 0; pos < tokens.size(); pos += n_batch) {
        if (session->should_abort(id)) {
            aborted = true;
            return true;
        }
        const auto n = static_cast<int32_t>(std::min(n_batch, tokens.size() - pos));
        if (llama_decode(session->ctx, llama_batch_get_one(tokens.data() + pos, n)) != 0) return false;
    }
    return true;
}

// Сбрасывает флаг генерации при любом выходе из localdoc_generate
struct GeneratingGuard {
    std::atomic<bool>& flag;
    ~GeneratingGuard() { flag.store(false); }
};

} // namespace

extern "C" {

LocalDocSession* localdoc_init(const char* model_path, int n_ctx, int n_gpu_layers) {
    if (!model_path || n_ctx <= 0) return nullptr;

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers; // Android -> Vulkan, iOS -> Metal
    mparams.use_mmap = true;             // Экономия RAM: ОС выгружает холодные страницы без краша

    llama_model* model = llama_model_load_from_file(model_path, mparams);
    if (!model) {
        llama_backend_free();
        return nullptr;
    }

    const int n_threads = thread_count();
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    cparams.n_batch = 512;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        llama_backend_free();
        return nullptr;
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(sparams);
    if (!sampler) {
        llama_free(ctx);
        llama_model_free(model);
        llama_backend_free();
        return nullptr;
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.3f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    auto* session = new LocalDocSession();
    session->model = model;
    session->ctx = ctx;
    session->sampler = sampler;
    return session;
}

int localdoc_generate(
    LocalDocSession* session,
    const char* prompt_cstr,
    int max_tokens,
    token_callback_t callback,
    void* user_data
) {
    if (!session || !session->ctx || !session->model || !prompt_cstr) return LOCALDOC_ERR_INVALID_ARGS;

    // Атомарно занимаем сессию и открываем новую эпоху отмены:
    // параллельная генерация в одном контексте недопустима
    uint64_t id = 0;
    {
        std::lock_guard<std::mutex> lock(session->state_mutex);
        if (session->closing.load()) return LOCALDOC_ERR_INVALID_ARGS;
        if (session->generating.load()) return LOCALDOC_ERR_BUSY;
        session->generating.store(true);
        id = session->generation_id.fetch_add(1) + 1;
    }
    GeneratingGuard guard{session->generating};

    const std::string prompt(prompt_cstr);

    // Сброс KV-кэша и состояния сэмплера перед новым запросом
    llama_memory_clear(llama_get_memory(session->ctx), false);
    llama_sampler_reset(session->sampler);

    const llama_vocab* vocab = llama_model_get_vocab(session->model);
    const auto prompt_len = static_cast<int32_t>(prompt.length());

    const int n_prompt_tokens = -llama_tokenize(vocab, prompt.c_str(), prompt_len, nullptr, 0, true, true);
    if (n_prompt_tokens <= 0) return LOCALDOC_ERR_TOKENIZE;

    std::vector<llama_token> tokens(static_cast<size_t>(n_prompt_tokens));
    if (llama_tokenize(vocab, prompt.c_str(), prompt_len,
                       tokens.data(), static_cast<int32_t>(tokens.size()), true, true) < 0) {
        return LOCALDOC_ERR_TOKENIZE;
    }

    // Оставляем в контексте место хотя бы под небольшой ответ
    const int n_ctx = static_cast<int>(llama_n_ctx(session->ctx));
    if (n_prompt_tokens >= n_ctx - 16) return LOCALDOC_ERR_PROMPT_TOO_LONG;

    bool aborted = false;
    if (!decode_prompt(session, id, tokens, aborted)) return LOCALDOC_ERR_DECODE;
    if (aborted) return LOCALDOC_OK;

    if (max_tokens <= 0) max_tokens = kDefaultMaxTokens;
    max_tokens = std::min(max_tokens, n_ctx - n_prompt_tokens);

    std::string utf8_buf;
    std::vector<char> piece(256);
    for (int i = 0; i < max_tokens && !session->should_abort(id); ++i) {
        llama_token id = llama_sampler_sample(session->sampler, session->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;

        int n = llama_token_to_piece(vocab, id, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
        if (n < 0) {
            // Буфер мал: отрицательное значение — требуемый размер
            piece.resize(static_cast<size_t>(-n));
            n = llama_token_to_piece(vocab, id, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
        }
        if (n > 0) {
            utf8_buf.append(piece.data(), static_cast<size_t>(n));
            const size_t ready = utf8_complete_prefix(utf8_buf);
            if (ready > 0) {
                if (callback) callback(utf8_buf.substr(0, ready).c_str(), user_data);
                utf8_buf.erase(0, ready);
            }
        }

        if (llama_decode(session->ctx, llama_batch_get_one(&id, 1)) != 0) break;
    }

    return LOCALDOC_OK;
}

void localdoc_stop(LocalDocSession* session) {
    if (!session) return;
    std::lock_guard<std::mutex> lock(session->state_mutex);
    // Без активной генерации останавливать нечего; иначе — текущая эпоха
    if (session->generating.load()) session->aborted_id.store(session->generation_id.load());
}

bool localdoc_is_generating(LocalDocSession* session) {
    return session && session->generating.load();
}

void localdoc_free(LocalDocSession* session) {
    if (!session) return;

    // Освобождать контекст во время генерации на другом потоке — use-after-free.
    // После closing новые генерации не стартуют (проверка под state_mutex).
    {
        std::lock_guard<std::mutex> lock(session->state_mutex);
        session->closing.store(true);
    }
    while (session->generating.load()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }

    if (session->sampler) llama_sampler_free(session->sampler);
    if (session->ctx) llama_free(session->ctx);
    if (session->model) llama_model_free(session->model);
    delete session;
    llama_backend_free();
}

const char* localdoc_version(void) {
    return "localdoc-c-api/1.1";
}

} // extern "C"
