package com.aiagent.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiagent.android.ai.LlmClient
import com.aiagent.android.floating.FloatingAssistantManager
import com.aiagent.android.model.UserMemoryItem
import com.aiagent.android.screen.ScreenAssistantAccessibilityService
import com.aiagent.android.security.KeyStoreVault
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    vault: KeyStoreVault,
    onOpenAccessibilitySettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(vault.getBaseUrl()) }
    var apiKey by remember { mutableStateOf(vault.getApiKey()) }
    var modelName by remember { mutableStateOf(vault.getModelName()) }
    var isOfflinePreferred by remember { mutableStateOf(vault.isOfflinePreferred()) }
    var isScreenAssistantEnabled by remember { mutableStateOf(vault.isScreenAssistantEnabled()) }

    var showPassword by remember { mutableStateOf(false) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var connectionTestResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    val hasAllFilesAccess = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true
    }

    val isAccessibilityActive = ScreenAssistantAccessibilityService.isServiceRunning()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "系统与服务配置",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Section: AI Model Configuration
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "大模型连接配置", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "支持接入任意 OpenAI 兼容协议服务商（如 DeepSeek、通义千问等）。密钥通过硬件 Keystore 本地加密保存。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = modelName == "deepseek-chat",
                            onClick = {
                                baseUrl = "https://api.deepseek.com/v1"
                                modelName = "deepseek-chat"
                                vault.saveBaseUrl(baseUrl)
                                vault.saveModelName(modelName)
                            },
                            label = { Text("DeepSeek V3 (首选推荐)", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = modelName == "deepseek-reasoner",
                            onClick = {
                                baseUrl = "https://api.deepseek.com/v1"
                                modelName = "deepseek-reasoner"
                                vault.saveBaseUrl(baseUrl)
                                vault.saveModelName(modelName)
                            },
                            label = { Text("DeepSeek R1", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = modelName.contains("qwen"),
                            onClick = {
                                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
                                modelName = "qwen-max"
                                vault.saveBaseUrl(baseUrl)
                                vault.saveModelName(modelName)
                            },
                            label = { Text("千问 Max", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = modelName == "gpt-4o",
                            onClick = {
                                baseUrl = "https://api.openai.com/v1"
                                modelName = "gpt-4o"
                                vault.saveBaseUrl(baseUrl)
                                vault.saveModelName(modelName)
                            },
                            label = { Text("GPT-4o", fontSize = 11.sp) }
                        )
                    }

                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = {
                            baseUrl = it
                            vault.saveBaseUrl(it)
                        },
                        label = { Text("接口 Base URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            vault.saveApiKey(it)
                        },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = null
                                )
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = modelName,
                        onValueChange = {
                            modelName = it
                            vault.saveModelName(it)
                        },
                        label = { Text("模型名称 (如 gpt-4o-mini, deepseek-chat)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    isTestingConnection = true
                                    connectionTestResult = null
                                    val client = LlmClient(baseUrl, apiKey, modelName)
                                    val res = client.testConnection()
                                    connectionTestResult = res
                                    isTestingConnection = false
                                }
                            },
                            enabled = !isTestingConnection && apiKey.isNotBlank()
                        ) {
                            if (isTestingConnection) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text("测试连接")
                        }
                    }

                    connectionTestResult?.let { (success, msg) ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = if (success) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = msg,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(8.dp),
                                color = if (success) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("离线规则优先", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("开启后优先使用本地规则匹配，无需网络与大模型", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        }
                        Switch(
                            checked = isOfflinePreferred,
                            onCheckedChange = {
                                isOfflinePreferred = it
                                vault.setOfflinePreferred(it)
                            }
                        )
                    }
                }
            }
        }

        // Section: Permissions & Storage Access
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "系统权限与访问范围", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("全盘文件访问权限", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                text = if (hasAllFilesAccess) "已授予 (支持深度分析非媒体目录与 APK)" else "未授予 (仅能访问媒体库与用户所选目录)",
                                fontSize = 12.sp,
                                color = if (hasAllFilesAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess) {
                            OutlinedButton(onClick = {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            }) {
                                Text("前往授权")
                            }
                        }
                    }
                }
            }
        }

        // Section: Screen Assistant (Accessibility)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "屏幕辅助操作 (无障碍)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Text(
                        text = "本模块独立且默认关闭。仅在用户发起任务时协助调起系统存储页面并辅助查看。绝不代替用户自动点击系统级删除确认弹窗。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("屏幕辅助开关", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                text = if (isAccessibilityActive) "系统无障碍服务已连接" else "系统无障碍服务未启用",
                                fontSize = 12.sp,
                                color = if (isAccessibilityActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                        Switch(
                            checked = isScreenAssistantEnabled,
                            onCheckedChange = {
                                isScreenAssistantEnabled = it
                                vault.setScreenAssistantEnabled(it)
                            }
                        )
                    }

                    if (!isAccessibilityActive && isScreenAssistantEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onOpenAccessibilitySettings,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("前往系统无障碍设置开启服务")
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("若提示「受限制的设置」：前往解除限制")
                            }
                            Text(
                                text = "💡 Android 13+ 提示「受限制的设置」或禁止开启：点击上方按钮进入应用详情 -> 点击右上角「⋮」选择「允许受限制的设置」验证密码后即可正常开启。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // Section: Floating Window Assistant
        item {
            var isFloatingRunning by remember { mutableStateOf(FloatingAssistantManager.isServiceRunning()) }
            val hasOverlay = FloatingAssistantManager.hasOverlayPermission(context)

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "桌面悬浮窗助手", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "开启后在手机桌面与任意第三方应用界面上层显示智能悬浮球。轻点即可展开迷你对话窗，随时随地与 AI 对话、查看当前屏幕、模拟连点手势或清理手机。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("桌面悬浮窗开关", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                text = when {
                                    !hasOverlay -> "需授予「显示在其他应用上层」权限"
                                    isFloatingRunning -> "悬浮窗运行中（随时跨屏可用）"
                                    else -> "已关闭"
                                },
                                fontSize = 12.sp,
                                color = if (isFloatingRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(
                            checked = isFloatingRunning,
                            onCheckedChange = { enable ->
                                if (enable) {
                                    val started = FloatingAssistantManager.startFloatingService(context)
                                    isFloatingRunning = started
                                } else {
                                    FloatingAssistantManager.stopFloatingService(context)
                                    isFloatingRunning = false
                                }
                            }
                        )
                    }

                    if (isFloatingRunning) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { FloatingAssistantManager.showFloatingService(context) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("展开对话", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { FloatingAssistantManager.dockFloatingService(context) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("贴边隐藏", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { FloatingAssistantManager.hideFloatingService(context) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("完全隐身", fontSize = 11.sp)
                            }
                        }
                    }

                    if (!hasOverlay) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { FloatingAssistantManager.requestOverlayPermission(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("前往系统设置授予悬浮窗权限")
                        }
                    }
                }
            }
        }

        // Section: Long Term Memory Management (Hardware Encrypted)
        item {
            var memories by remember { mutableStateOf(vault.getAllMemories()) }
            var showAddDialog by remember { mutableStateOf(false) }
            var unmaskedKeys by remember { mutableStateOf(setOf<String>()) }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "专属长时记忆 (跨对话不忘)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { showAddDialog = true }) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = "添加记忆", tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Text(
                        text = "基于 Android KeyStore 硬件级 (AES-256-GCM) 本地加密。保存手机号、密码、称呼或常用偏好后，开新对话也不会遗忘；在跨应用遇到输入、登录或填表时，AI 可直接自动填入。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                    )

                    if (memories.isEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "暂无专属记忆记录。您可以点击右上角「+」或在对话中对 AI 说“记住我的手机号是...”即可永久保存！",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            memories.forEach { mem ->
                                val isUnmasked = unmaskedKeys.contains(mem.key)
                                val displayValue = if (isUnmasked) {
                                    mem.value
                                } else {
                                    when (mem.key) {
                                        "phone" -> if (mem.value.length == 11) mem.value.substring(0, 3) + "****" + mem.value.substring(7) else mem.value
                                        "password" -> "••••••••"
                                        else -> mem.value
                                    }
                                }

                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = mem.label,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "(${mem.key})",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            }
                                            Text(
                                                text = displayValue,
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }

                                        Row {
                                            IconButton(
                                                onClick = {
                                                    unmaskedKeys = if (isUnmasked) {
                                                        unmaskedKeys - mem.key
                                                    } else {
                                                        unmaskedKeys + mem.key
                                                    }
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (isUnmasked) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                    contentDescription = "查看明文",
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    vault.deleteMemory(mem.key)
                                                    memories = vault.getAllMemories()
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "删除记忆",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = {
                                        vault.clearAllMemories()
                                        memories = emptyList()
                                    }
                                ) {
                                    Text("清空所有记忆", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    if (showAddDialog) {
                        var addKey by remember { mutableStateOf("phone") }
                        var addLabel by remember { mutableStateOf("手机号") }
                        var addValue by remember { mutableStateOf("") }

                        AlertDialog(
                            onDismissRequest = { showAddDialog = false },
                            title = { Text("添加专属长时记忆") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("快捷类型：", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilterChip(
                                            selected = addKey == "phone",
                                            onClick = {
                                                addKey = "phone"
                                                addLabel = "手机号"
                                            },
                                            label = { Text("手机号", fontSize = 11.sp) }
                                        )
                                        FilterChip(
                                            selected = addKey == "password",
                                            onClick = {
                                                addKey = "password"
                                                addLabel = "登录密码"
                                            },
                                            label = { Text("密码", fontSize = 11.sp) }
                                        )
                                        FilterChip(
                                            selected = addKey == "user_name",
                                            onClick = {
                                                addKey = "user_name"
                                                addLabel = "用户称呼"
                                            },
                                            label = { Text("称呼", fontSize = 11.sp) }
                                        )
                                    }

                                    OutlinedTextField(
                                        value = addLabel,
                                        onValueChange = { addLabel = it },
                                        label = { Text("记忆标签 (如：常用手机号)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = addKey,
                                        onValueChange = { addKey = it.trim().lowercase() },
                                        label = { Text("记忆键名 (key)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = addValue,
                                        onValueChange = { addValue = it },
                                        label = { Text("记忆内容 (value)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text(
                                        text = "🔒 保存后将通过 Android KeyStore (AES-256-GCM) 硬件加密保存在本机，绝不上云。",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        if (addKey.isNotBlank() && addValue.isNotBlank()) {
                                            vault.saveMemory(addKey, addValue, addLabel.ifBlank { addKey })
                                            memories = vault.getAllMemories()
                                            showAddDialog = false
                                        }
                                    }
                                ) {
                                    Text("保存")
                                }
                            },
                            dismissButton = {
                                OutlinedButton(onClick = { showAddDialog = false }) {
                                    Text("取消")
                                }
                            }
                        )
                    }
                }
            }
        }

        // Section: App & Device Info
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "设备与版本信息", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "• 应用版本：v1.0.0 (API 30 - 36)", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    Text(text = "• 目标环境：Android 16 / MagicOS 10.0 (VKJ-AN00)", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    Text(text = "• 安全机制：Android KeyStore + Scoped Storage + MediaStore Trash", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
