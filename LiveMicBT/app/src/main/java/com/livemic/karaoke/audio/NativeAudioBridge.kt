package com.livemic.karaoke.audio

object NativeAudioBridge {
    init {
        System.loadLibrary("livemic_engine")
    }

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

    external fun nativeGetTelemetry(): FloatArray
}
