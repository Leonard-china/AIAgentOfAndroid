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

        private val connection = kotlinx.coroutines.flow.MutableStateFlow(false)
        val connectionState: kotlinx.coroutines.flow.StateFlow<Boolean> = connection
        var isConnected: Boolean
            get() = connection.value
            private set(value) { connection.value = value }

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
            Log.w(TAG, "Emergency stop triggered!")
        }

        fun resetEmergencyStop() {
            emergencyStop.set(false)
            Log.i(TAG, "Emergency stop reset.")
        }

        fun isEmergencyStopped(): Boolean = emergencyStop.get()

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

        fun inspectScreenSummary(): String = ScreenSession().observe().toString()

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
                    "查看当前屏幕",
                    "向上滑动",
                    "返回上一页",
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
            AIAgentApplication.instance.conversation.submit(prompt)
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
        // Android interrupts feedback here; the service is still bound and usable.
        Log.i(TAG, "Accessibility feedback interrupted; service remains connected.")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        isConnected = false
        instance = null
        return super.onUnbind(intent)
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
