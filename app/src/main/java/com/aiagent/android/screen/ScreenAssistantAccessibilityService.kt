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

        internal var instance: ScreenAssistantAccessibilityService? = null
        private val emergencyStop = AtomicBoolean(false)

        /** 系统级破坏性确认弹窗的文本特征，用于硬性拦截自动代点。 */
        private val destructiveKeywords = listOf(
            "移至最近删除", "永久删除", "清除全部", "格式化", "清空", "删除", "卸载"
        )

        fun isServiceRunning(): Boolean = isConnected && instance != null

        /**
         * 检测当前活跃窗口是否为“系统级 + 含破坏性文案”的确认弹窗。
         * 仅对系统包生效，避免误伤普通应用内带有“删除”字样的正常界面。
         */
        fun isDestructiveSystemDialogActive(): Boolean {
            val svc = instance ?: return false
            return activeWindowLooksDestructive(svc)
        }

        private fun activeWindowLooksDestructive(svc: ScreenAssistantAccessibilityService): Boolean {
            val root = svc.rootInActiveWindow ?: return false
            val pkg = root.packageName?.toString().orEmpty()
            val isSystemWindow = pkg == "android" ||
                    pkg.startsWith("com.android.") ||
                    pkg.startsWith("com.google.android.") ||
                    pkg.startsWith("com.hihonor.") ||
                    pkg.startsWith("com.huawei.")
            if (!isSystemWindow) return false

            var matched = false
            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || matched || depth > 6) return
                val label = node.text?.toString() ?: node.contentDescription?.toString()
                if (label != null && destructiveKeywords.any { label.contains(it) }) {
                    matched = true
                    return
                }
                for (i in 0 until node.childCount) {
                    walk(node.getChild(i), depth + 1)
                }
            }
            walk(root, 0)
            return matched
        }

        fun triggerEmergencyStop() {
            emergencyStop.set(true)
        }

        fun resetEmergencyStop() {
            emergencyStop.set(false)
        }

        fun isEmergencyStopped(): Boolean = emergencyStop.get()

        fun clickAt(x: Float, y: Float, durationMs: Long = 50L): Boolean {
            if (emergencyStop.get()) return false
            val svc = instance ?: return false
            // 安全铁律：绝不通过屏幕自动化代点系统级删除/清空确认弹窗
            if (activeWindowLooksDestructive(svc)) return false
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
            if (destructiveKeywords.any { keyword.contains(it) }) {
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
