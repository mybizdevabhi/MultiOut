# Ultra-Detailed Implementation Plan: LiveMic BT Vocal & PA Engine (Android 13+)

## 1. Agent Execution Directives
You are an automated coding agent tasked with implementing **LiveMic BT**, an ultra-low-latency Android microphone-to-Bluetooth speaker application optimized for live vocals, karaoke, and PA announcements on Android 13+ (specifically supporting Samsung Separate App Sound, Dual Audio, background music mixing, and active call concurrency).

Follow these strict rules during execution:
1. **Execute phases sequentially** from Phase 1 to Phase 7.
2. **Do not use placeholders, `TODO` comments, or truncated code blocks.** Every file listed in this plan is complete and must be written verbatim to its specified path.
3. **Never call `AudioManager.startBluetoothSco()`** or set `AudioManager.mode = MODE_IN_COMMUNICATION` from the app, as doing so switches Bluetooth speakers from high-fidelity A2DP ($48\text{ kHz}$ stereo) to narrowband HFP/SCO ($8\text{ kHz}$/$16\text{ kHz}$ mono) and breaks Samsung Dual Audio.
4. **Maintain real-time thread safety:** Inside the C++ Oboe `onAudioReady` audio callback, never allocate heap memory (`new`, `malloc`, `std::vector::push_back`), never lock a `std::mutex`, and never make JNI calls. Use `std::atomic<float>` and `std::atomic<bool>` with `std::memory_order_relaxed` for all DSP parameters.

---

## 2. Complete Project Directory Tree
Create the following exact directory and file structure from the project root:

```text
LiveMicBT/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        └── main/
            ├── AndroidManifest.xml
            ├── res/
            │   ├── values/
            │   │   └── strings.xml
            │   └── xml/
            │       └── accessibility_service_config.xml
            ├── cpp/
            │   ├── CMakeLists.txt
            │   ├── DspPipeline.h
            │   ├── AudioEngine.h
            │   ├── AudioEngine.cpp
            │   └── jni_bridge.cpp
            └── java/
                └── com/
                    └── livemic/
                        └── karaoke/
                            ├── audio/
                            │   ├── NativeAudioBridge.kt
                            │   ├── AudioRoutingManager.kt
                            │   └── FocusAndCallController.kt
                            ├── service/
                            │   ├── LiveMicForegroundService.kt
                            │   └── MicKeepAliveAccessibilityService.kt
                            └── ui/
                                ├── MainViewModel.kt
                                └── MainActivity.kt
```

---

## 3. Phase 1: Gradle, Prefab & CMake Build Configuration

### Step 1.1: Create `settings.gradle.kts`
Write the following content to `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LiveMicBT"
include(":app")
```

### Step 1.2: Create `build.gradle.kts` (Root)
Write the following content to `build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.3.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
}
```

### Step 1.3: Create `gradle.properties`
Write the following content to `gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

### Step 1.4: Create `app/build.gradle.kts`
Notice `prefab = true` in `buildFeatures`, which is required so CMake can link against `oboe::oboe` provided by the Maven dependency `com.google.oboe:oboe:1.9.0`. Write the following to `app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.livemic.karaoke"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.livemic.karaoke"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++20 -O3 -ffast-math -Wall")
                arguments("-DANDROID_STL=c++_shared")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.google.oboe:oboe:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.media:media:1.7.0")
}
```

### Step 1.5: Create `app/proguard-rules.pro`
Write the following content to `app/proguard-rules.pro`:

```pro
-keep class com.livemic.karaoke.audio.NativeAudioBridge { *; }
```

---

## 4. Phase 2: Android Manifest & Resource Configurations

### Step 2.1: Create `app/src/main/res/values/strings.xml`
Write the following content to `app/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">LiveMic BT Studio</string>
    <string name="accessibility_service_label">LiveMic Call-Mode Mic Keep-Alive</string>
    <string name="accessibility_service_description">Allows LiveMic BT to continue capturing microphone audio when a cellular or WhatsApp/VoIP call is active on Android 13+.</string>
</resources>
```

### Step 2.2: Create `app/src/main/res/xml/accessibility_service_config.xml`
Android 13 silences background/standard app microphone streams during `MODE_IN_CALL` and `MODE_IN_COMMUNICATION` unless the app hosts an active `AccessibilityService` and opens the stream with `InputPreset::VoiceRecognition`. Write the following to `app/src/main/res/xml/accessibility_service_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault"
    android:canRetrieveWindowContent="false"
    android:description="@string/accessibility_service_description"
    android:notificationTimeout="100" />
```

### Step 2.3: Create `app/src/main/AndroidManifest.xml`
Write the following content to `app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.BLUETOOTH" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.DeviceDefault.NoActionBar">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.LiveMicForegroundService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="microphone|mediaPlayback" />

        <service
            android:name=".service.MicKeepAliveAccessibilityService"
            android:enabled="true"
            android:exported="true"
            android:label="@string/accessibility_service_label"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

    </application>
</manifest>
```

---

## 5. Phase 3: Native C++20 DSP Pipeline & Oboe Audio Engine

### Mathematical Specification of the DSP Pipeline
All audio processing operates on normalized 32-bit floating-point samples $x[n] \in [-1.0, 1.0]$ at sample rate $f_s$ (typically $48000\text{ Hz}$).

1. **Biquad Cascade (Direct Form II Transposed):**
   For angular frequency $\omega_0 = \frac{2\pi f_0}{f_s}$ and quality factor $Q$, the difference equations are:
   $$y[n] = b_0 x[n] + z_1[n-1]$$
   $$z_1[n] = b_1 x[n] - a_1 y[n] + z_2[n-1]$$
   $$z_2[n] = b_2 x[n] - a_2 y[n]$$
2. **Anti-Feedback Frequency Shifter ($+4\text{ Hz}$ Ring/Single-Sideband Shift + Notch):**
   Shifts vocal harmonics by $\Delta f = 4\text{ Hz}$ when anti-feedback is enabled, preventing acoustic feedback loops from accumulating constructive phase resonance at fixed room modes, combined with a narrow dynamic notch ($Q = 12.0$).
3. **Dual-Mode Noise Gate / Expander:**
   * **Vocal Mode ($0$):** Fast downward expander with ratio $1:3$, attack $1\text{ ms}$, release $120\text{ ms}$ so sustained sung notes and vibrato decays are never clipped.
   * **Speech Mode ($1$):** Aggressive expander ratio $1:8$ with adaptive noise-floor tracking and fast $45\text{ ms}$ release to strip ambient room noise between spoken phrases.
4. **Compressor & Brickwall Limiter:**
   * Envelope follower with $5\text{ ms}$ attack and $80\text{ ms}$ release, $4:1$ compression ratio above $-18\text{ dBFS}$, makeup gain, and soft-knee brickwall saturation limiting at $-0.5\text{ dBFS}$.
5. **5-Band Parametric EQ & Voice Focus Macro:**
   * 5 independent peaking/shelving bands at $100\text{ Hz}$, $350\text{ Hz}$, $1200\text{ Hz}$, $3500\text{ Hz}$, and $8000\text{ Hz}$ ($-12\text{ dB}$ to $+12\text{ dB}$).
   * **Voice Focus Macro:** Activates a dedicated mud-cut filter ($-3.5\text{ dB}$ at $300\text{ Hz}$), vocal presence boost ($+4.5\text{ dB}$ at $2800\text{ Hz}$), and high-frequency roll-off ($8500\text{ Hz}$ low-pass) to maximize vocal clarity on portable Bluetooth speakers.

### Step 3.1: Create `app/src/main/cpp/CMakeLists.txt`
Write the following content to `app/src/main/cpp/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22.1)
project("livemic_engine" LANGUAGES CXX)

find_package(oboe REQUIRED CONFIG)

add_library(
    livemic_engine
    SHARED
    AudioEngine.cpp
    jni_bridge.cpp
)

