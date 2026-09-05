# Fcitx5 Voice for Android

This fork adds hold-to-talk voice input to Fcitx5 for Android while keeping the
upstream keyboard and input engines intact.

## Use

1. Install the arm64 APK and enable **Fcitx5 Voice** as an Android input method.
2. Open **Keyboard settings → Voice input** and configure the applicable API key.
3. Hold the space bar to record, release to transcribe and commit, or swipe up
   before releasing to cancel.

The remote provider is selected from the device time zone: China time zones use
Zhipu `glm-asr-2512`; other time zones use OpenAI `gpt-audio-1.5`. Each request
contains the WAV recording, up to 8,000 characters before the cursor, and the
configured hotwords.

Local inference is enabled by default when the model is installed. Tap the local
model entry in Voice input settings to resume-download the 2.55 GiB input-only
subset of `taobao-mnn/Qwen2.5-Omni-3B-MNN`, pinned to model revision
`00dc2e9131a4bb325b43a47f4210dd6450116687`. MNN receives the original WAV in an
`<audio>` prompt together with the same editor context and hotwords. If the model
is absent, the app falls back to the regional remote provider.

API keys remain in Android app preferences. Audio is written to the app cache,
deleted after transcription, and is sent only to the selected engine.

## Build

Clone with submodules, install the Android SDK/NDK versions declared by the
project, then use the upstream Gradle build:

```sh
git clone --recursive <repository-url>
cd fcitx5-android-voice
BUILD_ABI=arm64-v8a ./gradlew :app:assembleDebug
```

MNN is pinned as a Git submodule. The application fork remains LGPL-2.1-or-later;
MNN retains its Apache-2.0 license.
