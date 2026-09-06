/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) 2023 Xiaomi Corporation
 */
#include <jni.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include <MNN/Interpreter.hpp>
#include <MNN/expr/ExprCreator.hpp>
#include <MNN/expr/ExecutorScope.hpp>
#include <MNN/expr/Module.hpp>

namespace {
constexpr int kSampleRate = 16000;
constexpr int kFrameSamples = 512;
constexpr int kContextSamples = 64;

void throwIllegalState(JNIEnv* env, const std::string& message) {
    auto clazz = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(clazz, message.c_str());
}

class SileroVad {
public:
    SileroVad(const uint8_t* model, size_t model_size) {
        MNN::ScheduleConfig schedule;
        schedule.numThread = 1;
        MNN::BackendConfig backend;
        backend.memory = MNN::BackendConfig::Memory_Low;
        schedule.backendConfig = &backend;
        runtime_.reset(MNN::Express::Executor::RuntimeManager::createRuntimeManager(schedule));
        if (!runtime_) throw std::runtime_error("MNN could not create the VAD runtime");

        MNN::Express::Module::Config config;
        config.rearrange = true;
        module_.reset(MNN::Express::Module::load({}, {}, model, model_size, runtime_, &config));
        if (!module_) throw std::runtime_error("MNN could not load the Silero VAD model");
        const auto* info = module_->getInfo();
        if (!info || info->inputNames != std::vector<std::string>({"input", "state", "sr"}) ||
            info->outputNames != std::vector<std::string>({"output", "stateN"})) {
            throw std::runtime_error("Unexpected Silero VAD model inputs or outputs");
        }
        reset();
    }

    void reset() {
        context_.fill(0.0f);
        const std::vector<int> state_shape{2, 1, 128};
        state_ = MNN::Express::_Const(
            zero_state_.data(), state_shape, MNN::Express::NCHW, halide_type_of<float>());
    }

    float predict(const int16_t* samples, size_t count) {
        if (count != kFrameSamples) throw std::runtime_error("Unexpected VAD frame size");
        std::array<float, kContextSamples + kFrameSamples> input_data{};
        std::copy(context_.begin(), context_.end(), input_data.begin());
        std::transform(samples, samples + count, input_data.begin() + kContextSamples,
                       [](int16_t sample) { return static_cast<float>(sample) / 32768.0f; });

        const std::vector<int> input_shape{1, static_cast<int>(input_data.size())};
        auto input = MNN::Express::_Const(
            input_data.data(), input_shape, MNN::Express::NCHW, halide_type_of<float>());
        const std::vector<int> sample_rate_shape{1};
        auto sample_rate = MNN::Express::_Const(
            &sample_rate_, sample_rate_shape, MNN::Express::NCHW, halide_type_of<int>());
        auto outputs = module_->onForward({input, state_, sample_rate});
        if (outputs.size() != 2 || outputs[0].get() == nullptr || outputs[1].get() == nullptr) {
            throw std::runtime_error("Silero VAD inference failed");
        }
        state_ = outputs[1];
        std::transform(samples + count - kContextSamples, samples + count, context_.begin(),
                       [](int16_t sample) { return static_cast<float>(sample) / 32768.0f; });
        return outputs[0]->readMap<float>()[0];
    }

private:
    std::shared_ptr<MNN::Express::Executor::RuntimeManager> runtime_;
    std::unique_ptr<MNN::Express::Module> module_;
    MNN::Express::VARP state_;
    std::array<float, 2 * 1 * 128> zero_state_{};
    std::array<float, kContextSamples> context_{};
    int sample_rate_ = kSampleRate;
};

SileroVad* fromPointer(jlong pointer) {
    auto* vad = reinterpret_cast<SileroVad*>(pointer);
    if (!vad) throw std::runtime_error("Silero VAD is not initialized");
    return vad;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_fcitx_fcitx5_android_input_voice_SileroVadNative_create(
        JNIEnv* env, jobject, jbyteArray model) {
    try {
        const auto size = env->GetArrayLength(model);
        auto* bytes = env->GetByteArrayElements(model, nullptr);
        if (!bytes) throw std::runtime_error("Cannot read the Silero VAD model");
        try {
            auto vad = std::make_unique<SileroVad>(reinterpret_cast<uint8_t*>(bytes), size);
            env->ReleaseByteArrayElements(model, bytes, JNI_ABORT);
            return reinterpret_cast<jlong>(vad.release());
        } catch (...) {
            env->ReleaseByteArrayElements(model, bytes, JNI_ABORT);
            throw;
        }
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_input_voice_SileroVadNative_reset(
        JNIEnv* env, jobject, jlong pointer) {
    try {
        fromPointer(pointer)->reset();
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
    }
}

extern "C" JNIEXPORT jfloat JNICALL
Java_org_fcitx_fcitx5_android_input_voice_SileroVadNative_predict(
        JNIEnv* env, jobject, jlong pointer, jshortArray samples) {
    try {
        const auto size = env->GetArrayLength(samples);
        auto* data = env->GetShortArrayElements(samples, nullptr);
        if (!data) throw std::runtime_error("Cannot read the VAD audio frame");
        try {
            const auto probability = fromPointer(pointer)->predict(data, size);
            env->ReleaseShortArrayElements(samples, data, JNI_ABORT);
            return probability;
        } catch (...) {
            env->ReleaseShortArrayElements(samples, data, JNI_ABORT);
            throw;
        }
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
        return 0.0f;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_input_voice_SileroVadNative_release(
        JNIEnv*, jobject, jlong pointer) {
    delete reinterpret_cast<SileroVad*>(pointer);
}