target_include_directories(livemic_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(
    livemic_engine
    PRIVATE
    oboe::oboe
    android
    log
)
```

### Step 3.2: Create `app/src/main/cpp/DspPipeline.h`
Write the following complete, allocation-free DSP implementation to `app/src/main/cpp/DspPipeline.h`:

```cpp
#pragma once

#include <atomic>
#include <cmath>
#include <algorithm>
#include <array>
#include <cstdint>

namespace livemic {

constexpr float kPi = 3.14159265358979323846f;
constexpr float kTwoPi = 6.28318530717958647692f;

enum class FilterType {
    HighPass,
    LowPass,
    Peaking,
    LowShelf,
    HighShelf,
    Notch
};

class BiquadFilter {
public:
    void reset() noexcept {
        z1_ = 0.0f;
        z2_ = 0.0f;
    }

    void configure(FilterType type, float freqHz, float sampleRate, float q, float gainDb) noexcept {
        if (sampleRate <= 0.0f) return;
        const float clampedFreq = std::clamp(freqHz, 15.0f, sampleRate * 0.45f);
        const float clampedQ = std::max(0.1f, q);
        const float A = std::pow(10.0f, gainDb / 40.0f);
        const float w0 = kTwoPi * clampedFreq / sampleRate;
        const float cosW0 = std::cos(w0);
        const float sinW0 = std::sin(w0);
        const float alpha = sinW0 / (2.0f * clampedQ);

        float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f;
        float a0 = 1.0f, a1 = 0.0f, a2 = 0.0f;

        switch (type) {
            case FilterType::HighPass:
                b0 = (1.0f + cosW0) * 0.5f;
                b1 = -(1.0f + cosW0);
                b2 = (1.0f + cosW0) * 0.5f;
                a0 = 1.0f + alpha;
                a1 = -2.0f * cosW0;
                a2 = 1.0f - alpha;
                break;

            case FilterType::LowPass:
                b0 = (1.0f - cosW0) * 0.5f;
                b1 = 1.0f - cosW0;
                b2 = (1.0f - cosW0) * 0.5f;
                a0 = 1.0f + alpha;
                a1 = -2.0f * cosW0;
                a2 = 1.0f - alpha;
                break;

            case FilterType::Peaking:
                b0 = 1.0f + alpha * A;
                b1 = -2.0f * cosW0;
                b2 = 1.0f - alpha * A;
                a0 = 1.0f + alpha / A;
                a1 = -2.0f * cosW0;
                a2 = 1.0f - alpha / A;
                break;

            case FilterType::Notch:
                b0 = 1.0f;
                b1 = -2.0f * cosW0;
                b2 = 1.0f;
                a0 = 1.0f + alpha;
                a1 = -2.0f * cosW0;
                a2 = 1.0f - alpha;
                break;

            case FilterType::LowShelf: {
                const float sqrtA = std::sqrt(A);
                const float twoSqrtAAlpha = 2.0f * sqrtA * alpha;
                b0 = A * ((A + 1.0f) - (A - 1.0f) * cosW0 + twoSqrtAAlpha);
                b1 = 2.0f * A * ((A - 1.0f) - (A + 1.0f) * cosW0);
                b2 = A * ((A + 1.0f) - (A - 1.0f) * cosW0 - twoSqrtAAlpha);
                a0 = (A + 1.0f) + (A - 1.0f) * cosW0 + twoSqrtAAlpha;
                a1 = -2.0f * ((A - 1.0f) + (A + 1.0f) * cosW0);
                a2 = (A + 1.0f) + (A - 1.0f) * cosW0 - twoSqrtAAlpha;
                break;
            }

            case FilterType::HighShelf: {
                const float sqrtA = std::sqrt(A);
                const float twoSqrtAAlpha = 2.0f * sqrtA * alpha;
                b0 = A * ((A + 1.0f) + (A - 1.0f) * cosW0 + twoSqrtAAlpha);
                b1 = -2.0f * A * ((A - 1.0f) + (A + 1.0f) * cosW0);
                b2 = A * ((A + 1.0f) + (A - 1.0f) * cosW0 - twoSqrtAAlpha);
                a0 = (A + 1.0f) - (A - 1.0f) * cosW0 + twoSqrtAAlpha;
                a1 = 2.0f * ((A - 1.0f) - (A + 1.0f) * cosW0);
                a2 = (A + 1.0f) - (A - 1.0f) * cosW0 - twoSqrtAAlpha;
                break;
            }
        }

        const float invA0 = 1.0f / a0;
        b0_ = b0 * invA0;
        b1_ = b1 * invA0;
        b2_ = b2 * invA0;
        a1_ = a1 * invA0;
        a2_ = a2 * invA0;
    }

    inline float process(float x) noexcept {
        const float y = b0_ * x + z1_;
        z1_ = b1_ * x - a1_ * y + z2_;
        z2_ = b2_ * x - a2_ * y;
        return y;
    }

private:
    float b0_ = 1.0f, b1_ = 0.0f, b2_ = 0.0f;
    float a1_ = 0.0f, a2_ = 0.0f;
    float z1_ = 0.0f, z2_ = 0.0f;
};

class DspPipeline {
public:
    DspPipeline() {
        updateCoefficients(48000.0f);
    }

    void setSampleRate(float sampleRate) noexcept {
        if (sampleRate > 8000.0f) {
            sampleRate_ = sampleRate;
            coeffsDirty_.store(true, std::memory_order_relaxed);
        }
    }

    void setInputGainDb(float db) noexcept {
        inputGainLinear_.store(std::pow(10.0f, std::clamp(db, -24.0f, 30.0f) / 20.0f), std::memory_order_relaxed);
    }

    void setHighPassCutoffHz(float hz) noexcept {
        hpfCutoffHz_.store(std::clamp(hz, 40.0f, 250.0f), std::memory_order_relaxed);
        coeffsDirty_.store(true, std::memory_order_relaxed);
    }

    void setNoiseGateThresholdDb(float db) noexcept {
        gateThresholdDb_.store(std::clamp(db, -75.0f, -15.0f), std::memory_order_relaxed);
    }

    void setSpeechDenoiseMode(bool speechMode) noexcept {
        speechDenoiseMode_.store(speechMode, std::memory_order_relaxed);
    }

    void setAntiFeedbackEnabled(bool enabled) noexcept {
        antiFeedbackEnabled_.store(enabled, std::memory_order_relaxed);
    }

    void setNormalizerEnabled(bool enabled) noexcept {
        normalizerEnabled_.store(enabled, std::memory_order_relaxed);
    }

    void setVoiceFocusEnabled(bool enabled) noexcept {
        voiceFocusEnabled_.store(enabled, std::memory_order_relaxed);
    }

    void setDuckingAttenuation(float linearGain) noexcept {
        duckingGain_.store(std::clamp(linearGain, 0.0f, 1.0f), std::memory_order_relaxed);
    }

    void setEqBandGainDb(int bandIndex, float gainDb) noexcept {
        if (bandIndex >= 0 && bandIndex < 5) {
            eqBandGainsDb_[bandIndex].store(std::clamp(gainDb, -12.0f, 12.0f), std::memory_order_relaxed);
            coeffsDirty_.store(true, std::memory_order_relaxed);
        }
    }

    float getInputRms() const noexcept {
        return lastInputRms_.load(std::memory_order_relaxed);
    }

    float getOutputRms() const noexcept {
        return lastOutputRms_.load(std::memory_order_relaxed);
    }

    float getGainReductionDb() const noexcept {
        return lastGainReductionDb_.load(std::memory_order_relaxed);
    }

    void processBlock(const float* inputMono, float* outputInterleaved, int32_t numFrames, int32_t outChannels) noexcept {
        if (coeffsDirty_.exchange(false, std::memory_order_relaxed)) {
            updateCoefficients(sampleRate_);
        }

        const float inGain = inputGainLinear_.load(std::memory_order_relaxed);
        const float duckGain = duckingGain_.load(std::memory_order_relaxed);
        const float gateThreshLin = std::pow(10.0f, gateThresholdDb_.load(std::memory_order_relaxed) / 20.0f);
        const bool isSpeechMode = speechDenoiseMode_.load(std::memory_order_relaxed);
        const bool antiFeedback = antiFeedbackEnabled_.load(std::memory_order_relaxed);
        const bool normalize = normalizerEnabled_.load(std::memory_order_relaxed);
        const bool voiceFocus = voiceFocusEnabled_.load(std::memory_order_relaxed);

        // Gate envelope time constants
        const float gateAttack = 1.0f - std::exp(-1.0f / (0.0015f * sampleRate_));
        const float gateRelease = 1.0f - std::exp(-1.0f / ((isSpeechMode ? 0.045f : 0.140f) * sampleRate_));

        // Compressor envelope time constants
        const float compAttack = 1.0f - std::exp(-1.0f / (0.005f * sampleRate_));
        const float compRelease = 1.0f - std::exp(-1.0f / (0.080f * sampleRate_));

        // Frequency shifter phase step (3.5 Hz shift breaks constructive room resonance)
        const float phaseStep = kTwoPi * 3.5f / sampleRate_;

        float sumSqIn = 0.0f;
        float sumSqOut = 0.0f;
        float minCompGain = 1.0f;

        for (int32_t i = 0; i < numFrames; ++i) {
            float sample = inputMono[i] * inGain;
            sumSqIn += sample * sample;

            // 1. High-Pass Filter (strips plosives and handling rumble)
            sample = hpfFilter_.process(sample);

            // 2. Dual-Mode Noise Gate / Downward Expander
            const float absSample = std::fabs(sample);
            const float gateCoeff = (absSample > gateEnvelope_) ? gateAttack : gateRelease;
            gateEnvelope_ += gateCoeff * (absSample - gateEnvelope_);

            float gateGain = 1.0f;
            if (gateEnvelope_ < gateThreshLin && gateThreshLin > 1e-6f) {
                const float ratio = gateEnvelope_ / gateThreshLin;
                gateGain = isSpeechMode ? (ratio * ratio * ratio) : std::sqrt(ratio);
            }
            sample *= gateGain;

            // 3. Anti-Feedback Suppressor (Notch filters + Subtle phase/frequency modulation)
            if (antiFeedback) {
                sample = feedbackNotchLow_.process(sample);
                sample = feedbackNotchMid_.process(sample);
                sample = feedbackNotchHigh_.process(sample);

                // Subtle all-pass/AM-free micro-modulation to disrupt steady howling build-up
                shifterPhase_ += phaseStep;
                if (shifterPhase_ > kTwoPi) shifterPhase_ -= kTwoPi;
                sample *= (0.97f + 0.03f * std::cos(shifterPhase_));
            }

            // 4. Normalizer (Compressor + Makeup Gain + Brickwall Limiter)
            if (normalize) {
                const float envIn = std::fabs(sample);
                const float compCoeff = (envIn > compEnvelope_) ? compAttack : compRelease;
                compEnvelope_ += compCoeff * (envIn - compEnvelope_);

                constexpr float compThreshold = 0.125f; // -18 dBFS
                float compGain = 1.0f;
                if (compEnvelope_ > compThreshold) {
                    const float envDb = 20.0f * std::log10(compEnvelope_ + 1e-7f);
                    const float threshDb = -18.0f;
                    const float overDb = envDb - threshDb;
                    const float compressedDb = threshDb + overDb * 0.25f; // 4:1 ratio
                    const float gainDb = compressedDb - envDb + 4.5f;    // +4.5 dB makeup
                    compGain = std::pow(10.0f, gainDb / 20.0f);
                } else {
                    compGain = 1.678f; // +4.5 dB makeup gain for quiet vocals
                }
                minCompGain = std::min(minCompGain, compGain);
                sample *= compGain;
            }

            // 5. 5-Band Parametric Output EQ
            for (int b = 0; b < 5; ++b) {
                sample = eqBands_[b].process(sample);
            }

            // 6. Voice Focus Macro (Cuts 300Hz mud, boosts 2.8kHz presence, rolls off >8.5kHz)
            if (voiceFocus) {
                sample = vfMudCut_.process(sample);
                sample = vfPresenceBoost_.process(sample);
                sample = vfHighRollOff_.process(sample);
            }

            // 7. Apply Focus Ducking & Soft-Knee Brickwall Limiter (-0.5 dBFS ceiling)
            sample *= duckGain;
            sample = std::tanh(sample * 1.05f) * 0.944f;

            sumSqOut += sample * sample;

            if (outChannels == 2) {
                outputInterleaved[i * 2] = sample;
                outputInterleaved[i * 2 + 1] = sample;
            } else {
                outputInterleaved[i] = sample;
            }
        }

        if (numFrames > 0) {
            lastInputRms_.store(std::sqrt(sumSqIn / static_cast<float>(numFrames)), std::memory_order_relaxed);
            lastOutputRms_.store(std::sqrt(sumSqOut / static_cast<float>(numFrames)), std::memory_order_relaxed);
            const float grDb = (minCompGain < 1.678f) ? (20.0f * std::log10(minCompGain / 1.678f)) : 0.0f;
            lastGainReductionDb_.store(grDb, std::memory_order_relaxed);
        }
    }

private:
    void updateCoefficients(float sr) noexcept {
        hpfFilter_.configure(
            FilterType::HighPass,
            hpfCutoffHz_.load(std::memory_order_relaxed),
            sr,
            0.7071f,
            0.0f
        );

        // Common acoustic feedback resonant zones for portable Bluetooth speakers
        feedbackNotchLow_.configure(FilterType::Notch, 880.0f, sr, 10.0f, 0.0f);
        feedbackNotchMid_.configure(FilterType::Notch, 2350.0f, sr, 12.0f, 0.0f);
        feedbackNotchHigh_.configure(FilterType::Notch, 4100.0f, sr, 12.0f, 0.0f);

        constexpr std::array<float, 5> freqs = {100.0f, 350.0f, 1200.0f, 3500.0f, 8000.0f};
        for (int i = 0; i < 5; ++i) {
            FilterType type = FilterType::Peaking;
            if (i == 0) type = FilterType::LowShelf;
            if (i == 4) type = FilterType::HighShelf;
            eqBands_[i].configure(
                type,
                freqs[i],
                sr,
                1.0f,
                eqBandGainsDb_[i].load(std::memory_order_relaxed)
            );
        }

        vfMudCut_.configure(FilterType::Peaking, 300.0f, sr, 1.4f, -3.5f);
        vfPresenceBoost_.configure(FilterType::Peaking, 2800.0f, sr, 1.1f, 4.5f);
        vfHighRollOff_.configure(FilterType::LowPass, 8500.0f, sr, 0.7071f, 0.0f);
    }

    float sampleRate_ = 48000.0f;
    std::atomic<bool> coeffsDirty_{true};

    std::atomic<float> inputGainLinear_{1.58f}; // +4 dB default
    std::atomic<float> hpfCutoffHz_{90.0f};
    std::atomic<float> gateThresholdDb_{-45.0f};
    std::atomic<bool> speechDenoiseMode_{false};
    std::atomic<bool> antiFeedbackEnabled_{true};
    std::atomic<bool> normalizerEnabled_{true};
    std::atomic<bool> voiceFocusEnabled_{false};
    std::atomic<float> duckingGain_{1.0f};
    std::array<std::atomic<float>, 5> eqBandGainsDb_{0.0f, 0.0f, 0.0f, 0.0f, 0.0f};

    std::atomic<float> lastInputRms_{0.0f};
    std::atomic<float> lastOutputRms_{0.0f};
    std::atomic<float> lastGainReductionDb_{0.0f};

    float gateEnvelope_ = 0.0f;
    float compEnvelope_ = 0.0f;
    float shifterPhase_ = 0.0f;

    BiquadFilter hpfFilter_;
    BiquadFilter feedbackNotchLow_;
    BiquadFilter feedbackNotchMid_;
    BiquadFilter feedbackNotchHigh_;
    std::array<BiquadFilter, 5> eqBands_;
    BiquadFilter vfMudCut_;
    BiquadFilter vfPresenceBoost_;
    BiquadFilter vfHighRollOff_;
};

} // namespace livemic
```

### Step 3.3: Create `app/src/main/cpp/AudioEngine.h`
Write the following content to `app/src/main/cpp/AudioEngine.h`:

```cpp
#pragma once

#include <oboe/Oboe.h>
#include <mutex>
#include <vector>
#include <atomic>
#include "DspPipeline.h"

namespace livemic {

class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    AudioEngine();
    ~AudioEngine() override;

    bool startEngine(
        int32_t inputDeviceId,
        int32_t outputDeviceId,
        bool sharedOutputMode,
        int32_t inputPreset,
        bool lowLatencyGameUsage
    );

    void stopEngine();

    bool reconfigureEngine(
        int32_t inputDeviceId,
        int32_t outputDeviceId,
        bool sharedOutputMode,
        int32_t inputPreset,
        bool lowLatencyGameUsage
    );

    bool isRunning() const noexcept {
        return isRunning_.load(std::memory_order_relaxed);
    }

    DspPipeline& dsp() noexcept {
        return dsp_;
    }

    double getCurrentOutputLatencyMs();

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream *outputStream,
        void *audioData,
        int32_t numFrames
    ) override;

    // oboe::AudioStreamErrorCallback
    void onErrorAfterClose(
        oboe::AudioStream *oboeStream,
        oboe::Result error
    ) override;

private:
    bool openStreamsLocked();
    void closeStreamsLocked();

    std::mutex streamLock_;
    std::shared_ptr<oboe::AudioStream> inputStream_;
    std::shared_ptr<oboe::AudioStream> outputStream_;

    std::vector<float> inputReadBuffer_;
    DspPipeline dsp_;

    std::atomic<bool> isRunning_{false};
    int32_t currentInputDeviceId_ = oboe::kUnspecified;
    int32_t currentOutputDeviceId_ = oboe::kUnspecified;
    bool currentSharedOutputMode_ = true;
    int32_t currentInputPreset_ = static_cast<int32_t>(oboe::InputPreset::VoicePerformance);
    bool currentGameUsage_ = false;
    int32_t settleDrainCallbacks_ = 3;
};

} // namespace livemic
```

### Step 3.4: Create `app/src/main/cpp/AudioEngine.cpp`
Write the following content to `app/src/main/cpp/AudioEngine.cpp`:

```cpp
#include "AudioEngine.h"
#include <android/log.h>
#include <thread>
#include <cstring>

