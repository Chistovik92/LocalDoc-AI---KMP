#include "localdoc_c_api.h"

#include "llama.h"

#include <atomic>
#include <string>
#include <vector>

struct LocalDocSession {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_sampler* sampler = nullptr;
    std::atomic<bool> abort_flag{false};
    std::atomic<bool> generating{false};
};

extern "C" {

LocalDocSession* localdoc_init(const char* model_path, int n_ctx, int n_gpu_layers) {
    if (!model_path || n_ctx <= 0) return nullptr;

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers; // Android -> Vulkan, iOS -> Metal
    mparams.use_mmap = true;             // Р­РєРѕРЅРѕРјРёСЏ RAM: РћРЎ РІС‹РіСЂСѓР¶Р°РµС‚ С…РѕР»РѕРґРЅС‹Рµ СЃС‚СЂР°РЅРёС†С‹ Р±РµР· РєСЂР°С€Р°

    llama_model* model = llama_model_load_from_file(model_path, mparams);
    if (!model) {
        llama_backend_free();
        return nullptr;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    cparams.n_batch = 512;
    cparams.n_threads = 6;
    cparams.n_threads_batch = 6;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        llama_backend_free();
        return nullptr;
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(sparams);
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

void localdoc_generate(
    LocalDocSession* session,
    const char* prompt_cstr,
    int max_tokens,
    token_callback_t callback,
    void* user_data
) {
    if (!session || !session->ctx || !prompt_cstr) return;
    if (session->generating.load()) return;

    session->abort_flag.store(false);
    session->generating.store(true);

    const std::string prompt(prompt_cstr);

    // РЎР±СЂРѕСЃ KV-РєСЌС€Р° РїРµСЂРµРґ РЅРѕРІС‹Рј Р·Р°РїСЂРѕСЃРѕРј
    llama_memory_clear(llama_get_memory(session->ctx), false);

    const llama_vocab* vocab = llama_model_get_vocab(session->model);

    const int n_prompt_tokens =
        -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.length()), nullptr, 0, true, true);
    if (n_prompt_tokens <= 0) {
        session->generating.store(false);
        return;
    }
    std::vector<llama_token> tokens(static_cast<size_t>(n_prompt_tokens));
    if (llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.length()),
                       tokens.data(), static_cast<int32_t>(tokens.size()), true, true) < 0) {
        session->generating.store(false);
        return;
    }

    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(session->ctx, batch) != 0) {
        session->generating.store(false);
        return;
    }

    if (max_tokens <= 0) max_tokens = 1536;

    std::string utf8_buf;
    for (int i = 0; i < max_tokens && !session->abort_flag.load(); ++i) {
        llama_token id = llama_sampler_sample(session->sampler, session->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;

        char piece[256];
        const int n = llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, true);
        if (n > 0) {
            utf8_buf.append(piece, static_cast<size_t>(n));
            // РћС‚РґР°С‘Рј РІ UI С‚РѕР»СЊРєРѕ Р·Р°РІРµСЂС€С‘РЅРЅС‹Рµ UTF-8 СЃРёРјРІРѕР»С‹:
            // Р¶РґС‘Рј, РїРѕРєР° РїРѕСЃР»РµРґРЅРёР№ Р±Р°Р№С‚ РЅРµ РѕРєР°Р¶РµС‚СЃСЏ Р±Р°Р№С‚РѕРј РїСЂРѕРґРѕР»Р¶РµРЅРёСЏ (0b10xxxxxx).
            const unsigned char last = static_cast<unsigned char>(utf8_buf.back());
            if ((last & 0xC0) != 0x80) {
                if (callback) callback(utf8_buf.c_str(), user_data);
                utf8_buf.clear();
            }
        }

        batch = llama_batch_get_one(&id, 1);
        if (llama_decode(session->ctx, batch) != 0) break;
    }

    session->generating.store(false);
}

void localdoc_stop(LocalDocSession* session) {
    if (session) session->abort_flag.store(true);
}

bool localdoc_is_generating(LocalDocSession* session) {
    return session && session->generating.load();
}

void localdoc_free(LocalDocSession* session) {
    if (session) {
        if (session->sampler) llama_sampler_free(session->sampler);
        if (session->ctx) llama_free(session->ctx);
        if (session->model) llama_model_free(session->model);
        delete session;
        llama_backend_free();
    }
}

const char* localdoc_version(void) {
    return "localdoc-c-api/1.0";
}

} // extern "C"
