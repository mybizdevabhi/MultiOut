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
            NativeAudioBridge.nativeSetDuckingAttenuation(1.0f)
            return@OnAudioFocusChangeListener
        }

        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                onFocusPauseRequested()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                NativeAudioBridge.nativeSetDuckingAttenuation(0.25f)
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