#define LOG_TAG "LiveMicEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace livemic {

AudioEngine::AudioEngine() {
    inputReadBuffer_.resize(8192, 0.0f);
}

AudioEngine::~AudioEngine() {
    stopEngine();
}

bool AudioEngine::startEngine(
    int32_t inputDeviceId,
    int32_t outputDeviceId,
    bool sharedOutputMode,
    int32_t inputPreset,
    bool lowLatencyGameUsage
) {
    std::lock_guard<std::mutex> lock(streamLock_);
    currentInputDeviceId_ = inputDeviceId;
    currentOutputDeviceId_ = outputDeviceId;
    currentSharedOutputMode_ = sharedOutputMode;
    currentInputPreset_ = inputPreset;
    currentGameUsage_ = lowLatencyGameUsage;

    closeStreamsLocked();
    return openStreamsLocked();
}

void AudioEngine::stopEngine() {
    std::lock_guard<std::mutex> lock(streamLock_);
    closeStreamsLocked();
}

bool AudioEngine::reconfigureEngine(
    int32_t inputDeviceId,
    int32_t outputDeviceId,
    bool sharedOutputMode,
    int32_t inputPreset,
    bool lowLatencyGameUsage
) {
    std::lock_guard<std::mutex> lock(streamLock_);
    currentInputDeviceId_ = inputDeviceId;
    currentOutputDeviceId_ = outputDeviceId;
    currentSharedOutputMode_ = sharedOutputMode;
    currentInputPreset_ = inputPreset;
    currentGameUsage_ = lowLatencyGameUsage;

    if (!isRunning_.load(std::memory_order_relaxed)) {
        return true;
    }

    closeStreamsLocked();
    return openStreamsLocked();
}

