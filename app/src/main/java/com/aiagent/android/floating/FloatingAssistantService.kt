package com.aiagent.android.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.aiagent.android.AIAgentApplication
import com.aiagent.android.R
import com.aiagent.android.model.ActionPlan
import com.aiagent.android.model.AiMessage
import com.aiagent.android.model.PlanStatus
import com.aiagent.android.model.TaskStep
import com.aiagent.android.model.formatBytes
import com.aiagent.android.tools.TrashOperationExecutor
import com.aiagent.android.ui.AIAgentTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class FloatingState {
    EXPANDED,       // 展开对话窗态：半屏毛玻璃卡片（约52%屏高），不遮挡全屏，随时对齐任务
    COLLAPSED_PILL, // 灵动微型胶囊：48dp x 36dp 椭圆，自由拖拽移动
    DOCKED_TAB,     // 极窄贴边把手：仅占边缘 16dp 宽度，半透明磨砂拉手，零视线遮挡
    HIDDEN          // 完全隐藏态：屏幕不占用任何像素，通知栏常驻随时唤出
}

class FloatingAssistantService : Service() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "ai_floating_service"
        const val NOTIFICATION_ID = 2001

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var lifecycleOwner: FloatingLifecycleOwner
    private lateinit var notificationManager: NotificationManager

    private val messages = mutableStateListOf<AiMessage>()
    private val activeSteps = mutableStateListOf<TaskStep>()
    private var isProcessing by mutableStateOf(false)

    // Current State
    private var currentState by mutableStateOf(FloatingState.EXPANDED)
    private var isDockedOnLeft by mutableStateOf(false)
    private var currentX: Int = 0
    private var currentY: Int = 0

    private lateinit var expandedLayoutParams: WindowManager.LayoutParams
    private lateinit var pillLayoutParams: WindowManager.LayoutParams
    private lateinit var dockedLayoutParams: WindowManager.LayoutParams
    private lateinit var hiddenLayoutParams: WindowManager.LayoutParams

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        lifecycleOwner = FloatingLifecycleOwner()

        createNotificationChannel()
        initLayoutParams()
        initMessages()
        initView()

        // startForegroundService 要求 5 秒内进入前台；同时保证悬浮窗与“随时唤出”
        // 通知不会因后台限制被系统快速回收。
        startForeground(NOTIFICATION_ID, buildNotification(currentState))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            FloatingAssistantManager.ACTION_SHOW -> {
                updateFloatingState(FloatingState.EXPANDED)
            }
            FloatingAssistantManager.ACTION_HIDE -> {
                updateFloatingState(FloatingState.HIDDEN)
            }
            FloatingAssistantManager.ACTION_DOCK -> {
                updateFloatingState(FloatingState.DOCKED_TAB)
            }
            FloatingAssistantManager.ACTION_TOGGLE -> {
                if (currentState == FloatingState.EXPANDED) {
                    updateFloatingState(FloatingState.DOCKED_TAB)
                } else {
                    updateFloatingState(FloatingState.EXPANDED)
                }
            }
            FloatingAssistantManager.ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        lifecycleOwner.destroy()
        notificationManager.cancel(NOTIFICATION_ID)
        if (::composeView.isInitialized) {
            try {
                windowManager.removeView(composeView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun initLayoutParams() {
        val dm = resources.displayMetrics
        val density = dm.density

        // Default initial coordinates
        currentX = dm.widthPixels - (50 * density).toInt()
        currentY = dm.heightPixels / 3

        // 1. Expanded Dialog LayoutParams (Centered, sleek 52% screen height, 88% width)
        val expandedWidth = minOf((dm.widthPixels * 0.88f).toInt(), (400 * density).toInt())
        val expandedHeight = minOf((dm.heightPixels * 0.52f).toInt(), (560 * density).toInt())

        expandedLayoutParams = WindowManager.LayoutParams(
            expandedWidth,
            expandedHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        // 2. Collapsed Pill LayoutParams (Compact 48dp x 38dp)
        pillLayoutParams = WindowManager.LayoutParams(
            (48 * density).toInt(),
            (38 * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = currentX
            y = currentY
        }

        // 3. Docked Tab LayoutParams (24dp touch area on edge, 60dp height)
        val dockTouchWidth = (24 * density).toInt()
        dockedLayoutParams = WindowManager.LayoutParams(
            dockTouchWidth,
            (60 * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (isDockedOnLeft) 0 else (dm.widthPixels - dockTouchWidth)
            y = currentY
        }

        // 4. Hidden LayoutParams (0 width/height, non-touchable)
        hiddenLayoutParams = WindowManager.LayoutParams(
            0,
            0,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun updateFloatingState(newState: FloatingState) {
        currentState = newState
        val dm = resources.displayMetrics
        val density = dm.density
        val dockTouchWidth = (24 * density).toInt()

        when (newState) {
            FloatingState.EXPANDED -> {
                composeView.visibility = View.VISIBLE
                windowManager.updateViewLayout(composeView, expandedLayoutParams)
            }
            FloatingState.COLLAPSED_PILL -> {
                composeView.visibility = View.VISIBLE
                pillLayoutParams.x = currentX.coerceIn(0, dm.widthPixels - (48 * density).toInt())
                pillLayoutParams.y = currentY.coerceIn((50 * density).toInt(), dm.heightPixels - (120 * density).toInt())
                windowManager.updateViewLayout(composeView, pillLayoutParams)
            }
            FloatingState.DOCKED_TAB -> {
                composeView.visibility = View.VISIBLE
                dockedLayoutParams.x = if (isDockedOnLeft) 0 else (dm.widthPixels - dockTouchWidth)
                dockedLayoutParams.y = currentY.coerceIn((60 * density).toInt(), dm.heightPixels - (140 * density).toInt())
                windowManager.updateViewLayout(composeView, dockedLayoutParams)
            }
            FloatingState.HIDDEN -> {
                composeView.visibility = View.GONE
                windowManager.updateViewLayout(composeView, hiddenLayoutParams)
                Toast.makeText(this, "AI 悬浮窗已收起，下拉通知栏可随时唤出", Toast.LENGTH_SHORT).show()
            }
        }

        updateNotification(newState)
    }

    private fun initMessages() {
        messages.add(
            AiMessage(
                sender = "ASSISTANT",
                content = "👋 您好！AI 助手悬浮窗已开启。您可以在手机任意应用界面向我发送指令，例如：“帮我点设置”、“向下滑动”、“查看当前屏幕”或“排查重复照片”。"
            )
        )
    }

    private fun initView() {
        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                AIAgentTheme {
                    FloatingContent()
                }
            }
        }

        val gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (currentState != FloatingState.EXPANDED) {
                    updateFloatingState(FloatingState.EXPANDED)
                    return true
                }
                return false
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (currentState != FloatingState.EXPANDED) {
                    updateFloatingState(FloatingState.EXPANDED)
                    return true
                }
                return false
            }
        })

        // Set touch listener for dragging and outside tapping
        composeView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isMoved = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                if (gestureDetector.onTouchEvent(event)) {
                    return true
                }

                val dm = resources.displayMetrics
                val density = dm.density

                when (currentState) {
                    FloatingState.EXPANDED -> {
                        if (event.action == MotionEvent.ACTION_OUTSIDE) {
                            // Tapped outside expanded window -> auto dock to edge tab without blocking view!
                            updateFloatingState(FloatingState.DOCKED_TAB)
                            return true
                        }
                        return false
                    }

                    FloatingState.DOCKED_TAB -> {
                        val slop = android.view.ViewConfiguration.get(this@FloatingAssistantService).scaledTouchSlop.coerceAtLeast((20 * density).toInt())
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                initialY = dockedLayoutParams.y
                                initialTouchX = event.rawX
                                initialTouchY = event.rawY
                                isMoved = false
                                return true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = event.rawX - initialTouchX
                                val dy = event.rawY - initialTouchY
                                if (abs(dy) > slop) {
                                    isMoved = true
                                    currentY = (initialY + dy).toInt().coerceIn(
                                        (60 * density).toInt(),
                                        dm.heightPixels - (140 * density).toInt()
                                    )
                                    dockedLayoutParams.y = currentY
                                    windowManager.updateViewLayout(composeView, dockedLayoutParams)
                                }
                                // Dragged inwards horizontally -> pull out to pill
                                val pulledInward = if (isDockedOnLeft) dx > (30 * density) else dx < -(30 * density)
                                if (pulledInward) {
                                    currentX = (event.rawX - 24 * density).toInt()
                                    updateFloatingState(FloatingState.COLLAPSED_PILL)
                                    return true
                                }
                                return true
                            }
                            MotionEvent.ACTION_UP -> {
                                val totalDist = kotlin.math.hypot(
                                    (event.rawX - initialTouchX).toDouble(),
                                    (event.rawY - initialTouchY).toDouble()
                                )
                                if (totalDist <= slop || !isMoved) {
                                    // Tapped edge tab -> expand
                                    updateFloatingState(FloatingState.EXPANDED)
                                }
                                return true
                            }
                        }
                    }

                    FloatingState.COLLAPSED_PILL -> {
                        val slop = android.view.ViewConfiguration.get(this@FloatingAssistantService).scaledTouchSlop.coerceAtLeast((20 * density).toInt())
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                initialX = pillLayoutParams.x
                                initialY = pillLayoutParams.y
                                initialTouchX = event.rawX
                                initialTouchY = event.rawY
                                isMoved = false
                                return true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = event.rawX - initialTouchX
                                val dy = event.rawY - initialTouchY
                                if (abs(dx) > slop || abs(dy) > slop) {
                                    isMoved = true
                                    currentX = (initialX + dx).toInt()
                                    currentY = (initialY + dy).toInt().coerceIn(
                                        (50 * density).toInt(),
                                        dm.heightPixels - (120 * density).toInt()
                                    )
                                    pillLayoutParams.x = currentX
                                    pillLayoutParams.y = currentY
                                    windowManager.updateViewLayout(composeView, pillLayoutParams)
                                }
                                return true
                            }
                            MotionEvent.ACTION_UP -> {
                                val totalDist = kotlin.math.hypot(
                                    (event.rawX - initialTouchX).toDouble(),
                                    (event.rawY - initialTouchY).toDouble()
                                )
                                if (totalDist <= slop || !isMoved) {
                                    // Clicked pill -> expand window
                                    updateFloatingState(FloatingState.EXPANDED)
                                } else {
                                    // Released finger -> auto snap & dock to edge!
                                    isDockedOnLeft = currentX < (dm.widthPixels / 2)
                                    updateFloatingState(FloatingState.DOCKED_TAB)
                                }
                                return true
                            }
                        }
                    }

                    FloatingState.HIDDEN -> {
                        return false
                    }
                }
                return false
            }
        })

        // Default open as expanded dialog on first launch
        windowManager.addView(composeView, expandedLayoutParams)
    }

    private fun startNewChat() {
        messages.clear()
        activeSteps.clear()
        isProcessing = false
        messages.add(
            AiMessage(
                sender = "ASSISTANT",
                content = "✨ 已为您开启新对话！之前的会话上下文与历史任务已重置。您可以随时对我说出新的文件管理或屏幕操作需求。"
            )
        )
    }

    private fun handleUserPrompt(prompt: String) {
        val trimmed = prompt.trim().removePrefix("➕").removePrefix("📌").removePrefix("👁️").trim()
        when (trimmed) {
            "开新对话", "新建对话", "清空对话", "重新开始" -> {
                startNewChat()
                return
            }
            "贴边隐藏", "贴边", "收起", "靠边" -> {
                updateFloatingState(FloatingState.DOCKED_TAB)
                return
            }
            "完全隐藏", "隐藏悬浮窗", "隐身", "隐藏" -> {
                updateFloatingState(FloatingState.HIDDEN)
                return
            }
            "打开悬浮窗", "唤出悬浮窗", "显示悬浮窗" -> {
                updateFloatingState(FloatingState.EXPANDED)
                return
            }
        }

        val currentHistory = messages.toList()
        messages.add(AiMessage(sender = "USER", content = prompt))
        isProcessing = true
        activeSteps.clear()

        serviceScope.launch {
            try {
                val responseMsg = AIAgentApplication.instance.orchestrator.processGoal(
                    userPrompt = prompt,
                    history = currentHistory,
                    onStepUpdated = { steps ->
                        activeSteps.clear()
                        activeSteps.addAll(steps)
                    }
                )
                messages.add(responseMsg)
            } catch (e: Exception) {
                messages.add(
                    AiMessage(
                        sender = "ASSISTANT",
                        content = "执行出现异常: ${e.message}"
                    )
                )
            } finally {
                isProcessing = false
            }
        }
    }

    private fun executeActionPlan(plan: ActionPlan) {
        serviceScope.launch {
            plan.status = PlanStatus.EXECUTING
            val app = AIAgentApplication.instance
            val result = app.trashExecutor.prepareTrashRequest(plan)

            when (result) {
                is TrashOperationExecutor.TrashResult.RequiresUserConsent -> {
                    Toast.makeText(
                        this@FloatingAssistantService,
                        "系统安全限制：请点击进入主应用确认系统回收站弹窗",
                        Toast.LENGTH_LONG
                    ).show()
                }
                is TrashOperationExecutor.TrashResult.Completed -> {
                    plan.status = PlanStatus.SUCCESS
                    messages.add(
                        AiMessage(
                            sender = "ASSISTANT",
                            content = "✅ 操作成功！已将 ${plan.targets.size} 个文件移入回收站，预计释放 ${formatBytes(result.releasedBytes)}。"
                        )
                    )
                }
                is TrashOperationExecutor.TrashResult.Failed -> {
                    plan.status = PlanStatus.FAILED
                    messages.add(
                        AiMessage(
                            sender = "ASSISTANT",
                            content = "❌ 操作失败: ${result.errorMessage}"
                        )
                    )
                }
                else -> {}
            }
        }
    }

    private fun cancelActionPlan(plan: ActionPlan) {
        plan.status = PlanStatus.CANCELLED
        messages.add(
            AiMessage(
                sender = "ASSISTANT",
                content = "已取消本次操作计划。"
            )
        )
    }

    @Composable
    private fun FloatingContent() {
        when (currentState) {
            FloatingState.DOCKED_TAB -> {
                // Form 1: Ultra-slim Docked Tab on the edge (14dp visible tab, 24dp touch target)
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = if (isDockedOnLeft) Alignment.CenterStart else Alignment.CenterEnd
                ) {
                    Surface(
                        onClick = { updateFloatingState(FloatingState.EXPANDED) },
                        shape = if (isDockedOnLeft) {
                            RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)
                        } else {
                            RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
                        },
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .width(14.dp)
                            .height(52.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(20.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.85f))
                            )
                        }
                    }
                }
            }

            FloatingState.COLLAPSED_PILL -> {
                // Form 2: Sleek Floating Pill (48dp x 36dp with SmartToy + live cyan dot)
                Surface(
                    onClick = { updateFloatingState(FloatingState.EXPANDED) },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = "AI 助手",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E676))
                        )
                    }
                }
            }

            FloatingState.EXPANDED -> {
                // Form 3: Modern Glassmorphism Floating Card (52% height, 88% width)
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 14.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(22.dp))
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Header Bar
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.SmartToy,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "AI 智能管家",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF00E676))
                                    )
                                }

                                // 1. New Chat button
                                IconButton(
                                    onClick = { startNewChat() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "开新对话",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // 2. Dock to edge button (贴边隐藏)
                                IconButton(
                                    onClick = { updateFloatingState(FloatingState.DOCKED_TAB) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PushPin,
                                        contentDescription = "贴边隐藏",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }

                                // 3. Minimize to pill button (最小化为小胶囊)
                                IconButton(
                                    onClick = { updateFloatingState(FloatingState.COLLAPSED_PILL) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Remove,
                                        contentDescription = "最小化为小胶囊",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // 4. Hide completely button (完全隐藏到通知栏)
                                IconButton(
                                    onClick = { updateFloatingState(FloatingState.HIDDEN) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VisibilityOff,
                                        contentDescription = "隐藏到后台",
                                        modifier = Modifier.size(17.dp)
                                    )
                                }

                                // 5. Close service button
                                IconButton(
                                    onClick = { stopSelf() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "关闭悬浮窗",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }
                        }

                        // Quick Chips Row
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            val quickChips = listOf(
                                "➕ 开新对话" to "开新对话",
                                "📌 贴边隐藏" to "贴边隐藏",
                                "👁️ 完全隐身" to "完全隐藏",
                                "🖥️ 看当前屏幕" to "查看当前屏幕有什么",
                                "⬇️ 向下滑动" to "向下滑动屏幕",
                                "⬆️ 向上滑动" to "向上滑动屏幕",
                                "🔙 返回" to "点一下返回",
                                "⚙️ 存储设置" to "打开系统存储页面",
                                "📊 空间占用" to "看看手机里什么最占空间",
                                "🗑️ 重复照片" to "排查重复照片"
                            )
                            items(quickChips) { (label, prompt) ->
                                SuggestionChip(
                                    onClick = {
                                        if (!isProcessing) {
                                            handleUserPrompt(prompt)
                                        }
                                    },
                                    label = { Text(label, fontSize = 10.sp) },
                                    modifier = Modifier.height(26.dp)
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        // Message List
                        val listState = rememberLazyListState()
                        LaunchedEffect(messages.size, activeSteps.size) {
                            val total = messages.size + if (isProcessing && activeSteps.isNotEmpty()) 1 else 0
                            if (total > 0) {
                                listState.animateScrollToItem(total - 1)
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(messages) { msg ->
                                FloatingMessageItem(
                                    message = msg,
                                    onConfirmPlan = { executeActionPlan(it) },
                                    onCancelPlan = { cancelActionPlan(it) }
                                )
                            }

                            if (isProcessing && activeSteps.isNotEmpty()) {
                                item {
                                    FloatingStepsCard(activeSteps)
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        // Input Row
                        var inputText by remember { mutableStateOf("") }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                placeholder = { Text("输入指令（如：点设置、向下滑）...", fontSize = 11.sp) },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 36.dp, max = 72.dp),
                                shape = RoundedCornerShape(18.dp),
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                                singleLine = false,
                                maxLines = 3
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            FilledIconButton(
                                onClick = {
                                    val trimmed = inputText.trim()
                                    if (trimmed.isNotBlank() && !isProcessing) {
                                        inputText = ""
                                        handleUserPrompt(trimmed)
                                    }
                                },
                                enabled = inputText.isNotBlank() && !isProcessing,
                                modifier = Modifier.size(36.dp)
                            ) {
                                if (isProcessing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "发送",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            FloatingState.HIDDEN -> {
                // Invisible when hidden
                Box(modifier = Modifier.size(0.dp))
            }
        }
    }

    @Composable
    private fun FloatingMessageItem(
        message: AiMessage,
        onConfirmPlan: (ActionPlan) -> Unit,
        onCancelPlan: (ActionPlan) -> Unit
    ) {
        val isUser = message.sender == "USER"
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 14.dp,
                    topEnd = 14.dp,
                    bottomStart = if (isUser) 14.dp else 2.dp,
                    bottomEnd = if (isUser) 2.dp else 14.dp
                ),
                color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 2.dp,
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            // Plan Preview Card if any
            message.plan?.let { plan ->
                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    modifier = Modifier.widthIn(max = 280.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "📋 ${plan.title}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Text(
                            text = plan.description,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "目标文件：${plan.targets.size} 个 | 预计释放：${formatBytes(plan.estimatedReleasableBytes)}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (plan.status == PlanStatus.PREVIEW) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { onCancelPlan(plan) },
                                    modifier = Modifier.height(26.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp)
                                ) {
                                    Text("取消", fontSize = 10.sp)
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Button(
                                    onClick = { onConfirmPlan(plan) },
                                    modifier = Modifier.height(26.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp)
                                ) {
                                    Text("确认清理", fontSize = 10.sp)
                                }
                            }
                        } else {
                            Text(
                                text = "状态: ${plan.status.name}",
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun FloatingStepsCard(steps: List<TaskStep>) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 1.5.dp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "正在执行任务步骤...",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                steps.takeLast(3).forEach { step ->
                    Text(
                        text = "• ${step.title}: ${step.detail}",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "AI 桌面悬浮助手",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "提供 AI 助手桌面悬浮窗的常驻控制与随时唤出"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(state: FloatingState) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: FloatingState): Notification {
        val showIntent = PendingIntent.getService(
            this, 101,
            Intent(this, FloatingAssistantService::class.java).apply { action = FloatingAssistantManager.ACTION_SHOW },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val dockIntent = PendingIntent.getService(
            this, 102,
            Intent(this, FloatingAssistantService::class.java).apply { action = FloatingAssistantManager.ACTION_DOCK },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val hideIntent = PendingIntent.getService(
            this, 103,
            Intent(this, FloatingAssistantService::class.java).apply { action = FloatingAssistantManager.ACTION_HIDE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 104,
            Intent(this, FloatingAssistantService::class.java).apply { action = FloatingAssistantManager.ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleIntent = PendingIntent.getService(
            this, 100,
            Intent(this, FloatingAssistantService::class.java).apply { action = FloatingAssistantManager.ACTION_TOGGLE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (state == FloatingState.HIDDEN) "🤖 AI 助手已在后台隐身运行" else "🤖 AI 桌面悬浮助手已就绪"
        val contentText = if (state == FloatingState.HIDDEN) "悬浮窗已完全收起 · 点击即可在屏幕随时唤出" else "点击展开或收起 · 支持屏幕操作与文件清理"

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(toggleIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "✨ 唤出助手", showIntent)
            .addAction(0, "📌 贴边收纳", dockIntent)
            .addAction(0, "👁️ 完全隐身", hideIntent)
            .addAction(0, "✕ 关闭", stopIntent)
            .build()

        return notification
    }

    private class FloatingLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val myViewModelStore = ViewModelStore()
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        init {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore get() = myViewModelStore
        override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

        fun destroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            myViewModelStore.clear()
        }
    }
}
