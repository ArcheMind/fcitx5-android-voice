/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
#include <jni.h>

#include <android/log.h>

#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>

#include <llm/llm.hpp>

using MNN::Transformer::Llm;
using MNN::Transformer::ChatMessages;
using MNN::Transformer::LlmStatus;

namespace {
std::mutex engine_mutex;
std::unique_ptr<Llm> engine;
std::string loaded_config;
std::string loaded_system_prompt;
ChatMessages chat_messages;
bool chat_session_active = false;

jbyteArray toBytes(JNIEnv* env, const std::string& text) {
    auto bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    if (bytes) {
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()),
                               reinterpret_cast<const jbyte*>(text.data()));
    }
    return bytes;
}

class StreamingBuffer : public std::stringbuf {
public:
    StreamingBuffer(JNIEnv* env, jobject callback, jmethodID method)
        : env_(env), callback_(callback), method_(method) {}

    int sync() override {
        if (env_->ExceptionCheck()) return -1;
        auto bytes = toBytes(env_, str());
        if (!bytes) return -1;
        env_->CallVoidMethod(callback_, method_, bytes);
        env_->DeleteLocalRef(bytes);
        return env_->ExceptionCheck() ? -1 : 0;
    }

private:
    JNIEnv* env_;
    jobject callback_;
    jmethodID method_;
};

std::string fromJString(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void throwIllegalState(JNIEnv* env, const std::string& message) {
    auto clazz = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(clazz, message.c_str());
}

void endChatSession() {
    if (engine && chat_session_active) {
        engine->endChatSession();
    }
    chat_messages.clear();
    chat_session_active = false;
}

std::string jsonString(const std::string& value) {
    static constexpr char Hex[] = "0123456789abcdef";
    std::string result = "\"";
    for (const unsigned char character : value) {
        switch (character) {
            case '"': result += "\\\""; break;
            case '\\': result += "\\\\"; break;
            case '\b': result += "\\b"; break;
            case '\f': result += "\\f"; break;
            case '\n': result += "\\n"; break;
            case '\r': result += "\\r"; break;
            case '\t': result += "\\t"; break;
            default:
                if (character < 0x20) {
                    result += "\\u00";
                    result += Hex[character >> 4];
                    result += Hex[character & 0x0f];
                } else {
                    result += static_cast<char>(character);
                }
        }
    }
    return result + '"';
}

void ensureLoaded(const std::string& config, const std::string& system_prompt) {
    if (engine && loaded_config == config && loaded_system_prompt == system_prompt) return;
    endChatSession();
    engine.reset(Llm::createLLM(config));
    if (!engine) {
        throw std::runtime_error("MNN could not create the local model");
    }
    engine->set_config(
        R"({"async":false,"has_talker":false,"is_visual":false,"max_new_tokens":256,"reuse_kv":true,"use_mmap":true,"system_prompt":)" +
        jsonString(system_prompt) + "}");
    if (!engine->load()) {
        const auto detail = engine->getLog();
        engine.reset();
        throw std::runtime_error("MNN model load failed: " + detail);
    }
    if (!engine->prefillFixedPrompt()) {
        const auto detail = engine->getLog();
        engine.reset();
        throw std::runtime_error("MNN fixed prompt prefill failed: " + detail);
    }
    __android_log_print(ANDROID_LOG_DEBUG, "fcitx5", "MNN fixed prefix ready: tokens=%zu",
                        engine->getCurrentHistory());
    loaded_config = config;
    loaded_system_prompt = system_prompt;
}
}

extern "C" JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_input_voice_LocalMnnEngine_unloadNative(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(engine_mutex);
    if (engine) {
        __android_log_print(ANDROID_LOG_DEBUG, "fcitx5", "MNN unload: releasing engine");
        endChatSession();
        engine.reset();
        loaded_config.clear();
        loaded_system_prompt.clear();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_input_voice_LocalMnnEngine_prewarmNative(
        JNIEnv* env, jobject, jstring config_path, jstring system_prompt) {
    try {
        std::lock_guard<std::mutex> lock(engine_mutex);
        ensureLoaded(fromJString(env, config_path), fromJString(env, system_prompt));
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_input_voice_LocalMnnEngine_beginSessionNative(
        JNIEnv* env, jobject, jstring config_path, jstring system_prompt, jstring context_message) {
    try {
        std::lock_guard<std::mutex> lock(engine_mutex);
        endChatSession();
        const auto systemPrompt = fromJString(env, system_prompt);
        ensureLoaded(fromJString(env, config_path), systemPrompt);
        if (!engine->beginChatSession()) {
            throw std::runtime_error("MNN could not begin the transcription chat session");
        }
        chat_messages.emplace_back("system", systemPrompt);
        chat_messages.emplace_back("user", fromJString(env, context_message));
        chat_session_active = true;
        __android_log_print(ANDROID_LOG_DEBUG, "fcitx5", "MNN chat session started: context=%s",
                            chat_messages.back().second.c_str());
    } catch (const std::exception& error) {
        endChatSession();
        throwIllegalState(env, error.what());
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_fcitx_fcitx5_android_input_voice_LocalMnnEngine_transcribeNative(
        JNIEnv* env, jobject, jstring audio_message, jobject callback) {
    try {
        auto callback_class = env->GetObjectClass(callback);
        auto method = env->GetMethodID(callback_class, "onPartial", "([B)V");
        env->DeleteLocalRef(callback_class);
        if (!method) return nullptr;
        std::lock_guard<std::mutex> lock(engine_mutex);
        if (!engine || !chat_session_active) {
            throw std::runtime_error("MNN transcription chat session is not active");
        }
        chat_messages.emplace_back("user", fromJString(env, audio_message));
        __android_log_print(ANDROID_LOG_DEBUG, "fcitx5", "MNN chat user message: %s",
                            chat_messages.back().second.c_str());
        StreamingBuffer buffer(env, callback, method);
        std::ostream output(&buffer);
        engine->responseChatSession(chat_messages, &output, "<eop>", 256);
        const auto* context = engine->getContext();
        if (context && context->status == LlmStatus::INTERNAL_ERROR) {
            throw std::runtime_error("MNN chat response failed: " + engine->getLog());
        }
        if (context) {
            __android_log_print(
                ANDROID_LOG_DEBUG,
                "fcitx5",
                "MNN performance: audio_us=%lld prefill_us=%lld decode_us=%lld ttfa_us=%lld sample_us=%lld prompt_len=%d gen_seq_len=%d",
                static_cast<long long>(context->audio_us),
                static_cast<long long>(context->prefill_us),
                static_cast<long long>(context->decode_us),
                static_cast<long long>(context->ttfa_us),
                static_cast<long long>(context->sample_us),
                context->prompt_len,
                context->gen_seq_len);
        }
        if (env->ExceptionCheck()) return nullptr;
        return toBytes(env, buffer.str());
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_input_voice_LocalMnnEngine_commitTranscriptNative(
        JNIEnv* env, jobject, jstring transcript) {
    try {
        std::lock_guard<std::mutex> lock(engine_mutex);
        if (!engine || !chat_session_active) {
            throw std::runtime_error("MNN transcription chat session is not active");
        }
        chat_messages.emplace_back("assistant", fromJString(env, transcript));
        __android_log_print(ANDROID_LOG_DEBUG, "fcitx5", "MNN chat assistant message: %s",
                            chat_messages.back().second.c_str());
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_input_voice_LocalMnnEngine_endSessionNative(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(engine_mutex);
    endChatSession();
    __android_log_print(ANDROID_LOG_DEBUG, "fcitx5", "MNN chat session ended");
}