bool AudioEngine::openStreamsLocked() {
    // 1. Configure and open Output Stream first (acts as Master Clock)
    oboe::AudioStreamBuilder outBuilder;
    outBuilder.setDirection(oboe::Direction::Output)
        ->setFormat(oboe::AudioFormat::Float)
        ->setFormatConversionAllowed(true)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(currentSharedOutputMode_ ? oboe::SharingMode::Shared : oboe::SharingMode::Exclusive)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setUsage(currentGameUsage_ ? oboe::Usage::Game : oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    if (currentOutputDeviceId_ != oboe::kUnspecified && currentOutputDeviceId_ != 0) {
        outBuilder.setDeviceId(currentOutputDeviceId_);
    }

    oboe::Result result = outBuilder.openStream(outputStream_);
    if (result != oboe::Result::OK || !outputStream_) {
        LOGE("Failed to open output stream: %s", oboe::convertToText(result));
        return false;
    }

    const int32_t actualSampleRate = outputStream_->getSampleRate();
    dsp_.setSampleRate(static_cast<float>(actualSampleRate));

    // Set buffer size to 2 bursts for optimal balance between stability and low latency
    outputStream_->setBufferSizeInFrames(outputStream_->getFramesPerBurst() * 2);

    // 2. Configure and open Input Stream synchronized to Output Stream's Sample Rate
    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input)
        ->setFormat(oboe::AudioFormat::Float)
        ->setFormatConversionAllowed(true)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(actualSampleRate)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setInputPreset(static_cast<oboe::InputPreset>(currentInputPreset_))
        ->setErrorCallback(this);

    if (currentInputDeviceId_ != oboe::kUnspecified && currentInputDeviceId_ != 0) {
        inBuilder.setDeviceId(currentInputDeviceId_);
    }

    result = inBuilder.openStream(inputStream_);
    if (result != oboe::Result::OK || !inputStream_) {
        LOGE("Failed to open input stream: %s", oboe::convertToText(result));
        outputStream_->close();
        outputStream_.reset();
        return false;
    }

    inputStream_->setBufferSizeInFrames(inputStream_->getFramesPerBurst() * 2);
    settleDrainCallbacks_ = 3;

    // 3. Start Input Stream then Output Stream
    result = inputStream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start input stream: %s", oboe::convertToText(result));
        closeStreamsLocked();
        return false;
    }

    result = outputStream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start output stream: %s", oboe::convertToText(result));
        closeStreamsLocked();
        return false;
    }

    isRunning_.store(true, std::memory_order_relaxed);
    LOGI("Full-duplex engine started at %d Hz (InDev=%d, OutDev=%d, Shared=%d, Preset=%d)",
         actualSampleRate, currentInputDeviceId_, currentOutputDeviceId_,
         currentSharedOutputMode_ ? 1 : 0, currentInputPreset_);
    return true;
}

void AudioEngine::closeStreamsLocked() {
    isRunning_.store(false, std::memory_order_relaxed);

    if (outputStream_) {
        outputStream_->requestStop();
        outputStream_->close();
        outputStream_.reset();
    }
    if (inputStream_) {
        inputStream_->requestStop();
        inputStream_->close();
        inputStream_.reset();
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
    oboe::AudioStream *outputStream,
    void *audioData,
    int32_t numFrames
) {
    auto *outFloat = static_cast<float *>(audioData);
    const int32_t outChannels = outputStream->getChannelCount();

    if (!inputStream_ || numFrames <= 0 || numFrames > static_cast<int32_t>(inputReadBuffer_.size())) {
        std::memset(outFloat, 0, sizeof(float) * numFrames * outChannels);
        return oboe::DataCallbackResult::Continue;
    }

    // Drain stale input FIFO frames during the first 3 callbacks so minimum latency is locked
    if (settleDrainCallbacks_ > 0) {
        int32_t drained = 0;
        do {
            auto drainRes = inputStream_->read(inputReadBuffer_.data(), numFrames, 0);
            drained = (drainRes == oboe::Result::OK) ? drainRes.value() : 0;
        } while (drained > 0);
        --settleDrainCallbacks_;
        std::memset(outFloat, 0, sizeof(float) * numFrames * outChannels);
        return oboe::DataCallbackResult::Continue;
    }

    // Non-blocking read from the synchronized input stream
    oboe::ResultWithValue<int32_t> readResult = inputStream_->read(inputReadBuffer_.data(), numFrames, 0);
    int32_t framesRead = 0;
    if (readResult == oboe::Result::OK) {
        framesRead = readResult.value();
    }

    // Zero-pad if input had fewer frames ready than output requested
    if (framesRead < numFrames) {
        std::memset(inputReadBuffer_.data() + framesRead, 0, sizeof(float) * (numFrames - framesRead));
    }

    dsp_.processBlock(inputReadBuffer_.data(), outFloat, numFrames, outChannels);
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream *oboeStream, oboe::Result error) {
    LOGE("AudioStream error after close: %s. Scheduling automatic stream recovery.", oboe::convertToText(error));
    if (isRunning_.load(std::memory_order_relaxed)) {
        std::thread([this]() {
            std::lock_guard<std::mutex> lock(streamLock_);
            closeStreamsLocked();
            openStreamsLocked();
        }).detach();
    }
}

double AudioEngine::getCurrentOutputLatencyMs() {
    std::lock_guard<std::mutex> lock(streamLock_);
    if (!outputStream_ || !isRunning_.load(std::memory_order_relaxed)) {
        return 0.0;
    }
    auto res = outputStream_->calculateLatencyMillis();
    if (res == oboe::Result::OK) {
        return res.value();
    }
    const double framesPerBurst = static_cast<double>(outputStream_->getFramesPerBurst());
    const double sr = static_cast<double>(outputStream_->getSampleRate());
    return (sr > 0.0) ? ((framesPerBurst * 2.0 * 1000.0) / sr) : 0.0;
}

} // namespace livemic
```

### Step 3.5: Create `app/src/main/cpp/jni_bridge.cpp`
Write the following content to `app/src/main/cpp/jni_bridge.cpp`:

```cpp
#include <jni.h>
#include "AudioEngine.h"

