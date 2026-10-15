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

        val useSharedOutput = state.mixWithOtherApps || state.routingMode == RoutingMode.HYBRID_AUTO

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
