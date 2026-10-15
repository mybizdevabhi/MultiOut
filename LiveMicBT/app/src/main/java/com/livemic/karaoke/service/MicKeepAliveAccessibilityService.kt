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