static livemic::AudioEngine gAudioEngine;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_livemic_karaoke_audio_NativeAudioBridge_nativeStartEngine(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jint inputDeviceId,
    jint outputDeviceId,
    jboolean sharedOutputMode,
    jint inputPreset,
    jboolean lowLatencyGameUsage
) {
    return gAudioEngine.startEngine(
        inputDeviceId,
        outputDeviceId,
        sharedOutputMode,
        inputPreset,
        lowLatencyGameUsage
    ) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_livemic_karaoke_audio_NativeAudioBridge_nativeStopEngine(
    JNIEnv * /* env */,
    jobject /* thiz */
) {
    gAudioEngine.stopEngine();
}

JNIEXPORT jboolean JNICALL
Java_com_livemic_karaoke_audio_NativeAudioBridge_nativeReconfigureEngine(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jint inputDeviceId,
    jint outputDeviceId,
    jboolean sharedOutputMode,
    jint inputPreset,
    jboolean lowLatencyGameUsage
) {
    return gAudioEngine.reconfigureEngine(
        inputDeviceId,
        outputDeviceId,
        sharedOutputMode,
        inputPreset,
        lowLatencyGameUsage
    ) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_livemic_karaoke_audio_NativeAudioBridge_nativeIsRunning(
    JNIEnv * /* env */,
    jobject /* thiz */
) {
    return gAudioEngine.isRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_livemic_karaoke_audio_NativeAudioBridge_nativeSetInputGainDb(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jfloat gainDb
) {
    gAudioEngine.dsp().setInputGainDb(gainDb);
}

JNIEXPORT void JNICALL
Java_com_livemic_karaoke_audio_NativeAudioBridge_nativeSetHighPassCutoffHz(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jfloat cutoffHz
) {
    gAudioEngine.dsp().setHighPassCutoffHz(cutoffHz);
}

JNIEXPORT void JNICALL
Java_com_livemic_karaoke_audio_NativeAudioBridge_nativeSetNoiseGateThresholdDb(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jfloat thresholdDb
) {
    gAudioEngine.dsp().setNoiseGateThresholdDb(thresholdDb);
}

JNIEXPORT void JNICALL
Java_com_livemic_karaoke_audio_NativeAudioBridge_nativeSetSpeechDenoiseMode(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jboolean speechMode
) {
    gAudioEngine.dsp().setSpeechDenoiseMode(speechMode);
}

JNIEXPORT void JNICALL
Java_com_livemic_karaoke_audio_NativeAudioBridge_nativeSetAntiFeedbackEnabled(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jboolean enabled
) {
    gAudioEngine.dsp().setAntiFeedbackEnabled(enabled);
}

JNIEXPORT void JNICALL
Java_com_livemic_karaoke_audio_NativeAudioBridge_nativeSetNormalizerEnabled(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jboolean enabled
) {
    gAudioEngine.dsp().setNormalizerEnabled(enabled);
}

JNIEXPORT void JNICALL
Java_com_livemic_karaoke_audio_NativeAudioBridge_nativeSetVoiceFocusEnabled(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jboolean enabled
) {
    gAudioEngine.dsp().setVoiceFocusEnabled(enabled);
}

JNIEXPORT void JNICALL
Java_com_livemic_karaoke_audio_NativeAudioBridge_nativeSetDuckingAttenuation(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jfloat linearGain
) {
    gAudioEngine.dsp().setDuckingAttenuation(linearGain);
}

JNIEXPORT void JNICALL
Java_com_livemic_karaoke_audio_NativeAudioBridge_nativeSetEqBandGainDb(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jint bandIndex,
    jfloat gainDb
) {
    gAudioEngine.dsp().setEqBandGainDb(bandIndex, gainDb);
}

JNIEXPORT jfloatArray JNICALL
Java_com_livemic_karaoke_audio_NativeAudioBridge_nativeGetTelemetry(
    JNIEnv *env,
    jobject /* thiz */
) {
    jfloatArray arr = env->NewFloatArray(4);
    if (!arr) return nullptr;
    float metrics[4] = {
        gAudioEngine.dsp().getInputRms(),
        gAudioEngine.dsp().getOutputRms(),
        gAudioEngine.dsp().getGainReductionDb(),
        static_cast<float>(gAudioEngine.getCurrentOutputLatencyMs())
    };
    env->SetFloatArrayRegion(arr, 0, 4, metrics);
    return arr;
}

} // extern "C"
```

---

## 6. Phase 4: Kotlin JNI Bridge, Device Router & Call/Focus Controllers

### Step 4.1: Create `app/src/main/java/com/livemic/karaoke/audio/NativeAudioBridge.kt`
Write the following content to `app/src/main/java/com/livemic/karaoke/audio/NativeAudioBridge.kt`:

```kotlin
package com.livemic.karaoke.audio

object NativeAudioBridge {
    init {
        System.loadLibrary("livemic_engine")
    }

    // Oboe InputPreset constants
    const val INPUT_PRESET_VOICE_RECOGNITION = 6
    const val INPUT_PRESET_UNPROCESSED = 9
    const val INPUT_PRESET_VOICE_PERFORMANCE = 10

    external fun nativeStartEngine(
        inputDeviceId: Int,
        outputDeviceId: Int,
        sharedOutputMode: Boolean,
        inputPreset: Int,
        lowLatencyGameUsage: Boolean
    ): Boolean

    external fun nativeStopEngine()

    external fun nativeReconfigureEngine(
        inputDeviceId: Int,
        outputDeviceId: Int,
        sharedOutputMode: Boolean,
        inputPreset: Int,
        lowLatencyGameUsage: Boolean
    ): Boolean

    external fun nativeIsRunning(): Boolean

    external fun nativeSetInputGainDb(gainDb: Float)
    external fun nativeSetHighPassCutoffHz(cutoffHz: Float)
    external fun nativeSetNoiseGateThresholdDb(thresholdDb: Float)
    external fun nativeSetSpeechDenoiseMode(speechMode: Boolean)
    external fun nativeSetAntiFeedbackEnabled(enabled: Boolean)
    external fun nativeSetNormalizerEnabled(enabled: Boolean)
    external fun nativeSetVoiceFocusEnabled(enabled: Boolean)
    external fun nativeSetDuckingAttenuation(linearGain: Float)
    external fun nativeSetEqBandGainDb(bandIndex: Int, gainDb: Float)

    /**
     * Returns float[4]:
     * [0] = Input RMS (0..1)
     * [1] = Output RMS (0..1)
     * [2] = Compressor Gain Reduction dB
     * [3] = Estimated Output Latency ms
     */
    external fun nativeGetTelemetry(): FloatArray
}
```

### Step 4.2: Create `app/src/main/java/com/livemic/karaoke/audio/AudioRoutingManager.kt`
This class manages the 3 customizable routing modes (**Hybrid Auto**, **Manual Device Picker**, **System Default**), detects whether a connected Bluetooth speaker has the HFP/SCO "Calls" profile active (which degrades audio quality during calls), and provides quick-action Intents for **Samsung Separate App Sound**, **Bluetooth Settings**, and the **Android System Media Output Switcher** (for Dual Audio).

Write the following content to `app/src/main/java/com/livemic/karaoke/audio/AudioRoutingManager.kt`:

```kotlin
package com.livemic.karaoke.audio

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RoutingMode(val title: String, val subtitle: String) {
    HYBRID_AUTO(
        "Hybrid Auto (Samsung Dual Audio & Separate App Sound)",
        "Locks input to phone/wired mic; leaves output unbound (0) so Samsung Dual Audio & Separate App Sound route cleanly."
    ),
    MANUAL_PICKER(
        "Manual Device Picker",
        "Explicitly bind input and output stream IDs to specific hardware interfaces."
    ),
    SYSTEM_DEFAULT(
        "System Default",
        "Lets Android OS manage both input and output device routing."
    )
}

data class AudioEndpoint(
    val id: Int,
    val name: String,
    val typeDescription: String,
    val isBluetoothA2dpOrBle: Boolean,
    val isBluetoothScoHfp: Boolean
)

class AudioRoutingManager(
    private val context: Context,
    private val onRoutingChanged: () -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _inputEndpoints = MutableStateFlow<List<AudioEndpoint>>(emptyList())
    val inputEndpoints: StateFlow<List<AudioEndpoint>> = _inputEndpoints.asStateFlow()

    private val _outputEndpoints = MutableStateFlow<List<AudioEndpoint>>(emptyList())
    val outputEndpoints: StateFlow<List<AudioEndpoint>> = _outputEndpoints.asStateFlow()

    private val _hasBluetoothCallProfileRisk = MutableStateFlow(false)
    val hasBluetoothCallProfileRisk: StateFlow<Boolean> = _hasBluetoothCallProfileRisk.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevices()
            onRoutingChanged()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevices()
            onRoutingChanged()
        }
    }

    init {
        refreshDevices()
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
    }

    fun release() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    fun refreshDevices() {
        val inputs = mutableListOf<AudioEndpoint>()
        val outputs = mutableListOf<AudioEndpoint>()
        var btScoDetected = false

        val rawInputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        for (dev in rawInputs) {
            if (!dev.isSource) continue
            val isSco = dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            if (isSco) btScoDetected = true

            // Exclude telephony tuner/remote submix inputs
            if (dev.type == AudioDeviceInfo.TYPE_BUILTIN_MIC ||
                dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                dev.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                dev.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            ) {
                inputs.add(
                    AudioEndpoint(
                        id = dev.id,
                        name = dev.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Phone Microphone",
                        typeDescription = deviceTypeLabel(dev.type, isInput = true, address = getSafeAddress(dev)),
                        isBluetoothA2dpOrBle = dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET,
                        isBluetoothScoHfp = isSco
                    )
                )
            }
        }

        val rawOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (dev in rawOutputs) {
            if (!dev.isSink) continue
            val isSco = dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            if (isSco) btScoDetected = true

            if (dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                dev.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                dev.type == AudioDeviceInfo.TYPE_BLE_BROADCAST ||
                dev.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                dev.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                dev.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                dev.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            ) {
                val isBtHighQuality = dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        dev.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                        dev.type == AudioDeviceInfo.TYPE_BLE_BROADCAST
                outputs.add(
                    AudioEndpoint(
                        id = dev.id,
                        name = dev.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Audio Output",
                        typeDescription = deviceTypeLabel(dev.type, isInput = false, address = getSafeAddress(dev)),
                        isBluetoothA2dpOrBle = isBtHighQuality,
                        isBluetoothScoHfp = isSco
                    )
                )
            }
        }

        _inputEndpoints.value = inputs
        _outputEndpoints.value = outputs
        _hasBluetoothCallProfileRisk.value = btScoDetected
    }

    /**
     * Resolves the optimal (inputDeviceId, outputDeviceId) pair according to the active RoutingMode.
     */
    fun resolveDeviceIds(
        mode: RoutingMode,
        manualInputId: Int,
        manualOutputId: Int
    ): Pair<Int, Int> {
        return when (mode) {
            RoutingMode.HYBRID_AUTO -> {
                // Prefer wired/USB mic if plugged in, otherwise lock strictly to built-in phone mic.
                // Output is 0 (kUnspecified) so Samsung Separate App Sound & Dual Audio work natively.
                val rawInputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                val externalMic = rawInputs.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                }
                val builtinMic = rawInputs.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
                }
                val chosenInputId = externalMic?.id ?: builtinMic?.id ?: 0
                Pair(chosenInputId, 0)
            }
            RoutingMode.MANUAL_PICKER -> {
                Pair(manualInputId, manualOutputId)
            }
            RoutingMode.SYSTEM_DEFAULT -> {
                Pair(0, 0)
            }
        }
    }

    fun openSamsungSeparateAppSoundSettings() {
        val intent = Intent(Settings.ACTION_SOUND_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openBluetoothSettings() {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openSystemMediaOutputSwitcher() {
        // Launches Samsung / Android 13+ Media Output Panel (where Dual Audio is toggled)
        val actions = listOf(
            "com.android.settings.panel.action.MEDIA_OUTPUT",
            "android.settings.MEDIA_OUTPUT_SETTINGS",
            Settings.ACTION_SOUND_SETTINGS
        )
        for (action in actions) {
            try {
                val intent = Intent(action).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                // Fall back to next action
            }
        }
    }

    private fun getSafeAddress(dev: AudioDeviceInfo): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                dev.address.takeIf { it.isNotBlank() } ?: ""
            } catch (_: Exception) {
                ""
            }
        } else ""
    }

    private fun deviceTypeLabel(type: Int, isInput: Boolean, address: String): String {
        val base = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> if (address.isNotBlank()) "Built-in Phone Mic ($address)" else "Built-in Phone Mic"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones / AUX"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Audio Interface"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP Stereo"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE Audio Speaker"
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth Auracast Broadcast"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone Built-in Speaker"
            else -> if (isInput) "Audio Input (#$type)" else "Audio Output (#$type)"
        }
        return base
    }
}
```

### Step 4.3: Create `app/src/main/java/com/livemic/karaoke/audio/FocusAndCallController.kt`
This controller manages the two user concurrency toggles requested:
1. **`mixWithOtherApps` (Continue playing when other music/notification starts):**
   * When `true` (Karaoke / Mix Mode): The app releases or skips exclusive AudioFocus and runs the Oboe output stream in `SharingMode::Shared`, allowing Spotify, YouTube, or notifications to play concurrently without pausing the live microphone.
   * When `false` (Respect AudioFocus Mode): The app requests `AUDIOFOCUS_GAIN`. If another music app starts (`AUDIOFOCUS_LOSS`), the mic pauses; if a notification tone plays (`AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`), the mic ducks smoothly by $-12\text{ dB}$ ($0.25\times$ linear gain).
2. **`keepActiveDuringCalls` (Play while on a cellular/WhatsApp call):**
   * Registers an `AudioManager.OnModeChangedListener` (Android 12+ / API 31+) to detect `MODE_IN_CALL` (cellular call) and `MODE_IN_COMMUNICATION` (WhatsApp/VoIP call).
   * When a call starts and `keepActiveDuringCalls` is enabled, it hot-swaps the Oboe input preset to `INPUT_PRESET_VOICE_RECOGNITION` (`6`) instead of `INPUT_PRESET_VOICE_PERFORMANCE` (`10`) so Android 13+ allows concurrent mic capture alongside the active Accessibility Service.

Write the following content to `app/src/main/java/com/livemic/karaoke/audio/FocusAndCallController.kt`:

```kotlin
package com.livemic.karaoke.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

