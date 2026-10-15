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

    fun resolveDeviceIds(
        mode: RoutingMode,
        manualInputId: Int,
        manualOutputId: Int
    ): Pair<Int, Int> {
        return when (mode) {
            RoutingMode.HYBRID_AUTO -> {
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
