package com.aiagent.android.floating

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
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
import com.aiagent.android.model.ActionPlan
import com.aiagent.android.model.AiMessage
import com.aiagent.android.model.PlanStatus
import com.aiagent.android.model.TaskStep
import com.aiagent.android.model.formatBytes
import com.aiagent.android.tools.TrashOperationExecutor
import com.aiagent.android.ui.AIAgentTheme
import com.aiagent.android.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingAssistantService : Service() {

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var lifecycleOwner: FloatingLifecycleOwner

    private val messages = mutableStateListOf<AiMessage>()
    private val activeSteps = mutableStateListOf<TaskStep>()
    private var isProcessing by mutableStateOf(false)
    private var isExpanded by mutableStateOf(false)

    private lateinit var bubbleLayoutParams: WindowManager.LayoutParams
    private lateinit var expandedLayoutParams: WindowManager.LayoutParams

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        lifecycleOwner = FloatingLifecycleOwner()

        initLayoutParams()
        initMessages()
        initView()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        lifecycleOwner.destroy()
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

        // 1. Collapsed Bubble LayoutParams (Touchable, non-focusable, pass-through touches outside)
        bubbleLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - 180
            y = dm.heightPixels / 3
        }

        // 2. Expanded Chat Dialog LayoutParams (Focusable for soft keyboard typing, centered)
        val expandedWidth = (dm.widthPixels * 0.92f).toInt()
        val expandedHeight = (dm.heightPixels * 0.65f).toInt()

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

        // Set touch listener for dragging bubble and clicking outside expanded window
        composeView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isMoved = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                if (isExpanded) {
                    if (event.action == MotionEvent.ACTION_OUTSIDE) {
                        // Tapped outside expanded window -> minimize to bubble
                        setExpandedState(false)
                        return true
                    }
                    return false
                }

                // Bubble drag & click handling
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = bubbleLayoutParams.x
                        initialY = bubbleLayoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isMoved = true
                            bubbleLayoutParams.x = (initialX + dx).toInt()
                            bubbleLayoutParams.y = (initialY + dy).toInt()
                            windowManager.updateViewLayout(composeView, bubbleLayoutParams)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isMoved) {
                            // Clicked -> Expand window
                            setExpandedState(true)
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(composeView, bubbleLayoutParams)
    }

    private fun setExpandedState(expanded: Boolean) {
        isExpanded = expanded
        if (expanded) {
            windowManager.updateViewLayout(composeView, expandedLayoutParams)
        } else {
            windowManager.updateViewLayout(composeView, bubbleLayoutParams)
        }
    }

    private fun handleUserPrompt(prompt: String) {
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
                    Toast.makeText(this@FloatingAssistantService, "需系统弹窗确认，正在打开主页面...", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this@FloatingAssistantService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                }
                is TrashOperationExecutor.TrashResult.Completed -> {
                    plan.status = PlanStatus.SUCCESS
                    messages.add(
                        AiMessage(
                            sender = "ASSISTANT",
                            content = "✅ 移入回收站成功！已安全将 ${plan.targets.size} 个文件移入系统回收站，预计释放 ${formatBytes(result.releasedBytes)}。"
                        )
                    )
                }
                is TrashOperationExecutor.TrashResult.TargetChanged -> {
                    plan.status = PlanStatus.PREVIEW
                    Toast.makeText(this@FloatingAssistantService, "目标发生变动，请重新确认！", Toast.LENGTH_SHORT).show()
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
        if (!isExpanded) {
            // Collapsed Bubble UI
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .shadow(8.dp, CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = "AI 助手悬浮球",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(30.dp)
                )
            }
        } else {
            // Expanded Chat Window UI
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header Bar
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.SmartToy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "AI 助手桌面悬浮窗",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "在任意应用界面跨屏对话与操作",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    fontSize = 11.sp
                                )
                            }
                            // Minimize button
                            IconButton(
                                onClick = { setExpandedState(false) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Remove,
                                    contentDescription = "折叠为悬浮球",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            // Close button
                            IconButton(
                                onClick = { stopSelf() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "关闭悬浮窗",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Quick Chips Row
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val quickChips = listOf(
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
                                label = { Text(label, fontSize = 11.sp) },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

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
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
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

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Input Row
                    var inputText by remember { mutableStateOf("") }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = { Text("输入指令（如：点设置、向下滑）...", fontSize = 12.sp) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp, max = 80.dp),
                            shape = RoundedCornerShape(20.dp),
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                            singleLine = false,
                            maxLines = 3
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        FilledIconButton(
                            onClick = {
                                val trimmed = inputText.trim()
                                if (trimmed.isNotBlank() && !isProcessing) {
                                    inputText = ""
                                    handleUserPrompt(trimmed)
                                }
                            },
                            enabled = inputText.isNotBlank() && !isProcessing,
                            modifier = Modifier.size(40.dp)
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "发送",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
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
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
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
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "📋 ${plan.title}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = plan.description,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "目标文件：${plan.targets.size} 个 | 预计释放：${formatBytes(plan.estimatedReleasableBytes)}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (plan.status == PlanStatus.PREVIEW) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { onCancelPlan(plan) },
                                    modifier = Modifier.height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("取消", fontSize = 11.sp)
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Button(
                                    onClick = { onConfirmPlan(plan) },
                                    modifier = Modifier.height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("确认清理", fontSize = 11.sp)
                                }
                            }
                        } else {
                            Text(
                                text = "状态: ${plan.status.name}",
                                fontSize = 10.sp,
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
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "正在执行任务步骤...",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                steps.takeLast(3).forEach { step ->
                    Text(
                        text = "• ${step.title}: ${step.detail}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
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
