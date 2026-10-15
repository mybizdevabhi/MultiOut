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
