package com.aiagent.android.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.aiagent.android.AIAgentApplication
import com.aiagent.android.floating.FloatingAssistantManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
         * 检测当前是否在 B站 UP主个人空间（如 3Blue1Brown 的空间主页/投稿页）
         */
        fun isBilibiliOnUpSpace(): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            if (root.findAccessibilityNodeInfosByText("播放全部").isNotEmpty()) return true
            val hasSubmission = root.findAccessibilityNodeInfosByText("投稿").isNotEmpty()
            val hasSpaceSignals = root.findAccessibilityNodeInfosByText("主页").isNotEmpty() ||
                    root.findAccessibilityNodeInfosByText("充电").isNotEmpty() ||
                    root.findAccessibilityNodeInfosByText("关注").isNotEmpty() ||
                    root.findAccessibilityNodeInfosByText("已关注").isNotEmpty() ||
                    root.findAccessibilityNodeInfosByText("动态").isNotEmpty()
            return hasSubmission && hasSpaceSignals
        }

        /**
         * 在 UP主个人空间内自动点击“播放全部”或第一条投稿视频进入播放详情页
         */
        fun playVideoFromUpSpace(): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            val dm = svc.resources.displayMetrics
            val bounds = Rect()

            // 1. 优先寻找“播放全部”按钮并点击
            val playAllNodes = root.findAccessibilityNodeInfosByText("播放全部")
            if (playAllNodes.isNotEmpty()) {
                val node = playAllNodes[0]
                node.getBoundsInScreen(bounds)
                if (svc.clickNodeOrParent(node) || clickAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())) {
                    Log.i(TAG, "playVideoFromUpSpace: Clicked '播放全部' @(${bounds.centerX()}, ${bounds.centerY()})")
                    return true
                }
            }

            // 2. 寻找投稿列表中的第一个视频标题节点
            fun findFirstVideo(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
                if (node == null || depth > 16) return null
                val text = node.text?.toString() ?: node.contentDescription?.toString()
                if (text != null && text.length in 6..120) {
                    node.getBoundsInScreen(bounds)
                    if (bounds.top > dm.heightPixels * 0.45f && bounds.width() > dm.widthPixels * 0.35f) {
                        val isExcluded = text.contains("播放全部") || text.contains("最新发布") || text.contains("筛选") ||
                                text.contains("置顶") || text.contains("充电") || text.contains("关注") ||
                                text.contains("主页") || text.contains("动态") || text.contains("投稿") || text.contains("小店")
                        if (!isExcluded) {
                            return node
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    val found = findFirstVideo(child, depth + 1)
                    if (found != null) return found
                }
                return null
            }

            val vid = findFirstVideo(root, 0)
            if (vid != null) {
                vid.getBoundsInScreen(bounds)
                val ok = svc.clickNodeOrParent(vid) || clickAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                if (ok) {
                    Log.i(TAG, "playVideoFromUpSpace: Clicked video '${vid.text}' @(${bounds.centerX()}, ${bounds.centerY()})")
                    return true
                }
            }

            // 3. 坐标保底：UP主空间投稿列表首个视频项 (width * 0.5f, height * 0.72f)
            val coordOk = clickAt(dm.widthPixels * 0.5f, dm.heightPixels * 0.72f)
            Log.i(TAG, "playVideoFromUpSpace coordinate tap fallback @(0.5f, 0.72f) -> $coordOk")
            return coordOk
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
                            Thread.sleep(500L)
                        } catch (_: InterruptedException) {}
                        val searchClicked = submitSearch(text)
                        Log.i(TAG, "submitSearch result: $searchClicked")
                    }
                    return true
                }
            }
            Log.w(TAG, "No editable node found to input text: \"$text\"")
            return false
        }

        /**
         * 强化版搜索提交：综合考虑顶栏搜索按钮、搜索候选联想词项、软键盘回车键
         */
        fun submitSearch(keyword: String = ""): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            val dm = svc.resources.displayMetrics
            val width = dm.widthPixels.toFloat()
            val height = dm.heightPixels.toFloat()

            // 1. 尝试对当前焦点输入框执行 ACTION_IME_ENTER (API 30+)
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                    Log.i(TAG, "submitSearch via ACTION_IME_ENTER succeeded")
                    return true
                }
            }

            // 2. 查找屏幕顶部的“搜索”按钮（Y < 16% 高度，X > 65% 屏幕宽度）
            val bounds = Rect()
            val searchNodes = root.findAccessibilityNodeInfosByText("搜索")
            for (node in searchNodes) {
                node.getBoundsInScreen(bounds)
                if (bounds.top < height * 0.16f && bounds.right > width * 0.65f) {
                    if (svc.clickNodeOrParent(node) || clickAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())) {
                        Log.i(TAG, "submitSearch via top-right search button succeeded @(${bounds.centerX()}, ${bounds.centerY()})")
                        return true
                    }
                }
            }

            // 3. 点击联想词列表中的首个候选词（如 "3Blue1Brown" 或包含关键词的项）
            if (keyword.isNotEmpty()) {
                val suggestionNodes = root.findAccessibilityNodeInfosByText(keyword)
                for (node in suggestionNodes) {
                    node.getBoundsInScreen(bounds)
                    if (bounds.top in (height * 0.08f).toInt()..(height * 0.45f).toInt()) {
                        if (svc.clickNodeOrParent(node) || clickAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())) {
                            Log.i(TAG, "submitSearch via suggestion item succeeded: ${node.text} @(${bounds.centerX()}, ${bounds.centerY()})")
                            return true
                        }
                    }
                }
            }

            // 4. 坐标回退保底：
            // 4a. 顶栏右侧“搜索”固定位置点击
            val topSearchOk = clickAt(width * 0.92f, height * 0.055f)
            if (topSearchOk) {
                Log.i(TAG, "submitSearch fallback tap on top-right search coord succeeded")
                return true
            }

            // 4b. 软键盘右下角“搜索”按键位置点击
            val imeSearchOk = clickAt(width * 0.90f, height * 0.94f)
            Log.i(TAG, "submitSearch fallback tap on keyboard search key -> $imeSearchOk")
            return imeSearchOk
        }

        /**
         * 无论在搜索结果页还是在 UP主个人空间页，都精准定位并点击视频卡片进入播放闭环
         */
        fun clickFirstVideoCardInSearchResults(): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            val dm = svc.resources.displayMetrics
            val width = dm.widthPixels.toFloat()
            val height = dm.heightPixels.toFloat()
            val bounds = Rect()

            // 0. 特征检测 A：如果已经进入了 UP主个人空间，直接播放视频
            if (isBilibiliOnUpSpace()) {
                val ok = playVideoFromUpSpace()
                if (ok) {
                    try { Thread.sleep(900L) } catch (_: InterruptedException) {}
                    return true
                }
            }

            // 1. 在通用搜索结果列表中寻找真正的视频卡片（严格过滤掉 UP主名片，UP主名片包含粉丝、充电、认证、关注等）
            fun findSearchVideoNode(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
                if (node == null || depth > 16) return null
                val text = node.text?.toString() ?: node.contentDescription?.toString()
                if (text != null && text.length in 6..100) {
                    node.getBoundsInScreen(bounds)
                    if (bounds.width() > width * 0.35f && bounds.top in (height * 0.10f).toInt()..(height * 0.85f).toInt()) {
                        val isUpCard = text.contains("粉丝") || text.contains("充电") || text.contains("认证") ||
                                text.contains("关注") || text.contains("综合") || text.contains("番剧") ||
                                text.contains("筛选") || text.contains("会员购") || text.contains("动态") ||
                                text.contains("小店")
                        if (!isUpCard) {
                            return node
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    val found = findSearchVideoNode(child, depth + 1)
                    if (found != null) return found
                }
                return null
            }

            val videoNode = findSearchVideoNode(root, 0)
            if (videoNode != null) {
                videoNode.getBoundsInScreen(bounds)
                val clicked = svc.clickNodeOrParent(videoNode) || clickAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                Log.i(TAG, "clickFirstVideoCardInSearchResults found and clicked: ${videoNode.text ?: videoNode.contentDescription} -> $clicked")
            } else {
                // 坐标保底：点击搜索结果第 1 个视频项 (width * 0.5f, height * 0.42f) 避开顶部的 UP主名片
                val coordOk = clickAt(width * 0.5f, height * 0.42f)
                Log.i(TAG, "clickFirstVideoCard coordinate tap fallback @(0.5f, 0.42f) -> $coordOk")
            }

            // 等待页面过渡渲染
            try { Thread.sleep(1000L) } catch (_: InterruptedException) {}

            // 自愈校验：若点击误入了 UP主个人空间（例如点中了 UP主名片），继续执行空间内播放
            if (isBilibiliOnUpSpace()) {
                Log.i(TAG, "Landed on UP space after click, auto continuing playVideoFromUpSpace()...")
                playVideoFromUpSpace()
                try { Thread.sleep(900L) } catch (_: InterruptedException) {}
            }

            return isVideoPlaying() || isBilibiliOnUpSpace()
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
            val isUpSpace = isBilibiliOnUpSpace()
            if (isPlaying) {
                builder.append("🎬【状态判定】：检测到屏幕正处于视频播放详情页！已达成视频播放目标！\n\n")
            } else if (isUpSpace) {
                builder.append("📺【状态判定】：当前已进入 UP 主个人空间主页！请调用 operate_screen_click 点击「播放全部」或者点击首个投稿视频卡片进入播放！\n\n")
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

        private var quickChatView: View? = null
        private var quickChatWm: WindowManager? = null

        /**
         * 立即收起并销毁就地快速交流弹窗。
         */
        fun hideQuickChatOverlay() {
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val view = quickChatView
                val wm = quickChatWm
                if (view != null && wm != null) {
                    runCatching { wm.removeViewImmediate(view) }
                }
                quickChatView = null
                quickChatWm = null
            }
        }

        /**
         * 在当前任何第三方页面（如B站、微信）通过 TYPE_ACCESSIBILITY_OVERLAY 就地弹出 AI 对话交流面板。
         * 享有无障碍原生顶层图层特权，即便未手动开启悬浮窗权限，也能 100% 顺畅弹出并与用户交流。
         */
        fun showQuickChatOverlay(
            context: Context,
            summary: String = "",
            onSend: ((String) -> Unit)? = null
        ) {
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                hideQuickChatOverlay()

                val svc = instance ?: return@post
                val wm = (svc.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return@post
                quickChatWm = wm

                val dm = svc.resources.displayMetrics
                val cardWidth = (dm.widthPixels * 0.94f).toInt()

                val cardLayout = LinearLayout(svc).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = dpToPx(svc, 14f).toInt()
                    setPadding(pad, pad, pad, pad)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dpToPx(svc, 20f)
                        setColor(Color.parseColor("#F6121724")) // 墨黑毛玻璃
                        setStroke(dpToPx(svc, 1.2f).toInt(), Color.parseColor("#5076A0F8"))
                    }
                }

                // 1. 顶部标题栏
                val headerLayout = LinearLayout(svc).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dpToPx(svc, 8f).toInt()
                    }
                }

                val titleTv = TextView(svc).apply {
                    text = "🤖 AI 助手就地交流"
                    setTextColor(Color.parseColor("#FFFFFF"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                headerLayout.addView(titleTv)

                val closeTv = TextView(svc).apply {
                    text = "✕"
                    setTextColor(Color.parseColor("#90A4AE"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    gravity = Gravity.CENTER
                    val size = dpToPx(svc, 26f).toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#263242"))
                    }
                    setOnClickListener {
                        hideQuickChatOverlay()
                    }
                }
                headerLayout.addView(closeTv)
                cardLayout.addView(headerLayout)

                // 2. 状态简报提示
                if (summary.isNotBlank()) {
                    val summaryTv = TextView(svc).apply {
                        text = summary
                        setTextColor(Color.parseColor("#94A3B8"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        maxLines = 2
                        val hP = dpToPx(svc, 9f).toInt()
                        val vP = dpToPx(svc, 5f).toInt()
                        setPadding(hP, vP, hP, vP)
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dpToPx(svc, 10f)
                            setColor(Color.parseColor("#182133"))
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = dpToPx(svc, 8f).toInt()
                        }
                    }
                    cardLayout.addView(summaryTv)
                }

                // 3. 快捷指令横滑气泡
                val chipScroll = HorizontalScrollView(svc).apply {
                    isHorizontalScrollBarEnabled = false
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dpToPx(svc, 10f).toInt()
                    }
                }
                val chipRow = LinearLayout(svc).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                val quickActions = listOf(
                    "🎬 换一个视频",
                    "⛶ 全屏播放",
                    "⏸ 暂停/继续",
                    "⬅ 返回推荐首页",
                    "📱 授权完整悬浮窗"
                )

                fun sendQuery(text: String) {
                    hideQuickChatOverlay()
                    if (onSend != null) {
                        onSend.invoke(text)
                    } else {
                        executeDefaultFollowupGoal(svc, text)
                    }
                }

                for (act in quickActions) {
                    val chip = TextView(svc).apply {
                        text = act
                        setTextColor(Color.parseColor("#93C5FD"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        val hP = dpToPx(svc, 9f).toInt()
                        val vP = dpToPx(svc, 5f).toInt()
                        setPadding(hP, vP, hP, vP)
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dpToPx(svc, 12f)
                            setColor(Color.parseColor("#1E293B"))
                            setStroke(dpToPx(svc, 0.8f).toInt(), Color.parseColor("#3B82F6"))
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            marginEnd = dpToPx(svc, 6f).toInt()
                        }
                        setOnClickListener {
                            if (act.contains("完整悬浮窗")) {
                                FloatingAssistantManager.requestOverlayPermission(svc)
                            } else {
                                val cleanPrompt = act.replace(Regex("^[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u2600-\\u26FF\\u2700-\\u27BF]+\\s*"), "")
                                sendQuery(cleanPrompt)
                            }
                        }
                    }
                    chipRow.addView(chip)
                }
                chipScroll.addView(chipRow)
                cardLayout.addView(chipScroll)

                // 4. 输入框与发送按钮
                val inputLayout = LinearLayout(svc).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                val editText = EditText(svc).apply {
                    hint = "输入你想让 AI 做的事..."
                    setHintTextColor(Color.parseColor("#64748B"))
                    setTextColor(Color.parseColor("#F1F5F9"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    val hP = dpToPx(svc, 10f).toInt()
                    val vP = dpToPx(svc, 7f).toInt()
                    setPadding(hP, vP, hP, vP)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dpToPx(svc, 10f)
                        setColor(Color.parseColor("#1E293B"))
                        setStroke(dpToPx(svc, 1f).toInt(), Color.parseColor("#334155"))
                    }
                    isSingleLine = true
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                inputLayout.addView(editText)

                val sendBtn = TextView(svc).apply {
                    text = "发送"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    val hP = dpToPx(svc, 13f).toInt()
                    val vP = dpToPx(svc, 7f).toInt()
                    setPadding(hP, vP, hP, vP)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dpToPx(svc, 10f)
                        setColor(Color.parseColor("#2563EB"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = dpToPx(svc, 7f).toInt()
                    }
                    setOnClickListener {
                        val input = editText.text?.toString()?.trim() ?: ""
                        if (input.isNotEmpty()) {
                            sendQuery(input)
                        }
                    }
                }
                inputLayout.addView(sendBtn)
                cardLayout.addView(inputLayout)

                quickChatView = cardLayout

                val params = WindowManager.LayoutParams(
                    cardWidth,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = dpToPx(svc, 28f).toInt()
                    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                }

                try {
                    wm.addView(cardLayout, params)
                } catch (e: Exception) {
                    Log.e(TAG, "showQuickChatOverlay failed to add view", e)
                    quickChatView = null
                    quickChatWm = null
                }
            }
        }

        private fun executeDefaultFollowupGoal(context: Context, prompt: String) {
            ScreenActionHud.show(context, "正在执行: \"$prompt\"...")
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val orchestrator = AIAgentApplication.instance.orchestrator
                    orchestrator.processGoal(
                        userPrompt = prompt,
                        onStepUpdated = { steps ->
                            steps.lastOrNull()?.let { lastStep ->
                                ScreenActionHud.update(lastStep.detail.ifBlank { lastStep.title })
                            }
                        }
                    )
                } catch (e: Exception) {
                    ScreenActionHud.failed("执行出错: ${e.message}")
                }
            }
        }

        private fun dpToPx(context: Context, dp: Float): Float {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
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
        hideQuickChatOverlay()
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
