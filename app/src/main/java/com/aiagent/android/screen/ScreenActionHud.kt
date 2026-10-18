package com.aiagent.android.screen

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 屏幕操作全局灵动状态条 (Codex-style Screen Action HUD)。
 *
 * 当 AI 正在跨应用操作屏幕、调用应用、分析界面或模拟手势时，在屏幕顶端悬浮展示
 * 极简、高辨识度的毛玻璃状态胶囊，实时反馈当前正在执行的具体动作与步骤，
 * 并提供一键【停止】急停按钮，彻底消除用户在 AI 自动化操作时的失控感与不确定感。
 */
object ScreenActionHud {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var hudView: View? = null

    private var iconContainer: FrameLayout? = null
    private var progressBar: ProgressBar? = null
    private var checkIcon: TextView? = null
    private var statusTextView: TextView? = null
    private var stopButton: TextView? = null

    private var currentOnStop: (() -> Unit)? = null
    private var autoDismissRunnable: Runnable? = null

    /**
     * 显示正在执行的 HUD 状态条。
     *
     * @param context 运行上下文
     * @param initialText 初始步骤文字，例如：“正在启动哔哩哔哩...”
     * @param onStopClicked 用户点击【停止】按钮时的回调
     */
    fun show(context: Context, initialText: String, onStopClicked: (() -> Unit)? = null) {
        mainHandler.post {
            currentOnStop = onStopClicked
            autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }

            if (hudView != null && windowManager != null) {
                updateText(initialText)
                setWorkingMode()
                return@post
            }

            createAndAttachView(context, initialText)
        }
    }

    /**
     * 更新当前动作文字（平滑切换）。
     */
    fun update(stepText: String) {
        mainHandler.post {
            updateText(stepText)
            setWorkingMode()
        }
    }

    /**
     * 标记任务完成，展示绿色对勾与成功文案，并在指定延时后自动优雅淡出。
     */
    fun complete(message: String, autoDismissDelayMs: Long = 2500L) {
        mainHandler.post {
            updateText(message)
            setCompletedMode()

            autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
            val dismissTask = Runnable { dismiss() }
            autoDismissRunnable = dismissTask
            mainHandler.postDelayed(dismissTask, autoDismissDelayMs)
        }
    }

    /**
     * 标记任务中止或失败。
     */
    fun failed(message: String, autoDismissDelayMs: Long = 2500L) {
        mainHandler.post {
            updateText(message)
            setFailedMode()

            autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
            val dismissTask = Runnable { dismiss() }
            autoDismissRunnable = dismissTask
            mainHandler.postDelayed(dismissTask, autoDismissDelayMs)
        }
    }

    /**
     * 立即移除并隐藏 HUD 状态条。
     */
    fun dismiss() {
        mainHandler.post {
            autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
            autoDismissRunnable = null
            currentOnStop = null

            val view = hudView
            val wm = windowManager
            if (view != null && wm != null) {
                runCatching { wm.removeViewImmediate(view) }
            }
            hudView = null
            windowManager = null
            iconContainer = null
            progressBar = null
            checkIcon = null
            statusTextView = null
            stopButton = null
        }
    }

    fun isShowing(): Boolean = hudView != null

    private fun updateText(text: String) {
        statusTextView?.text = text
    }

    private fun setWorkingMode() {
        progressBar?.visibility = View.VISIBLE
        checkIcon?.visibility = View.GONE
        stopButton?.visibility = View.VISIBLE
    }

    private fun setCompletedMode() {
        progressBar?.visibility = View.GONE
        checkIcon?.apply {
            visibility = View.VISIBLE
            text = "✓"
            setTextColor(Color.parseColor("#4CAF50"))
        }
        stopButton?.visibility = View.GONE
    }

    private fun setFailedMode() {
        progressBar?.visibility = View.GONE
        checkIcon?.apply {
            visibility = View.VISIBLE
            text = "⏹"
            setTextColor(Color.parseColor("#EF5350"))
        }
        stopButton?.visibility = View.GONE
    }

    private fun createAndAttachView(context: Context, initialText: String) {
        // 优先使用已激活的 AccessibilityService 上下文，享有 TYPE_ACCESSIBILITY_OVERLAY 优势（无需额外悬浮窗授权）
        val svc = ScreenAssistantAccessibilityService.instance
        val targetContext = svc ?: context.applicationContext
        val wm = (targetContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return
        windowManager = wm

        val windowType = if (svc != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Settings.canDrawOverlays(targetContext)) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            // 权限不足时不展示 HUD，避免崩溃
            return
        }

        val pillLayout = LinearLayout(targetContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val horizontalPadding = dpToPx(targetContext, 14f).toInt()
            val verticalPadding = dpToPx(targetContext, 8f).toInt()
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)

            // Codex-style 墨黑半透明毛玻璃胶囊背景
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(targetContext, 24f)
                setColor(Color.parseColor("#F0121418")) // 94% 墨黑
                setStroke(dpToPx(targetContext, 1.2f).toInt(), Color.parseColor("#384252")) // 细腻微光边框
            }
        }

        // 1. 左侧动态指示器容器
        val iconBox = FrameLayout(targetContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(targetContext, 20f).toInt(),
                dpToPx(targetContext, 20f).toInt()
            )
        }
        val spinner = ProgressBar(targetContext, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val staticIcon = TextView(targetContext).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        iconBox.addView(spinner)
        iconBox.addView(staticIcon)
        pillLayout.addView(iconBox)

        // 2. 中间步骤文本
        val textView = TextView(targetContext).apply {
            text = initialText
            setTextColor(Color.parseColor("#F0F4F8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            val margin = dpToPx(targetContext, 10f).toInt()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(margin, 0, margin, 0)
            }
        }
        pillLayout.addView(textView)

        // 3. 右侧急停【停止】按钮
        val btnStop = TextView(targetContext).apply {
            text = "停止"
            setTextColor(Color.parseColor("#FFCDD2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            val btnHPadding = dpToPx(targetContext, 10f).toInt()
            val btnVPadding = dpToPx(targetContext, 4f).toInt()
            setPadding(btnHPadding, btnVPadding, btnHPadding, btnVPadding)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(targetContext, 12f)
                setColor(Color.parseColor("#4A1B1B")) // 暗红底
                setStroke(dpToPx(targetContext, 0.8f).toInt(), Color.parseColor("#D32F2F")) // 鲜红框
            }
            setOnClickListener {
                currentOnStop?.invoke()
                updateText("已停止自动化操作")
                setFailedMode()
                val dismissTask = Runnable { dismiss() }
                autoDismissRunnable = dismissTask
                mainHandler.postDelayed(dismissTask, 1200L)
            }
        }
        pillLayout.addView(btnStop)

        // 保存引用
        iconContainer = iconBox
        progressBar = spinner
        checkIcon = staticIcon
        statusTextView = textView
        stopButton = btnStop
        hudView = pillLayout

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpToPx(targetContext, 54f).toInt() // 居于状态栏下方
        }

        try {
            wm.addView(pillLayout, params)
        } catch (_: Exception) {
            hudView = null
            windowManager = null
        }
    }

    private fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
    }
}