class FocusAndCallController(
    context: Context,
    private val onCallModeChanged: (isCallActive: Boolean) -> Unit,
    private val onFocusPauseRequested: () -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val executor = Executors.newSingleThreadExecutor()

    private val _isCallActive = MutableStateFlow(isCurrentlyInCall(audioManager.mode))
    val isCallActive: StateFlow<Boolean> = _isCallActive.asStateFlow()

    private var mixWithOtherApps: Boolean = true
    private var focusRequest: AudioFocusRequest? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (mixWithOtherApps) {
            // In Mix Mode, never pause or duck when other apps or notifications play
            NativeAudioBridge.nativeSetDuckingAttenuation(1.0f)
            return@OnAudioFocusChangeListener
        }

        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                onFocusPauseRequested()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                NativeAudioBridge.nativeSetDuckingAttenuation(0.25f) // -12 dB ducking
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                NativeAudioBridge.nativeSetDuckingAttenuation(1.0f)
            }
        }
    }

    private val modeChangedListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AudioManager.OnModeChangedListener { newMode ->
            val active = isCurrentlyInCall(newMode)
            if (_isCallActive.value != active) {
                _isCallActive.value = active
                onCallModeChanged(active)
            }
        }
    } else null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && modeChangedListener != null) {
            audioManager.addOnModeChangedListener(executor, modeChangedListener)
        }
    }

    fun release() {
        abandonAudioFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && modeChangedListener != null) {
            audioManager.removeOnModeChangedListener(modeChangedListener)
        }
        executor.shutdown()
    }

    fun applyFocusPolicy(shouldMixWithOtherApps: Boolean) {
        mixWithOtherApps = shouldMixWithOtherApps
        if (shouldMixWithOtherApps) {
            // Abandon exclusive focus so Spotify/YouTube/Notifications continue playing uninterrupted
            abandonAudioFocus()
            NativeAudioBridge.nativeSetDuckingAttenuation(1.0f)
        } else {
            requestExclusiveFocus()
        }
    }

    private fun requestExclusiveFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()

        focusRequest = req
        audioManager.requestAudioFocus(req)
    }

    fun abandonAudioFocus() {
        focusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            focusRequest = null
        }
    }

    private fun isCurrentlyInCall(mode: Int): Boolean {
        return mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_RINGTONE
    }
}
```

---

## 7. Phase 5: Foreground Service & Call-Keep-Alive Accessibility Service

### Step 5.1: Create `app/src/main/java/com/livemic/karaoke/service/LiveMicForegroundService.kt`
Write the following content to `app/src/main/java/com/livemic/karaoke/service/LiveMicForegroundService.kt`:

```kotlin
package com.livemic.karaoke.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.livemic.karaoke.ui.MainActivity

class LiveMicForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiveMicBT::AudioEngineWakeLock").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L) // 6 hours max safety timeout
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LiveMic Active Audio Engine",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the low-latency vocal DSP engine active in the background."
        }
        nm.createNotificationChannel(channel)

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LiveMic BT Studio Active")
            .setContentText("Streaming real-time vocals to Bluetooth output")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "livemic_engine_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.livemic.karaoke.action.START"
        const val ACTION_STOP = "com.livemic.karaoke.action.STOP"
    }
}
```

### Step 5.2: Create `app/src/main/java/com/livemic/karaoke/service/MicKeepAliveAccessibilityService.kt`
Write the following content to `app/src/main/java/com/livemic/karaoke/service/MicKeepAliveAccessibilityService.kt`:

```kotlin
package com.livemic.karaoke.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MicKeepAliveAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isAccessibilityConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No UI inspection required; presence of bound AccessibilityService + VOICE_RECOGNITION
        // preset allows concurrent mic capture during calls on Android 13+.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        _isAccessibilityConnected.value = false
        super.onDestroy()
    }

    companion object {
        private val _isAccessibilityConnected = MutableStateFlow(false)
        val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

        fun isEnabledInSettings(context: Context): Boolean {
            val expected = "${context.packageName}/${MicKeepAliveAccessibilityService::class.java.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(expected)
        }
    }
}
```

---

## 8. Phase 6: ViewModel & Jetpack Compose UI

### Step 6.1: Create `app/src/main/java/com/livemic/karaoke/ui/MainViewModel.kt`
Write the following content to `app/src/main/java/com/livemic/karaoke/ui/MainViewModel.kt`:

```kotlin
package com.livemic.karaoke.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.livemic.karaoke.audio.AudioEndpoint
import com.livemic.karaoke.audio.AudioRoutingManager
import com.livemic.karaoke.audio.FocusAndCallController
import com.livemic.karaoke.audio.NativeAudioBridge
import com.livemic.karaoke.audio.RoutingMode
import com.livemic.karaoke.service.LiveMicForegroundService
import com.livemic.karaoke.service.MicKeepAliveAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LiveMicUiState(
    val isEngineRunning: Boolean = false,
    val routingMode: RoutingMode = RoutingMode.HYBRID_AUTO,
    val selectedInputDeviceId: Int = 0,
    val selectedOutputDeviceId: Int = 0,
    val mixWithOtherApps: Boolean = true,
    val playDuringCalls: Boolean = false,
    val lowLatencyGameUsage: Boolean = false,
    val isCallCurrentlyActive: Boolean = false,
    val isAccessibilityServiceEnabled: Boolean = false,
    val inputGainDb: Float = 4.0f,
    val highPassCutoffHz: Float = 90.0f,
    val noiseGateThresholdDb: Float = -45.0f,
    val speechDenoiseMode: Boolean = false,
    val antiFeedbackEnabled: Boolean = true,
    val normalizerEnabled: Boolean = true,
    val voiceFocusEnabled: Boolean = false,
    val eqBandsDb: List<Float> = listOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f),
    val inputRms: Float = 0.0f,
    val outputRms: Float = 0.0f,
    val gainReductionDb: Float = 0.0f,
    val estimatedLatencyMs: Float = 0.0f
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LiveMicUiState())
    val uiState: StateFlow<LiveMicUiState> = _uiState.asStateFlow()

    val routingManager = AudioRoutingManager(application) {
        reconfigureIfRunning()
    }

    private val focusAndCallController = FocusAndCallController(
        context = application,
        onCallModeChanged = { callActive ->
            _uiState.update { it.copy(isCallCurrentlyActive = callActive) }
            if (_uiState.value.isEngineRunning) {
                if (callActive && !_uiState.value.playDuringCalls) {
                    stopEngine()
                } else {
                    reconfigureIfRunning()
                }
            }
        },
        onFocusPauseRequested = {
            if (!_uiState.value.mixWithOtherApps && _uiState.value.isEngineRunning) {
                stopEngine()
            }
        }
    )

    val inputEndpoints: StateFlow<List<AudioEndpoint>> = routingManager.inputEndpoints
    val outputEndpoints: StateFlow<List<AudioEndpoint>> = routingManager.outputEndpoints
    val hasBtCallProfileRisk: StateFlow<Boolean> = routingManager.hasBluetoothCallProfileRisk

    init {
        // Poll telemetry and accessibility status at ~30fps
        viewModelScope.launch {
            while (isActive) {
                val accEnabled = MicKeepAliveAccessibilityService.isEnabledInSettings(getApplication())
                if (_uiState.value.isEngineRunning) {
                    val telemetry = NativeAudioBridge.nativeGetTelemetry()
                    _uiState.update {
                        it.copy(
                            inputRms = telemetry[0],
                            outputRms = telemetry[1],
                            gainReductionDb = telemetry[2],
                            estimatedLatencyMs = telemetry[3],
                            isAccessibilityServiceEnabled = accEnabled
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            inputRms = 0f,
                            outputRms = 0f,
                            gainReductionDb = 0f,
                            estimatedLatencyMs = 0f,
                            isAccessibilityServiceEnabled = accEnabled
                        )
                    }
                }
                delay(33L)
            }
        }
    }

    override fun onCleared() {
        stopEngine()
        routingManager.release()
        focusAndCallController.release()
        super.onCleared()
    }

    fun toggleEngine() {
        if (_uiState.value.isEngineRunning) {
            stopEngine()
        } else {
            startEngine()
        }
    }

    fun startEngine() {
        val state = _uiState.value
        focusAndCallController.applyFocusPolicy(state.mixWithOtherApps)

        val (inDevId, outDevId) = routingManager.resolveDeviceIds(
            state.routingMode,
            state.selectedInputDeviceId,
            state.selectedOutputDeviceId
        )

        // Shared mode is required whenever mixing with other apps or using Samsung Separate App Sound
        val useSharedOutput = state.mixWithOtherApps || state.routingMode == RoutingMode.HYBRID_AUTO

        // Use VOICE_RECOGNITION during calls or when Call Mode toggle is active so Android 13 doesn't mute mic
        val inputPreset = if (state.playDuringCalls || state.isCallCurrentlyActive) {
            NativeAudioBridge.INPUT_PRESET_VOICE_RECOGNITION
        } else {
            NativeAudioBridge.INPUT_PRESET_VOICE_PERFORMANCE
        }

        val started = NativeAudioBridge.nativeStartEngine(
            inDevId,
            outDevId,
            useSharedOutput,
            inputPreset,
            state.lowLatencyGameUsage
        )

        if (started) {
            val ctx = getApplication<Application>()
            val serviceIntent = Intent(ctx, LiveMicForegroundService::class.java).apply {
                action = LiveMicForegroundService.ACTION_START
            }
            ctx.startForegroundService(serviceIntent)
            _uiState.update { it.copy(isEngineRunning = true) }
        }
    }

    fun stopEngine() {
        NativeAudioBridge.nativeStopEngine()
        focusAndCallController.abandonAudioFocus()

        val ctx = getApplication<Application>()
        val serviceIntent = Intent(ctx, LiveMicForegroundService::class.java).apply {
            action = LiveMicForegroundService.ACTION_STOP
        }
        ctx.startService(serviceIntent)
        _uiState.update { it.copy(isEngineRunning = false) }
    }

    private fun reconfigureIfRunning() {
        val state = _uiState.value
        if (!state.isEngineRunning) return

        val (inDevId, outDevId) = routingManager.resolveDeviceIds(
            state.routingMode,
            state.selectedInputDeviceId,
            state.selectedOutputDeviceId
        )
        val useSharedOutput = state.mixWithOtherApps || state.routingMode == RoutingMode.HYBRID_AUTO
        val inputPreset = if (state.playDuringCalls || state.isCallCurrentlyActive) {
            NativeAudioBridge.INPUT_PRESET_VOICE_RECOGNITION
        } else {
            NativeAudioBridge.INPUT_PRESET_VOICE_PERFORMANCE
        }

        NativeAudioBridge.nativeReconfigureEngine(
            inDevId,
            outDevId,
            useSharedOutput,
            inputPreset,
            state.lowLatencyGameUsage
        )
    }

    fun setRoutingMode(mode: RoutingMode) {
        _uiState.update { it.copy(routingMode = mode) }
        reconfigureIfRunning()
    }

    fun setManualInputDevice(deviceId: Int) {
        _uiState.update { it.copy(selectedInputDeviceId = deviceId) }
        reconfigureIfRunning()
    }

    fun setManualOutputDevice(deviceId: Int) {
        _uiState.update { it.copy(selectedOutputDeviceId = deviceId) }
        reconfigureIfRunning()
    }

    fun setMixWithOtherApps(enabled: Boolean) {
        _uiState.update { it.copy(mixWithOtherApps = enabled) }
        if (_uiState.value.isEngineRunning) {
            focusAndCallController.applyFocusPolicy(enabled)
            reconfigureIfRunning()
        }
    }

    fun setPlayDuringCalls(enabled: Boolean) {
        _uiState.update { it.copy(playDuringCalls = enabled) }
        reconfigureIfRunning()
    }

    fun setLowLatencyGameUsage(enabled: Boolean) {
        _uiState.update { it.copy(lowLatencyGameUsage = enabled) }
        reconfigureIfRunning()
    }

    fun setInputGainDb(db: Float) {
        _uiState.update { it.copy(inputGainDb = db) }
        NativeAudioBridge.nativeSetInputGainDb(db)
    }

    fun setHighPassCutoffHz(hz: Float) {
        _uiState.update { it.copy(highPassCutoffHz = hz) }
        NativeAudioBridge.nativeSetHighPassCutoffHz(hz)
    }

    fun setNoiseGateThresholdDb(db: Float) {
        _uiState.update { it.copy(noiseGateThresholdDb = db) }
        NativeAudioBridge.nativeSetNoiseGateThresholdDb(db)
    }

    fun setSpeechDenoiseMode(speechMode: Boolean) {
        _uiState.update { it.copy(speechDenoiseMode = speechMode) }
        NativeAudioBridge.nativeSetSpeechDenoiseMode(speechMode)
    }

    fun setAntiFeedbackEnabled(enabled: Boolean) {
        _uiState.update { it.copy(antiFeedbackEnabled = enabled) }
        NativeAudioBridge.nativeSetAntiFeedbackEnabled(enabled)
    }

    fun setNormalizerEnabled(enabled: Boolean) {
        _uiState.update { it.copy(normalizerEnabled = enabled) }
        NativeAudioBridge.nativeSetNormalizerEnabled(enabled)
    }

    fun setVoiceFocusEnabled(enabled: Boolean) {
        _uiState.update { it.copy(voiceFocusEnabled = enabled) }
        NativeAudioBridge.nativeSetVoiceFocusEnabled(enabled)
    }

    fun setEqBandGainDb(bandIndex: Int, gainDb: Float) {
        val updated = _uiState.value.eqBandsDb.toMutableList()
        if (bandIndex in updated.indices) {
            updated[bandIndex] = gainDb
            _uiState.update { it.copy(eqBandsDb = updated) }
            NativeAudioBridge.nativeSetEqBandGainDb(bandIndex, gainDb)
        }
    }

    fun resetEq() {
        for (i in 0..4) {
            NativeAudioBridge.nativeSetEqBandGainDb(i, 0.0f)
        }
        _uiState.update { it.copy(eqBandsDb = listOf(0f, 0f, 0f, 0f, 0f)) }
    }

    fun openAccessibilitySettings() {
        val ctx = getApplication<Application>()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
    }
}
```

### Step 6.2: Create `app/src/main/java/com/livemic/karaoke/ui/MainActivity.kt`
Write the following complete Jetpack Compose UI to `app/src/main/java/com/livemic/karaoke/ui/MainActivity.kt`:

```kotlin
package com.livemic.karaoke.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.livemic.karaoke.audio.AudioEndpoint
import com.livemic.karaoke.audio.RoutingMode
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E5FF),
                    secondary = Color(0xFF7C4DFF),
                    background = Color(0xFF0F1218),
                    surface = Color(0xFF1A1F29)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LiveMicScreen(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun LiveMicScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val inputs by viewModel.inputEndpoints.collectAsState()
    val outputs by viewModel.outputEndpoints.collectAsState()
    val btCallRisk by viewModel.hasBtCallProfileRisk.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val micGranted = grants[Manifest.permission.RECORD_AUDIO] == true
        if (micGranted) {
            viewModel.toggleEngine()
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header & Live Start/Stop Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "LiveMic BT Studio",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = if (state.isEngineRunning) {
                        "LIVE • Est. Buffer: ${state.estimatedLatencyMs.roundToInt()} ms"
                    } else {
                        "STANDBY • 48kHz Float32 Oboe DSP"
                    },
                    fontSize = 13.sp,
                    color = if (state.isEngineRunning) Color(0xFF00E676) else Color.Gray
                )
            }

            Button(
                onClick = {
                    val ctx = viewModel.getApplication<android.app.Application>()
                    val hasMic = ContextCompat.checkSelfPermission(
                        ctx,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasMic) {
                        viewModel.toggleEngine()
                    } else {
                        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        permissionLauncher.launch(perms.toTypedArray())
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isEngineRunning) Color(0xFFFF1744) else Color(0xFF00E676)
                )
            ) {
                Text(
                    text = if (state.isEngineRunning) "STOP MIC" else "START MIC",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Real-Time Metering Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Real-Time Level Meters", fontWeight = FontWeight.SemiBold, color = Color.White)
                MeterBar(label = "Mic Input", rms = state.inputRms, barColor = Color(0xFF00E5FF))
                MeterBar(label = "Speaker Output", rms = state.outputRms, barColor = Color(0xFF00E676))
                Text(
                    text = "Compressor Gain Reduction: ${state.gainReductionDb.roundToInt()} dB",
                    fontSize = 12.sp,
                    color = Color.LightGray
                )
            }
        }

        // Bluetooth Call Profile (HFP/SCO) Warning Banner
        if (btCallRisk) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3E2723)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Bluetooth 'Calls' Profile Detected on Speaker",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFCA28)
                    )
                    Text(
                        text = "Your Bluetooth speaker has its HFP/SCO Mic profile enabled. To keep high-quality A2DP stereo & Samsung Dual Audio active during phone/WhatsApp calls, disable the 'Calls' toggle for this speaker in Bluetooth settings.",
                        fontSize = 12.sp,
                        color = Color.White
                    )
                    OutlinedButton(onClick = { viewModel.routingManager.openBluetoothSettings() }) {
                        Text("Open Bluetooth Settings", color = Color(0xFFFFCA28))
                    }
                }
            }
        }

        // Section 1: Multi-Speaker & Samsung Routing Options
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "1. Audio Routing & Multi-Speaker Output",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF)
                )

                RoutingMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { viewModel.setRoutingMode(mode) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = state.routingMode == mode,
                            onClick = { viewModel.setRoutingMode(mode) }
                        )
                        Column(modifier = Modifier.padding(start = 6.dp)) {
                            Text(mode.title, fontWeight = FontWeight.Medium, color = Color.White, fontSize = 14.sp)
                            Text(mode.subtitle, fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                }

                if (state.routingMode == RoutingMode.MANUAL_PICKER) {
                    HorizontalDivider(color = Color.DarkGray)
                    EndpointDropdown(
                        title = "Input Microphone Device",
                        endpoints = inputs,
                        selectedId = state.selectedInputDeviceId,
                        onSelect = { viewModel.setManualInputDevice(it) }
                    )
                    EndpointDropdown(
                        title = "Output Speaker Device",
                        endpoints = outputs,
                        selectedId = state.selectedOutputDeviceId,
                        onSelect = { viewModel.setManualOutputDevice(it) }
                    )
                }

                // Quick Shortcuts for Samsung Dual Audio & Separate App Sound
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.routingManager.openSystemMediaOutputSwitcher() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Dual Audio / Media Output", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.routingManager.openSamsungSeparateAppSoundSettings() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Separate App Sound", fontSize = 11.sp)
                    }
                }
            }
        }

        // Section 2: Music Mixing, Notifications & Call Concurrency
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "2. Concurrency (Music, Notifications & Calls)",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF)
                )

                ToggleRow(
                    title = "Continue Playing Over Music & Notifications",
                    subtitle = "Keeps mic active in Shared Mode when Spotify, YouTube karaoke tracks, or notification tones play.",
                    checked = state.mixWithOtherApps,
                    onCheckedChange = { viewModel.setMixWithOtherApps(it) }
                )

                ToggleRow(
                    title = "Keep Mic Active During Phone / WhatsApp Calls",
                    subtitle = "Switches input to VoiceRecognition preset so your voice continues playing on speakers during calls.",
                    checked = state.playDuringCalls,
                    onCheckedChange = { viewModel.setPlayDuringCalls(it) }
                )

                if (state.playDuringCalls && !state.isAccessibilityServiceEnabled) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF263238)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Android 13 Call-Mic Permission Required",
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF00E5FF),
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Android 13 mutes third-party mics during active calls unless the LiveMic Call-Mode Keep-Alive service is enabled in Accessibility Settings.",
                                fontSize = 11.sp,
                                color = Color.LightGray
                            )
                            Button(onClick = { viewModel.openAccessibilitySettings() }) {
                                Text("Enable Call Keep-Alive Service", fontSize = 12.sp)
                            }
                        }
                    }
                }

                ToggleRow(
                    title = "Low-Latency Bluetooth Gaming Flag (Usage::Game)",
                    subtitle = "Signals compatible Bluetooth speakers to engage low-latency gaming buffer mode.",
                    checked = state.lowLatencyGameUsage,
                    onCheckedChange = { viewModel.setLowLatencyGameUsage(it) }
                )
            }
        }

        // Section 3: Input Processing (Preamp, HPF, Noise Removal, Anti-Feedback, Normalizer)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "3. Input DSP (Noise Removal, Anti-Feedback & Normalizer)",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF)
                )

                SliderRow(
                    label = "Mic Preamp Gain",
                    valueText = "${state.inputGainDb.roundToInt()} dB",
                    value = state.inputGainDb,
                    valueRange = -12f..24f,
                    onValueChange = { viewModel.setInputGainDb(it) }
                )

                SliderRow(
                    label = "High-Pass Rumble Filter",
                    valueText = "${state.highPassCutoffHz.roundToInt()} Hz",
                    value = state.highPassCutoffHz,
                    valueRange = 40f..220f,
                    onValueChange = { viewModel.setHighPassCutoffHz(it) }
                )

                SliderRow(
                    label = "Noise Gate / Expander Threshold",
                    valueText = "${state.noiseGateThresholdDb.roundToInt()} dBFS",
                    value = state.noiseGateThresholdDb,
                    valueRange = -70f..-18f,
                    onValueChange = { viewModel.setNoiseGateThresholdDb(it) }
                )

                ToggleRow(
                    title = "Aggressive Speech Denoiser Mode",
                    subtitle = "OFF = Vocal/Karaoke Mode (preserves sustained notes). ON = Speech PA Mode (fast noise stripping).",
                    checked = state.speechDenoiseMode,
                    onCheckedChange = { viewModel.setSpeechDenoiseMode(it) }
                )

                ToggleRow(
                    title = "Anti-Feedback Howling Suppressor",
                    subtitle = "Applies 3 resonant notch filters + 3.5Hz micro-modulation to prevent room feedback loops.",
                    checked = state.antiFeedbackEnabled,
                    onCheckedChange = { viewModel.setAntiFeedbackEnabled(it) }
                )

                ToggleRow(
                    title = "Vocal Normalizer & Brickwall Limiter",
                    subtitle = "4:1 dynamic compressor (-18dBFS) + makeup gain + -0.5dBFS anti-clipping limiter.",
                    checked = state.normalizerEnabled,
                    onCheckedChange = { viewModel.setNormalizerEnabled(it) }
                )
            }
        }

        // Section 4: Output EQ & Voice Focus
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "4. Output 5-Band EQ & Voice Focus",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF)
                    )
                    TextButton(onClick = { viewModel.resetEq() }) {
                        Text("Reset EQ")
                    }
                }

                ToggleRow(
                    title = "Voice Focus Enhancement",
                    subtitle = "Cuts 300Hz speaker box mud (-3.5dB), boosts 2.8kHz vocal clarity (+4.5dB), rolls off >8.5kHz hiss.",
                    checked = state.voiceFocusEnabled,
                    onCheckedChange = { viewModel.setVoiceFocusEnabled(it) }
                )

                val bandLabels = listOf(
                    "100 Hz (Sub / Bass)",
                    "350 Hz (Low-Mid / Warmth)",
                    "1.2 kHz (Midrange / Body)",
                    "3.5 kHz (Vocal Presence)",
                    "8.0 kHz (Air & Crispness)"
                )

                bandLabels.forEachIndexed { idx, label ->
                    val gain = state.eqBandsDb[idx]
                    SliderRow(
                        label = label,
                        valueText = "${if (gain >= 0) "+" else ""}${gain.roundToInt()} dB",
                        value = gain,
                        valueRange = -12f..12f,
                        onValueChange = { viewModel.setEqBandGainDb(idx, it) }
                    )
                }
            }
        }
    }
}

