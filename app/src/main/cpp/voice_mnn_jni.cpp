/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
#include <jni.h>

#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>

#include <llm/llm.hpp>

using MNN::Transformer::Llm;

namespace {
std::mutex engine_mutex;
std::unique_ptr<Llm> engine;
std::string loaded_config;

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

void ensureLoaded(const std::string& config) {
    if (engine && loaded_config == config) return;
    engine.reset(Llm::createLLM(config));
    if (!engine) {
        throw std::runtime_error("MNN could not create the local model");
    }
    engine->set_config(R"({"async":false,"has_talker":false,"is_visual":false,"max_new_tokens":256,"use_mmap":true,"system_prompt":"Speech-to-text only. Output exactly the spoken words and nothing else. Include natural punctuation."})");
    if (!engine->load()) {
        const auto detail = engine->getLog();
        engine.reset();
        throw std::runtime_error("MNN model load failed: " + detail);
    }
    loaded_config = config;
}
}

extern "C" JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_input_voice_LocalMnnEngine_prewarmNative(
        JNIEnv* env, jobject, jstring config_path) {
    try {
        std::lock_guard<std::mutex> lock(engine_mutex);
        ensureLoaded(fromJString(env, config_path));
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_fcitx_fcitx5_android_input_voice_LocalMnnEngine_transcribeNative(
        JNIEnv* env, jobject, jstring config_path, jstring audio_path, jstring instruction) {
    try {
        std::lock_guard<std::mutex> lock(engine_mutex);
        const auto config = fromJString(env, config_path);
        const bool already_loaded = engine && loaded_config == config;
        ensureLoaded(config);
        if (already_loaded) engine->reset();
        const auto prompt = fromJString(env, instruction) + "\n<audio>" +
                            fromJString(env, audio_path) + "</audio>";
        std::ostringstream output;
        engine->response(prompt, &output, "<eop>", 256);
        return env->NewStringUTF(output.str().c_str());
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
        return nullptr;
    }
}
