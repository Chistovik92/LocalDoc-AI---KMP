// Тонкий JNI-мост Android: пробрасывает вызовы Kotlin в единый C-API (localdoc_c_api.h).
#include <jni.h>
#include <string>
#include "localdoc_c_api.h"

static void throwEngineError(JNIEnv* env, const char* message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message);
}

struct JniCallbackContext {
    JNIEnv* env;
    jobject callback_obj;
    jmethodID on_token_mid;
};

static void jni_token_trampoline(const char* token_utf8, void* user_data) {
    auto* ctx = static_cast<JniCallbackContext*>(user_data);
    if (!ctx->env || !ctx->on_token_mid) return;
    jstring jtok = ctx->env->NewStringUTF(token_utf8);
    if (!jtok) return;
    ctx->env->CallVoidMethod(ctx->callback_obj, ctx->on_token_mid, jtok);
    ctx->env->DeleteLocalRef(jtok);
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

extern "C" JNIEXPORT void JNICALL
Java_com_localdoc_ai_engine_LlamaEngine_generateNative(
        JNIEnv* env, jobject /*thiz*/, jlong ptr, jstring prompt, jint max_tokens, jobject callback) {
    if (ptr == 0 || !prompt || !callback) return;
    const char* c_prompt = env->GetStringUTFChars(prompt, nullptr);
    if (!c_prompt) return;

    jclass cls = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    if (!on_token) {
        env->ReleaseStringUTFChars(prompt, c_prompt);
        throwEngineError(env, "TokenCallback.onToken not found");
        return;
    }

    JniCallbackContext ctx{env, callback, on_token};
    localdoc_generate(reinterpret_cast<LocalDocSession*>(ptr), c_prompt, max_tokens, jni_token_trampoline, &ctx);
    env->ReleaseStringUTFChars(prompt, c_prompt);
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
