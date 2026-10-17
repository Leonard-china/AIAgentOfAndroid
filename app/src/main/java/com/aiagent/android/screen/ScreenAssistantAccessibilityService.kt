package com.aiagent.android.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean

class ScreenAssistantAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var isConnected: Boolean = false
            private set

        private var instance: ScreenAssistantAccessibilityService? = null
        private val emergencyStop = AtomicBoolean(false)

        fun isServiceRunning(): Boolean = isConnected && instance != null

        fun triggerEmergencyStop() {
            emergencyStop.set(true)
        }

        fun findAndClick(keyword: String): Boolean {
            if (emergencyStop.get()) return false
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false

            // Safety check: Never automate clicks on destructive confirmation dialogs!
            val pkg = root.packageName?.toString() ?: ""
            if (keyword.contains("删除") || keyword.contains("清除全部") || keyword.contains("格式化")) {
                // Strict adherence to safety rule: Never auto-click delete popups!
                return false
            }

            val matchingNodes = root.findAccessibilityNodeInfosByText(keyword)
            for (node in matchingNodes) {
                if (svc.clickNodeOrParent(node)) {
                    return true
                }
            }
            return false
        }

        fun performGlobalBack(): Boolean {
            val svc = instance ?: return false
            return svc.performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isConnected = true
        emergencyStop.set(false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive observation only; no aggressive background tapping
    }

    override fun onInterrupt() {
        isConnected = false
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
        instance = null
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }
}
