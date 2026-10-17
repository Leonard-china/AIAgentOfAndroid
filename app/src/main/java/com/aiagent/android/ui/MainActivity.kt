package com.aiagent.android.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aiagent.android.AIAgentApplication
import com.aiagent.android.model.*
import com.aiagent.android.tools.TrashOperationExecutor
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch

enum class AppTab(val title: String, val icon: ImageVector) {
    ASSISTANT("助手", Icons.AutoMirrored.Filled.Chat),
    STORAGE("存储", Icons.Default.PieChart),
    MEDIA("相册与文件", Icons.Default.PhotoLibrary),
    AUDIT("操作记录", Icons.Default.History),
    SETTINGS("设置", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var trashConsentLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var restoreConsentLauncher: ActivityResultLauncher<IntentSenderRequest>
    private var pendingConsentPlan: ActionPlan? = null
    private var pendingRestoreRecord: AuditRecord? = null

    // UI States
    private val messages = mutableStateListOf<AiMessage>()
    private val activeSteps = mutableStateListOf<TaskStep>()
    private var isProcessing by mutableStateOf(false)
    private var storageStat by mutableStateOf<StorageStat?>(null)
    private var isStorageLoading by mutableStateOf(false)
    private val mediaItems = mutableStateListOf<MediaItem>()
    private var isMediaLoading by mutableStateOf(false)
    private val auditRecords = mutableStateListOf<AuditRecord>()
    private var isPartialPhotoAccess by mutableStateOf(false)
    private var currentTab by mutableStateOf(AppTab.ASSISTANT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initLaunchers()
        checkAndRequestPermissions()

        // Initial welcome message
        if (messages.isEmpty()) {
            messages.add(
                AiMessage(
                    sender = "ASSISTANT",
                    content = "您好！我是您的 Android AI 文件与相册管理助手。我可以为您分析存储空间、查找超大视频、精准排查重复照片并整理相册。请问有什么可以帮您？"
                )
            )
        }

        refreshData()

        intent?.getStringExtra("prompt")?.let { prompt ->
            if (prompt.isNotBlank()) handleUserPrompt(prompt)
        }

        setContent {
            AIAgentTheme {

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            AppTab.values().forEach { tab ->
                                NavigationBarItem(
                                    selected = currentTab == tab,
                                    onClick = {
                                        currentTab = tab
                                        if (tab == AppTab.STORAGE) loadStorageData()
                                        if (tab == AppTab.MEDIA) loadMediaData()
                                        if (tab == AppTab.AUDIT) loadAuditData()
                                    },
                                    icon = { Icon(imageVector = tab.icon, contentDescription = tab.title) },
                                    label = { Text(tab.title) }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    when (currentTab) {
                        AppTab.ASSISTANT -> AssistantScreen(
                            messages = messages,
                            activeSteps = activeSteps,
                            isProcessing = isProcessing,
                            onSendMessage = { prompt -> handleUserPrompt(prompt) },
                            onNewChat = { startNewChat() },
                            onConfirmPlan = { plan -> executeActionPlan(plan) },
                            onCancelPlan = { plan -> cancelActionPlan(plan) },
                            modifier = Modifier.padding(innerPadding)
                        )
                        AppTab.STORAGE -> StorageScreen(
                            storageStat = storageStat,
                            isLoading = isStorageLoading,
                            onRefresh = { loadStorageData() },
                            onQuickAction = { prompt ->
                                currentTab = AppTab.ASSISTANT
                                handleUserPrompt(prompt)
                            },
                            modifier = Modifier.padding(innerPadding)
                        )
                        AppTab.MEDIA -> MediaScreen(
                            mediaItems = mediaItems,
                            isLoading = isMediaLoading,
                            isPartialPhotoAccess = isPartialPhotoAccess,
                            onRefresh = { loadMediaData() },
                            onTrashSelected = { selected ->
                                val targets = selected.map {
                                    ActionTarget(
                                        id = it.id,
                                        uriString = it.uriString,
                                        displayName = it.displayName,
                                        sizeBytes = it.sizeBytes,
                                        originalPath = it.path
                                    )
                                }
                                val plan = ActionPlan(
                                    actionType = ActionType.TRASH,
                                    title = "移入系统媒体回收站",
                                    description = "已手动选择 ${selected.size} 个项目，将通过系统标准接口移入回收站（可保留30天恢复期）。",
                                    targets = targets,
                                    estimatedReleasableBytes = selected.sumOf { it.sizeBytes },
                                    status = PlanStatus.PREVIEW
                                )
                                currentTab = AppTab.ASSISTANT
                                messages.add(
                                    AiMessage(
                                        sender = "ASSISTANT",
                                        content = "已为您生成批量操作卡片，请确认后移入系统回收站：",
                                        plan = plan
                                    )
                                )
                            },
                            modifier = Modifier.padding(innerPadding)
                        )
                        AppTab.AUDIT -> AuditLogScreen(
                            auditRecords = auditRecords,
                            onRefresh = { loadAuditData() },
                            onRestoreRecord = { record -> requestRestoreRecord(record) },
                            modifier = Modifier.padding(innerPadding)
                        )
                        AppTab.SETTINGS -> SettingsScreen(
                            vault = AIAgentApplication.instance.vault,
                            onOpenAccessibilitySettings = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    private fun initLaunchers() {
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { _ ->
            checkPermissionsState()
            refreshData()
        }

        trashConsentLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            val plan = pendingConsentPlan ?: return@registerForActivityResult
            pendingConsentPlan = null

            lifecycleScope.launch {
                val app = AIAgentApplication.instance
                val res = app.trashExecutor.onConsentResultReceived(plan, result.resultCode)
                when (res) {
                    is TrashOperationExecutor.TrashResult.Completed -> {
                        messages.add(
                            AiMessage(
                                sender = "ASSISTANT",
                                content = "✅ 操作成功！已将 ${res.plan.targets.size} 个文件移入系统回收站，预计可释放空间 ${formatBytes(res.releasedBytes)}。"
                            )
                        )
                        loadStorageData()
                        loadMediaData()
                        loadAuditData()
                    }
                    is TrashOperationExecutor.TrashResult.Failed -> {
                        messages.add(
                            AiMessage(
                                sender = "ASSISTANT",
                                content = "⚠️ 操作已终止: ${res.errorMessage}"
                            )
                        )
                    }
                    else -> {}
                }
            }
        }

        restoreConsentLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            val record = pendingRestoreRecord
            pendingRestoreRecord = null
            if (record != null && result.resultCode == Activity.RESULT_OK) {
                lifecycleScope.launch {
                    val app = AIAgentApplication.instance
                    app.dbHelper.updateAuditStatus(record.id, "RESTORED")
                    Toast.makeText(this@MainActivity, "✅ 成功从系统回收站还原！", Toast.LENGTH_SHORT).show()
                    loadStorageData()
                    loadMediaData()
                    loadAuditData()
                }
            } else if (result.resultCode != Activity.RESULT_OK) {
                Toast.makeText(this@MainActivity, "已取消还原操作", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            // Android 11 - 12
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            checkPermissionsState()
        }
    }

    private fun checkPermissionsState() {
        val app = AIAgentApplication.instance
        isPartialPhotoAccess = app.mediaScanner.isVisualMediaPartialGranted()
    }

    private fun refreshData() {
        loadStorageData()
        loadMediaData()
        loadAuditData()
    }

    private fun loadStorageData() {
        lifecycleScope.launch {
            isStorageLoading = true
            try {
                val stats = AIAgentApplication.instance.storageEngine.getStorageOverview()
                storageStat = stats
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isStorageLoading = false
            }
        }
    }

    private fun loadMediaData() {
        lifecycleScope.launch {
            isMediaLoading = true
            try {
                val items = AIAgentApplication.instance.mediaScanner.queryMediaItems(limit = 300)
                mediaItems.clear()
                mediaItems.addAll(items)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isMediaLoading = false
            }
        }
    }

    private fun loadAuditData() {
        lifecycleScope.launch {
            try {
                val records = AIAgentApplication.instance.dbHelper.getAllAuditRecords()
                auditRecords.clear()
                auditRecords.addAll(records)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startNewChat() {
        messages.clear()
        activeSteps.clear()
        isProcessing = false
        messages.add(
            AiMessage(
                sender = "ASSISTANT",
                content = "✨ 已为您开启新对话！之前的会话上下文与历史任务已重置。您可以随时向我咨询手机存储、查找大文件、清理相册或协助操作屏幕。"
            )
        )
    }

    private fun handleUserPrompt(prompt: String) {
        val trimmed = prompt.trim().removePrefix("➕").trim()
        if (trimmed == "开新对话" || trimmed == "新建对话" || trimmed == "清空对话" || trimmed == "重新开始") {
            startNewChat()
            return
        }

        val currentHistory = messages.toList()
        messages.add(AiMessage(sender = "USER", content = prompt))
        isProcessing = true
        activeSteps.clear()

        lifecycleScope.launch {
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
                        content = "执行过程中遇到错误: ${e.message}"
                    )
                )
            } finally {
                isProcessing = false
            }
        }
    }

    private fun executeActionPlan(plan: ActionPlan) {
        lifecycleScope.launch {
            plan.status = PlanStatus.EXECUTING
            val app = AIAgentApplication.instance
            val result = app.trashExecutor.prepareTrashRequest(plan)

            when (result) {
                is TrashOperationExecutor.TrashResult.RequiresUserConsent -> {
                    pendingConsentPlan = result.plan
                    trashConsentLauncher.launch(result.intentSenderRequest)
                }
                is TrashOperationExecutor.TrashResult.Completed -> {
                    plan.status = PlanStatus.SUCCESS
                    messages.add(
                        AiMessage(
                            sender = "ASSISTANT",
                            content = "✅ 操作成功！已将 ${plan.targets.size} 个文件移入回收站，预计释放 ${formatBytes(result.releasedBytes)}。"
                        )
                    )
                    loadStorageData()
                    loadMediaData()
                    loadAuditData()
                }
                is TrashOperationExecutor.TrashResult.TargetChanged -> {
                    plan.status = PlanStatus.PREVIEW
                    Toast.makeText(this@MainActivity, "注意：有 ${result.missingCount} 个文件已不存在，已重新生成最新清单，请重新确认！", Toast.LENGTH_LONG).show()
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
        lifecycleScope.launch {
            AIAgentApplication.instance.dbHelper.insertAuditRecord(
                AuditRecord(
                    actionType = plan.actionType,
                    planTitle = plan.title,
                    targetCount = plan.targets.size,
                    releasedBytes = 0L,
                    status = "CANCELLED_BY_USER",
                    detailsJson = "用户在预览卡片上主动点击取消"
                )
            )
            loadAuditData()
        }
        messages.add(
            AiMessage(
                sender = "ASSISTANT",
                content = "已取消该操作，未做任何修改。"
            )
        )
    }

    private fun requestRestoreRecord(record: AuditRecord) {
        val targets: List<ActionTarget> = runCatching {
            val listType = object : TypeToken<List<ActionTarget>>() {}.type
            Gson().fromJson<List<ActionTarget>>(record.detailsJson, listType)
        }.getOrNull() ?: emptyList()

        if (targets.isEmpty()) {
            Toast.makeText(this, "未找到有效的还原目标详情", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val app = AIAgentApplication.instance
            val intentSenderRequest = app.trashExecutor.prepareRestoreRequest(targets)
            if (intentSenderRequest != null) {
                pendingRestoreRecord = record
                restoreConsentLauncher.launch(intentSenderRequest)
            } else {
                Toast.makeText(this@MainActivity, "当前系统不支持或未找到待还原文件", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("prompt")?.let { prompt ->
            if (prompt.isNotBlank()) {
                currentTab = AppTab.ASSISTANT
                handleUserPrompt(prompt)
            }
        }
    }
}
