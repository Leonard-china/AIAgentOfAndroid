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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 屏幕操作全局灵动状态条与任务完成通知横幅 (Screen Action HUD)。
 *
 * 1. 运行态 (Working Mode)：
 *    在屏幕顶端悬浮墨黑半透明毛玻璃灵动胶囊，实时显示当前动作与步骤，
 *    提供【停止】急停按钮与【✕】随手关闭按钮，绝不遮挡操作。
 *
 * 2. 完成态 (Completion Mode / 任务执行报告)：
 *    任务结束时自动转换为交互式信息通知横幅，清晰呈现任务标题与执行报告，
 *    提供【💬 交流】就地展开悬浮对话按钮，以及醒目的【✕】关闭按钮，
 *    用户可随手点击【✕】一键关闭，彻底消除标志常驻关不掉的困扰。
 */
object ScreenActionHud {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var hudView: View? = null

    private var currentOnStop: (() -> Unit)? = null
    private var autoDismissRunnable: Runnable? = null

    // 运行态子 View 引用
    private var progressBar: ProgressBar? = null
    private var checkIcon: TextView? = null
    private var statusTextView: TextView? = null
    private var stopButton: TextView? = null
    private var closeButton: TextView? = null

    /**
     * 显示正在执行的 HUD 状态条。
     */
    fun show(context: Context, initialText: String, onStopClicked: (() -> Unit)? = null) {
        runOnMain {
            currentOnStop = onStopClicked
            cancelAutoDismiss()

            if (hudView != null && windowManager != null && statusTextView != null) {
                updateText(initialText)
                setWorkingMode()
                return@runOnMain
            }

            dismissInternal()
            currentOnStop = onStopClicked
            createAndAttachWorkingView(context, initialText)
        }
    }

    /**
     * 更新当前动作文字（平滑切换）。
     */
    fun update(stepText: String) {
        runOnMain {
            if (hudView == null || statusTextView == null) return@runOnMain
            updateText(stepText)
            setWorkingMode()
        }
    }

