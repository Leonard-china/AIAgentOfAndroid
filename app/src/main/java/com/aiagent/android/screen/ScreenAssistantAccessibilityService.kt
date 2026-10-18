package com.aiagent.android.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScreenAssistantAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ScreenAssistant"

        @Volatile
        var isConnected: Boolean = false
            private set

        internal var instance: ScreenAssistantAccessibilityService? = null
        private val emergencyStop = AtomicBoolean(false)

        /** 系统级破坏性确认弹窗的文本特征，用于硬性拦截自动代点。 */
        private val destructiveKeywords = listOf(
            "移至最近删除", "永久删除", "清除全部", "格式化", "清空", "删除", "卸载"
        )

        /** 开屏广告与常见阻碍弹窗关键词（本地快速秒跳） */
        private val splashSkipKeywords = listOf(
            "跳过广告", "跳过", "关闭广告", "Skip", "我知道了", "以后再说", "残忍拒绝"
        )

        /** 视频播放状态特征（用于目标达成校验） */
        private val videoPlaybackKeywords = listOf(
            "弹幕", "清晰度", "倍速", "投屏", "点赞", "投币", "分享", "全屏", "评论"
        )

        /** B站「我的」个人中心特征词 */
        private val bilibiliMyPageKeywords = listOf(
            "我的大会员", "创作中心", "离线缓存", "我的收藏", "稍后再看", "我的服务"
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
            Log.w(TAG, "Emergency stop triggered!")
        }

        fun resetEmergencyStop() {
            emergencyStop.set(false)
            Log.i(TAG, "Emergency stop reset.")
        }

        fun isEmergencyStopped(): Boolean = emergencyStop.get()

        /**
         * 本地毫秒级开屏广告/更新弹窗自动快速秒跳。
         * 在启动应用后在本地以 100ms 频率快速扫描当前屏幕（无需经过大模型云端往返），
         * 一旦探测到开屏跳过按钮，0.1 秒内执行原生点击。
         */
        fun autoDismissSplashAdOrDialog(maxWaitMs: Long = 2600L, pollIntervalMs: Long = 100L): Boolean {
            if (emergencyStop.get()) return false
            val svc = instance ?: return false
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "Starting local autoDismissSplashAdOrDialog (timeout: ${maxWaitMs}ms)...")

            while (System.currentTimeMillis() - startTime < maxWaitMs) {
                if (emergencyStop.get()) return false
                val root = svc.rootInActiveWindow
                if (root != null) {
                    val targetNode = findSplashSkipNode(root)
                    if (targetNode != null) {
                        val text = targetNode.text?.toString() ?: targetNode.contentDescription?.toString()
                        Log.i(TAG, "Found splash skip target: \"$text\", clicking immediately...")
                        var clicked = svc.clickNodeOrParent(targetNode)
                        if (!clicked) {
                            val bounds = Rect()
                            targetNode.getBoundsInScreen(bounds)
                            if (bounds.width() > 0 && bounds.height() > 0) {
                                clicked = clickAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                            }
                        }
                        if (clicked) {
                            Log.i(TAG, "Successfully auto-dismissed splash ad: \"$text\" in ${System.currentTimeMillis() - startTime}ms")
                            return true
                        }
                    }
                }
                try {
                    Thread.sleep(pollIntervalMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
            Log.d(TAG, "autoDismissSplashAdOrDialog finished without hitting skip button.")
            return false
        }

        private fun findSplashSkipNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var count = 0
            while (queue.isNotEmpty() && count < 100) {
                val node = queue.removeFirst()
                count++
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                val label = if (!text.isNullOrEmpty()) text else desc
                if (label != null && label.length in 1..20) {
                    // Check if it matches any skip pattern
                    if (splashSkipKeywords.any { p -> label.contains(p, ignoreCase = true) }) {
                        if (!destructiveKeywords.any { label.contains(it) }) {
                            return node
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) queue.add(child)
                }
            }
            return null
        }

        /**
         * 检查当前屏幕是否正在播放视频（基于全屏、清晰度、弹幕等特征）
         */
        fun isVideoPlaying(): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            var matchCount = 0

            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 12 || matchCount >= 2) return
                val text = node.text?.toString() ?: node.contentDescription?.toString()
                if (text != null && videoPlaybackKeywords.any { text.contains(it) }) {
                    matchCount++
                }
                for (i in 0 until node.childCount) {
                    walk(node.getChild(i), depth + 1)
                }
            }
            walk(root, 0)
            return matchCount >= 2
        }

        /**
         * 检测当前是否误入了 B站的「我的」个人中心页面
         */
        fun isBilibiliOnMyPage(): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            var matchCount = 0

            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 10 || matchCount >= 2) return
                val text = node.text?.toString() ?: node.contentDescription?.toString()
                if (text != null && bilibiliMyPageKeywords.any { text.contains(it) }) {
                    matchCount++
                }
                for (i in 0 until node.childCount) {
                    walk(node.getChild(i), depth + 1)
                }
            }
            walk(root, 0)
            return matchCount >= 2
        }

        /**
         * 若当前在「我的」个人中心，自动点按「首页」导航标签切回推荐视频流
         */
        fun ensureBilibiliHomePage(): Boolean {
            if (isBilibiliOnMyPage()) {
                Log.i(TAG, "Detected on 'My' page, auto clicking '首页' to return to home feed...")
                return findAndClick("首页")
            }
            return true
        }

        /**
         * 在当前界面的活跃输入框中输入文本，并可选提交（点击搜索或回车）
         */
        fun inputText(text: String, submit: Boolean = true): Boolean {
            if (emergencyStop.get()) return false
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            Log.d(TAG, "inputText: \"$text\", submit=$submit")

            // 1. 尝试找到已经获取焦点的输入框
            var targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (targetNode == null || (!targetNode.isEditable && targetNode.className?.contains("EditText") != true)) {
                // 2. 遍历查找第一个 EditText 或可编辑控件
                targetNode = findFirstEditableNode(root)
            }

            if (targetNode != null) {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val setSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Log.i(TAG, "ACTION_SET_TEXT result: $setSuccess")
                if (setSuccess) {
                    if (submit) {
                        try {
                            Thread.sleep(400L)
                        } catch (_: InterruptedException) {}
                        // 尝试点击搜索/确定按钮
                        val searchClicked = findAndClick("搜索") || findAndClick("确定") || findAndClick("前往")
                        Log.i(TAG, "Search/Confirm button click result: $searchClicked")
                    }
                    return true
                }
            }
            Log.w(TAG, "No editable node found to input text: \"$text\"")
            return false
        }

        private fun findFirstEditableNode(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
            if (depth > 12) return null
            if (node.isEditable || node.className?.contains("EditText") == true) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findFirstEditableNode(child, depth + 1)
                if (found != null) return found
            }
            return null
        }

        fun clickAt(x: Float, y: Float, durationMs: Long = 50L): Boolean {
            if (emergencyStop.get()) return false
            val svc = instance ?: return false
            // 安全铁律：绝不通过屏幕自动化代点系统级删除/清空确认弹窗
            if (activeWindowLooksDestructive(svc)) {
                Log.w(TAG, "clickAt blocked by safety rule: destructive window active")
                return false
            }
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
            Log.d(TAG, "clickAt ($x, $y) -> $success")
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
            Log.d(TAG, "swipe from ($startX, $startY) to ($endX, $endY) -> $success")
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
                Log.w(TAG, "findAndClick blocked by safety rule for keyword: $keyword")
                return false
            }

            val matchingNodes = root.findAccessibilityNodeInfosByText(keyword)
            val bounds = Rect()
            for (node in matchingNodes) {
                // 1. Try native performAction click
                if (svc.clickNodeOrParent(node)) {
                    Log.i(TAG, "findAndClick native performAction succeeded for: $keyword")
                    return true
                }
                // 2. Fallback: coordinate gesture tap on center of bounds
                node.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    val tapped = clickAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                    if (tapped) {
                        Log.i(TAG, "findAndClick coordinate tap succeeded for: $keyword @(${bounds.centerX()}, ${bounds.centerY()})")
                        return true
                    }
                }
            }
            Log.d(TAG, "findAndClick failed to find/click keyword: $keyword")
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
            val res = svc.performGlobalAction(action)
            Log.d(TAG, "performGlobal $actionName -> $res")
            return res
        }

        fun performGlobalBack(): Boolean = performGlobal("BACK")

        fun inspectScreenSummary(): String {
            val svc = instance ?: return "无障碍辅助服务未连接"
            val root = svc.rootInActiveWindow ?: return "当前未检测到活跃窗口"

            val items = mutableListOf<String>()
            val bounds = Rect()

            // 检查当前应用界面全局状态
            val isPlaying = isVideoPlaying()
            val isOnMyPage = isBilibiliOnMyPage()

            val navTabs = setOf("首页", "关注", "动态", "会员购", "我的")

            fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 16 || items.size >= 50) return
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                val label = when {
                    !text.isNullOrEmpty() -> text
                    !desc.isNullOrEmpty() -> desc
                    else -> null
                }

                if (label != null && label.length in 1..60) {
                    node.getBoundsInScreen(bounds)
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        val centerX = bounds.centerX()
                        val centerY = bounds.centerY()

                        val entry = when {
                            navTabs.contains(label) -> {
                                if (label == "我的") {
                                    "[底栏导航: \"$label\" @($centerX,$centerY) - ⚠️此为个人中心，非视频卡片，切勿误点]"
                                } else {
                                    "[底栏导航: \"$label\" @($centerX,$centerY) - 提示:可点击返回首页推荐流]"
                                }
                            }
                            label.contains("搜索") -> "[搜索入口: \"$label\" @($centerX,$centerY)]"
                            node.isClickable && label.length >= 4 -> "[推荐视频卡片: \"$label\" @($centerX,$centerY)]"
                            node.isClickable -> "[可点击控件: \"$label\" @($centerX,$centerY)]"
                            node.className?.contains("Button") == true -> "[按钮: \"$label\" @($centerX,$centerY)]"
                            else -> "[文本: \"$label\" @($centerX,$centerY)]"
                        }
                        items.add(entry)
                    }
                }

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    traverse(child, depth + 1)
                }
            }

            traverse(root, 0)

            val builder = StringBuilder()
            if (isPlaying) {
                builder.append("🎬【状态判定】：检测到屏幕正处于视频播放详情页！已达成视频播放目标！\n\n")
            } else if (isOnMyPage) {
                builder.append("⚠️【状态判定】：当前停留在「我的」个人中心界面，并非视频播放页。请点击「首页」返回推荐流或点击搜索框寻找视频！\n\n")
            }

            if (items.isEmpty()) {
                builder.append("当前屏幕未检测到显著文本或交互元素")
            } else {
                builder.append("屏幕可见元素（共 ${items.size} 项，纯文本摘要）：\n")
                builder.append(items.joinToString("\n"))
            }

            Log.d(TAG, "inspectScreenSummary generated (${items.size} items, isPlaying=$isPlaying, isOnMyPage=$isOnMyPage)")
            return builder.toString()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isConnected = true
        emergencyStop.set(false)
        Log.i(TAG, "ScreenAssistantAccessibilityService connected successfully.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive observation only; no aggressive background tapping
    }

    override fun onInterrupt() {
        isConnected = false
        instance = null
        Log.w(TAG, "ScreenAssistantAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
        instance = null
        Log.w(TAG, "ScreenAssistantAccessibilityService destroyed.")
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
