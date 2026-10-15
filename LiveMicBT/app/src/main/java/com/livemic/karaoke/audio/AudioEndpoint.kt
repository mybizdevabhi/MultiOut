package com.livemic.karaoke.audio

data class AudioEndpoint(
    val id: Int,
    val name: String,
    val typeDescription: String,
    val isBluetoothA2dpOrBle: Boolean,
    val isBluetoothScoHfp: Boolean
)