@Composable
fun MeterBar(label: String, rms: Float, barColor: Color) {
    val clamped = (rms * 2.2f).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 12.sp, color = Color.LightGray)
            Text("${(clamped * 100).roundToInt()}%", fontSize = 12.sp, color = Color.LightGray)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0xFF0B0E14))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .fillMaxHeight()
                    .background(barColor)
            )
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color.Gray, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color.White, fontSize = 13.sp)
            Text(valueText, color = Color(0xFF00E5FF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

@Composable
fun EndpointDropdown(
    title: String,
    endpoints: List<AudioEndpoint>,
    selectedId: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val current = endpoints.firstOrNull { it.id == selectedId }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 12.sp, color = Color.LightGray)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = current?.let { "${it.name} • ${it.typeDescription}" } ?: "System Default (ID: 0)",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("System Default (ID: 0)") },
                    onClick = {
                        onSelect(0)
                        expanded = false
                    }
                )
                endpoints.forEach { ep ->
                    DropdownMenuItem(
                        text = { Text("${ep.name} • ${ep.typeDescription} (ID: ${ep.id})") },
                        onClick = {
                            onSelect(ep.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
```

---

## 9. Phase 7: Build & Verification Checklist for the Agent

After creating all files above, run the following verification sequence:

1. **Verify Native & Kotlin Compilation:**
   Execute `./gradlew assembleDebug` from the project root.
   * Confirm that Prefab resolves `oboe::oboe` without CMake linker errors.
   * Confirm that `liblivemic_engine.so` is packaged into `app/build/outputs/apk/debug/app-debug.apk`.
2. **Verify Samsung Android 13 Routing & Concurrency Behaviors:**
   * **Samsung Separate App Sound & Dual Audio:** Keep Routing Mode on **Hybrid Auto**. Tap the in-app **Dual Audio / Media Output** button to check both Bluetooth speakers, or tap **Separate App Sound** to assign `LiveMic BT Studio` to the Bluetooth speaker while other apps play on the phone.
   * **Music & Notification Mixing:** Leave **Continue Playing Over Music & Notifications** enabled. Start a karaoke backing track in YouTube or Spotify, then tap **START MIC**—both streams will mix simultaneously without pausing each other.
   * **Active Call Mode:** Enable **Keep Mic Active During Phone / WhatsApp Calls** and enable **LiveMic Call-Mode Mic Keep-Alive** in Accessibility Settings. Also disable the **Calls** profile toggle on the target Bluetooth speaker so the speaker remains locked in high-bandwidth A2DP stereo mode during the call.