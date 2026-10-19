// Тонкий JNI-мост Android: пробрасывает вызовы Kotlin в единый C-API (localdoc_c_api.h).
#include <jni.h>
#include <cstring>
#include "localdoc_c_api.h"

static void throwEngineError(JNIEnv* env, const char* message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message);
}

struct JniCallbackContext {
    JNIEnv* env;
    jobject callback_obj;
    jmethodID on_token_mid;
    LocalDocSession* session;
};

// Токены передаются байтами: NewStringUTF ожидает Modified UTF-8 и падает
// под CheckJNI на 4-байтовых последовательностях (эмодзи, редкие символы).
static void jni_token_trampoline(const char* token_utf8, void* user_data) {
    auto* ctx = static_cast<JniCallbackContext*>(user_data);
    if (!ctx || !ctx->env || !ctx->on_token_mid || !token_utf8) return;
    JNIEnv* env = ctx->env;
    if (env->ExceptionCheck()) return;

    const auto len = static_cast<jsize>(std::strlen(token_utf8));
    jbyteArray bytes = env->NewByteArray(len);
    if (!bytes) {
        localdoc_stop(ctx->session);
        return;
    }
    env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte*>(token_utf8));
    env->CallVoidMethod(ctx->callback_obj, ctx->on_token_mid, bytes);
    env->DeleteLocalRef(bytes);
    // Исключение в Kotlin-коллбэке — прекращаем генерацию, оно пробросится после возврата
    if (env->ExceptionCheck()) localdoc_stop(ctx->session);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_localdoc_ai_engine_LlamaEngine_initNative(
        JNIEnv* env, jobject /*thiz*/, jstring path, jint n_ctx, jint n_gpu_layers) {
    if (!path) return 0;
    const char* c_path = env->GetStringUTFChars(path, nullptr);
    if (!c_path) return 0;
    auto* session = localdoc_init(c_path, n_ctx, n_gpu_layers);
    env->ReleaseStringUTFChars(path, c_path);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_localdoc_ai_engine_LlamaEngine_generateNative(
        JNIEnv* env, jobject /*thiz*/, jlong ptr, jbyteArray prompt_utf8, jint max_tokens, jobject callback) {
    if (ptr == 0 || !prompt_utf8 || !callback) return LOCALDOC_ERR_INVALID_ARGS;

    // Промпт приходит стандартным UTF-8 (не Modified UTF-8) с завершающим нулём от Kotlin
    jbyte* c_prompt = env->GetByteArrayElements(prompt_utf8, nullptr);
    if (!c_prompt) return LOCALDOC_ERR_INVALID_ARGS;

    jclass cls = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(cls, "onToken", "([B)V");
    env->DeleteLocalRef(cls);
    if (!on_token) {
        env->ReleaseByteArrayElements(prompt_utf8, c_prompt, JNI_ABORT);
        env->ExceptionClear();
        throwEngineError(env, "TokenCallback.onToken not found");
        return LOCALDOC_ERR_INVALID_ARGS;
    }

    auto* session = reinterpret_cast<LocalDocSession*>(ptr);
    JniCallbackContext ctx{env, callback, on_token, session};
    const int result = localdoc_generate(
        session, reinterpret_cast<const char*>(c_prompt), max_tokens, jni_token_trampoline, &ctx);
    env->ReleaseByteArrayElements(prompt_utf8, c_prompt, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_localdoc_ai_engine_LlamaEngine_stopNative(JNIEnv*, jobject, jlong ptr) {
    localdoc_stop(reinterpret_cast<LocalDocSession*>(ptr));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_localdoc_ai_engine_LlamaEngine_isGeneratingNative(JNIEnv*, jobject, jlong ptr) {
    return localdoc_is_generating(reinterpret_cast<LocalDocSession*>(ptr)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_localdoc_ai_engine_LlamaEngine_freeNative(JNIEnv*, jobject, jlong ptr) {
    localdoc_free(reinterpret_cast<LocalDocSession*>(ptr));
}
