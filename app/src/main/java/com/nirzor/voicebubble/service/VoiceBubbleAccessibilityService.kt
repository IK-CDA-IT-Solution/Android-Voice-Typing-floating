package com.nirzor.voicebubble.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VoiceBubbleAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceEnabled.value = true
        Log.d(TAG, "VoiceBubbleAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Track focus / state change events
    }

    override fun onInterrupt() {
        Log.w(TAG, "VoiceBubbleAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
            _isServiceEnabled.value = false
        }
        Log.d(TAG, "VoiceBubbleAccessibilityService destroyed")
    }

    companion object {
        private const val TAG = "VoiceBubbleA11y"

        @Volatile
        private var instance: VoiceBubbleAccessibilityService? = null

        private val _isServiceEnabled = MutableStateFlow(false)
        val isServiceEnabled: StateFlow<Boolean> = _isServiceEnabled.asStateFlow()

        /**
         * Injects text directly into the currently focused editable input field.
         * Returns true if successfully set text on a focused editable node; false otherwise.
         * If the node is a password field, returns false without modifying to preserve privacy.
         */
        fun injectText(text: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false

            return try {
                if (focusedNode.isPassword) {
                    Log.d(TAG, "Focused node is password field; skipping direct injection for safety")
                    false
                } else if (!focusedNode.isEditable) {
                    Log.d(TAG, "Focused node is not editable")
                    false
                } else {
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    }
                    focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error performing direct text injection", e)
                false
            } finally {
                @Suppress("DEPRECATION")
                focusedNode.recycle()
                @Suppress("DEPRECATION")
                root.recycle()
            }
        }
    }
}
