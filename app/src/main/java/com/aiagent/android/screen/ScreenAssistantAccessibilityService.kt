package com.aiagent.android.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

        fun clickAt(x: Float, y: Float, durationMs: Long = 50L): Boolean {
            if (emergencyStop.get()) return false
            val svc = instance ?: return false
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()

            val latch = CountDownLatch(1)
            var success = false

            val dispatched = svc.dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    success = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    success = false
                    latch.countDown()
                }
            }, null)

            if (!dispatched) return false
            try {
                latch.await(1000, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return false
            }
            return success
        }

        fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300L): Boolean {
            if (emergencyStop.get()) return false
            val svc = instance ?: return false
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()

            val latch = CountDownLatch(1)
            var success = false

            val dispatched = svc.dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    success = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    success = false
                    latch.countDown()
                }
            }, null)

            if (!dispatched) return false
            try {
                latch.await(1500, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return false
            }
            return success
        }

        fun swipeDirection(direction: String): Boolean {
            val svc = instance ?: return false
            val dm = svc.resources.displayMetrics
            val width = dm.widthPixels.toFloat()
            val height = dm.heightPixels.toFloat()

            val centerX = width / 2f
            val centerY = height / 2f

            return when (direction.uppercase()) {
                "UP" -> swipe(centerX, height * 0.75f, centerX, height * 0.25f, 300L)
                "DOWN" -> swipe(centerX, height * 0.25f, centerX, height * 0.75f, 300L)
                "LEFT" -> swipe(width * 0.8f, centerY, width * 0.2f, centerY, 300L)
                "RIGHT" -> swipe(width * 0.2f, centerY, width * 0.8f, centerY, 300L)
                else -> false
            }
        }

        fun findAndClick(keyword: String): Boolean {
            if (emergencyStop.get()) return false
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false

            // Safety check: Never automate clicks on destructive confirmation dialogs!
            if (keyword.contains("删除") || keyword.contains("清除全部") || keyword.contains("格式化")) {
                // Strict adherence to safety rule: Never auto-click delete popups!
                return false
            }

            val matchingNodes = root.findAccessibilityNodeInfosByText(keyword)
            val bounds = Rect()
            for (node in matchingNodes) {
                // 1. Try native performAction click
                if (svc.clickNodeOrParent(node)) {
                    return true
                }
                // 2. Fallback: coordinate gesture tap on center of bounds (like clicker)
                node.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    val tapped = clickAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                    if (tapped) {
                        return true
                    }
                }
            }
            return false
        }

        fun performGlobal(actionName: String): Boolean {
            val svc = instance ?: return false
            val action = when (actionName.uppercase()) {
                "BACK" -> GLOBAL_ACTION_BACK
                "HOME" -> GLOBAL_ACTION_HOME
                "RECENTS" -> GLOBAL_ACTION_RECENTS
                "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
                "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
                else -> return false
            }
            return svc.performGlobalAction(action)
        }

        fun performGlobalBack(): Boolean = performGlobal("BACK")

        fun inspectScreenSummary(): String {
            val svc = instance ?: return "无障碍辅助服务未连接"
            val root = svc.rootInActiveWindow ?: return "当前未检测到活跃窗口"

            val items = mutableListOf<String>()
            val bounds = Rect()

            fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 8 || items.size >= 25) return
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                val label = when {
                    !text.isNullOrEmpty() -> text
                    !desc.isNullOrEmpty() -> desc
                    else -> null
                }

                if (label != null && label.length in 1..40) {
                    node.getBoundsInScreen(bounds)
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        val type = when {
                            node.isClickable -> "可点击"
                            node.className?.contains("Button") == true -> "按钮"
                            else -> "文本"
                        }
                        items.add("[$type: \"$label\" @(${bounds.centerX()},${bounds.centerY()})]")
                    }
                }

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    traverse(child, depth + 1)
                }
            }

            traverse(root, 0)

            return if (items.isEmpty()) {
                "当前屏幕未检测到显著文本或交互元素"
            } else {
                "屏幕可见元素（共 ${items.size} 项，纯文本摘要）：\n" + items.joinToString("\n")
            }
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