    /**
     * 任务完成统一切换：展示具有任务报告、就地交流按钮与明确【✕】关闭按钮的交互横幅。
     *
     * @param context 上下文
     * @param title 标题（如“🎬 视频已开始播放”或“✅ 任务已圆满完成”）
     * @param message 详细任务报告摘要
     * @param autoDismissMs 自动淡出时间（毫秒），默认 8000ms
     * @param onOpenChat 点击横幅或“💬 交流”按钮时触发就地交流
     */
    fun showCompletion(
        context: Context,
        title: String = "AI 智能管家",
        message: String,
        autoDismissMs: Long = 8500L,
        onOpenChat: (() -> Unit)? = null
    ) {
        runOnMain {
            dismissInternal()

            val svc = ScreenAssistantAccessibilityService.instance
            val targetContext = svc ?: context.applicationContext
            val wm = (targetContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return@runOnMain
            windowManager = wm

            val windowType = if (svc != null) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else if (Settings.canDrawOverlays(targetContext)) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                return@runOnMain
            }

            val dm = targetContext.resources.displayMetrics
            val bannerWidth = (dm.widthPixels * 0.94f).toInt()

            val banner = LinearLayout(targetContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val hPad = dpToPx(targetContext, 14f).toInt()
                val vPad = dpToPx(targetContext, 10f).toInt()
                setPadding(hPad, vPad, hPad, vPad)

                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(targetContext, 18f)
                    setColor(Color.parseColor("#F4121622")) // 95% 墨黑毛玻璃
                    setStroke(dpToPx(targetContext, 1.2f).toInt(), Color.parseColor("#5076A0F8")) // 微蓝光泽边框
                }

                // 点击横幅主体触发就地交流
                setOnClickListener {
                    dismissInternal()
                    onOpenChat?.invoke()
                }
            }

            // 1. 左侧图标
            val isVideo = title.contains("视频") || title.contains("播放")
            val isLock = title.contains("长时记忆") || title.contains("密码")
            val iconEmoji = when {
                isVideo -> "🎬"
                isLock -> "🔒"
                else -> "🤖"
            }
            val iconView = TextView(targetContext).apply {
                text = iconEmoji
                textSize = 17f
                gravity = Gravity.CENTER
                val size = dpToPx(targetContext, 34f).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = dpToPx(targetContext, 10f).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(if (isVideo) "#1C2E4A" else "#263855"))
                }
            }
            banner.addView(iconView)

            // 2. 中间文字（标题 + 任务简报）
            val textBox = LinearLayout(targetContext).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val titleView = TextView(targetContext).apply {
                text = title
                setTextColor(Color.parseColor("#FFFFFF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val msgView = TextView(targetContext).apply {
                text = message
                setTextColor(Color.parseColor("#CBD5E1"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dpToPx(targetContext, 2f).toInt(), 0, 0)
            }
            textBox.addView(titleView)
            textBox.addView(msgView)
            banner.addView(textBox)

            // 3. 右侧操作区：【💬 交流】按钮 + 【✕】关闭按钮
            val actionBox = LinearLayout(targetContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dpToPx(targetContext, 8f).toInt()
                }
            }

            if (onOpenChat != null) {
                val chatBtn = TextView(targetContext).apply {
                    text = "💬 交流"
                    setTextColor(Color.parseColor("#8AB4F8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    val btnHPad = dpToPx(targetContext, 8f).toInt()
                    val btnVPad = dpToPx(targetContext, 5f).toInt()
                    setPadding(btnHPad, btnVPad, btnHPad, btnVPad)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dpToPx(targetContext, 12f)
                        setColor(Color.parseColor("#204A85"))
                        setStroke(dpToPx(targetContext, 0.8f).toInt(), Color.parseColor("#508AB4F8"))
                    }
                    setOnClickListener {
                        dismissInternal()
                        onOpenChat.invoke()
                    }
                }
                actionBox.addView(chatBtn)
            }

            // 明确的【✕】关闭按钮，随手一戳立即关掉
            val closeBtn = TextView(targetContext).apply {
                text = "✕"
                setTextColor(Color.parseColor("#90A4AE"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                val size = dpToPx(targetContext, 26f).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = dpToPx(targetContext, 6f).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#252E3E"))
                }
                setOnClickListener {
                    dismissInternal()
                }
            }
            actionBox.addView(closeBtn)
            banner.addView(actionBox)

            hudView = banner

            val params = WindowManager.LayoutParams(
                bannerWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dpToPx(targetContext, 46f).toInt()
            }

            try {
                wm.addView(banner, params)
                scheduleAutoDismiss(autoDismissMs)
            } catch (_: Exception) {
                hudView = null
                windowManager = null
            }
        }
    }

    /**
     * 保持向后兼容：调用 complete 即展示统一的完成通知横幅。
     */
    fun complete(message: String, autoDismissDelayMs: Long = 8000L) {
        val svc = ScreenAssistantAccessibilityService.instance
        if (svc != null) {
            showCompletion(
                context = svc,
                title = "✅ 任务已完成",
                message = message,
                autoDismissMs = autoDismissDelayMs
            )
        } else {
            runOnMain {
                updateText(message)
                setCompletedMode()
                scheduleAutoDismiss(autoDismissDelayMs)
            }
        }
    }

    /**
     * 保持向后兼容：别名方法 showCompletionBanner
     */
    fun showCompletionBanner(
        context: Context,
        title: String = "AI 智能管家",
        message: String,
        autoDismissMs: Long = 8000L,
        onOpenChat: () -> Unit
    ) {
        showCompletion(context, title, message, autoDismissMs, onOpenChat)
    }

    /**
     * 标记任务中止或失败。
     */
    fun failed(message: String, autoDismissDelayMs: Long = 2500L) {
        runOnMain {
            updateText(message)
            setFailedMode()
            scheduleAutoDismiss(autoDismissDelayMs)
        }
    }

    /**
     * 立即移除并隐藏 HUD 状态条。
     */
    fun dismiss() {
        runOnMain {
            dismissInternal()
        }
    }

    /** Hide only our HUD while acquiring a frame, then restore the same view if still attached. */
    suspend fun <T> withoutOverlay(block: suspend () -> T): T {
        val hidden = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            hudView?.also { it.visibility = View.INVISIBLE }
        }
        return try {
            kotlinx.coroutines.delay(100)
            block()
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.Main.immediate) {
                if (hudView === hidden) hidden?.visibility = View.VISIBLE
            }
        }
    }

    fun isShowing(): Boolean = hudView != null

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun cancelAutoDismiss() {
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        autoDismissRunnable = null
    }

    private fun scheduleAutoDismiss(delayMs: Long) {
        cancelAutoDismiss()
        val task = Runnable { dismissInternal() }
        autoDismissRunnable = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun dismissInternal() {
        cancelAutoDismiss()
        currentOnStop = null

        val view = hudView
        val wm = windowManager
        if (view != null && wm != null) {
            runCatching { wm.removeViewImmediate(view) }
        }
        hudView = null
        windowManager = null
        progressBar = null
        checkIcon = null
        statusTextView = null
        stopButton = null
        closeButton = null
    }

    private fun updateText(text: String) {
        statusTextView?.text = text
    }

    private fun setWorkingMode() {
        progressBar?.visibility = View.VISIBLE
        checkIcon?.visibility = View.GONE
        stopButton?.visibility = View.VISIBLE
        closeButton?.visibility = View.VISIBLE
    }

    private fun setCompletedMode() {
        progressBar?.visibility = View.GONE
        checkIcon?.apply {
            visibility = View.VISIBLE
            text = "✓"
            setTextColor(Color.parseColor("#4CAF50"))
        }
        stopButton?.visibility = View.GONE
        closeButton?.visibility = View.VISIBLE
    }

    private fun setFailedMode() {
        progressBar?.visibility = View.GONE
        checkIcon?.apply {
            visibility = View.VISIBLE
            text = "⏹"
            setTextColor(Color.parseColor("#EF5350"))
        }
        stopButton?.visibility = View.GONE
        closeButton?.visibility = View.VISIBLE
    }

    private fun createAndAttachWorkingView(context: Context, initialText: String) {
        val svc = ScreenAssistantAccessibilityService.instance
        val targetContext = svc ?: context.applicationContext
        val wm = (targetContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return
        windowManager = wm

        val windowType = if (svc != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Settings.canDrawOverlays(targetContext)) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            return
        }

        val pillLayout = LinearLayout(targetContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val horizontalPadding = dpToPx(targetContext, 12f).toInt()
            val verticalPadding = dpToPx(targetContext, 7f).toInt()
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)

            // Codex-style 墨黑半透明毛玻璃胶囊背景
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(targetContext, 24f)
                setColor(Color.parseColor("#F0121418")) // 94% 墨黑
                setStroke(dpToPx(targetContext, 1.2f).toInt(), Color.parseColor("#384252")) // 细腻微光边框
            }
        }

        // 1. 左侧指示器容器
        val iconBox = FrameLayout(targetContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(targetContext, 18f).toInt(),
                dpToPx(targetContext, 18f).toInt()
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
            textSize = 13f
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            val margin = dpToPx(targetContext, 8f).toInt()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(margin, 0, margin, 0)
            }
        }
        pillLayout.addView(textView)

        // 3. 右侧操作区：【停止】按钮 + 【✕】关闭按钮
        val btnStop = TextView(targetContext).apply {
            text = "停止"
            setTextColor(Color.parseColor("#FFCDD2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            val btnHPadding = dpToPx(targetContext, 8f).toInt()
            val btnVPadding = dpToPx(targetContext, 3f).toInt()
            setPadding(btnHPadding, btnVPadding, btnHPadding, btnVPadding)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(targetContext, 10f)
                setColor(Color.parseColor("#4A1B1B")) // 暗红底
                setStroke(dpToPx(targetContext, 0.8f).toInt(), Color.parseColor("#D32F2F")) // 鲜红框
            }
            setOnClickListener {
                currentOnStop?.invoke()
                updateText("已停止自动化操作")
                setFailedMode()
                scheduleAutoDismiss(1200L)
            }
        }
        pillLayout.addView(btnStop)

        // 随手一戳立即关闭按钮
        val btnClose = TextView(targetContext).apply {
            text = "✕"
            setTextColor(Color.parseColor("#78909C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            val size = dpToPx(targetContext, 22f).toInt()
            val mStart = dpToPx(targetContext, 5f).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = mStart
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#222A36"))
            }
            setOnClickListener {
                dismissInternal()
            }
        }
        pillLayout.addView(btnClose)

        progressBar = spinner
        checkIcon = staticIcon
        statusTextView = textView
        stopButton = btnStop
        closeButton = btnClose
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
            y = dpToPx(targetContext, 52f).toInt()
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
