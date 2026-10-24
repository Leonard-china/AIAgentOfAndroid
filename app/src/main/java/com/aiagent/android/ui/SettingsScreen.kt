package com.aiagent.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
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

    var visionEnabled by remember { mutableStateOf(vault.isVisionEnabled()) }
    var useCustomVision by remember { mutableStateOf(vault.getVisionBaseUrl().isNotBlank() || vault.getVisionApiKeyOverride().isNotBlank()) }
    var visionModel by remember { mutableStateOf(vault.getVisionModel()) }
    var visionBaseUrl by remember { mutableStateOf(vault.getVisionBaseUrl()) }
    var visionApiKey by remember { mutableStateOf(vault.getVisionApiKeyOverride()) }
    var requestVisionConsent by remember { mutableStateOf(false) }

    var showPassword by remember { mutableStateOf(false) }
    var showVisionPassword by remember { mutableStateOf(false) }

    var isTestingConnection by remember { mutableStateOf(false) }
    var connectionTestResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var saveFeedback by remember { mutableStateOf<String?>(null) }

    var isTestingVision by remember { mutableStateOf(false) }
    var visionTestResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var visionSaveFeedback by remember { mutableStateOf<String?>(null) }

    if (requestVisionConsent) {
        AlertDialog(
            onDismissRequest = { requestVisionConsent = false },
            title = { Text("开启截图视觉理解？") },
            text = { Text("开启后，你发起的任务可按需把当前应用截图发送给配置的视觉模型。截图只在端侧内存中处理并销毁，不写入相册；检测到密码输入或系统安全画面时会自动拒绝截屏。可以随时关闭。") },
            confirmButton = {
                TextButton(onClick = {
                    visionEnabled = true
                    vault.setVisionEnabled(true)
                    requestVisionConsent = false
                }) { Text("了解并开启") }
            },
            dismissButton = {
                TextButton(onClick = { requestVisionConsent = false }) { Text("暂不开启") }
            }
        )
    }

    val hasAllFilesAccess = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true
    }

    val isAccessibilityActive by ScreenAssistantAccessibilityService.connectionState.collectAsState()

    fun selectPreset(presetName: String, targetBaseUrl: String, targetModel: String, isVisionSupported: Boolean = false) {
        val cleanCurrentUrl = LlmClient.cleanBaseUrl(baseUrl)
        val cleanCurrentKey = LlmClient.cleanApiKey(apiKey)
        val oldProvider = vault.getProviderId(cleanCurrentUrl)
        if (cleanCurrentKey.isNotBlank()) {
            vault.saveApiKeyForProvider(oldProvider, cleanCurrentKey)
        }

        val cleanTargetUrl = LlmClient.cleanBaseUrl(targetBaseUrl)
        val newProvider = vault.getProviderId(cleanTargetUrl)
        val targetKey = vault.getApiKeyForProvider(newProvider)

        baseUrl = cleanTargetUrl
        modelName = targetModel
        apiKey = targetKey

        if (isVisionSupported && visionModel.isBlank()) {
            visionModel = targetModel
        }

        vault.saveBaseUrl(cleanTargetUrl)
        vault.saveModelName(targetModel)
        vault.saveApiKey(targetKey)

        connectionTestResult = null
        if (targetKey.isNotBlank()) {
            saveFeedback = "已自动切换为【$presetName】的已保存密钥"
        } else {
            saveFeedback = "已切换为【$presetName】（尚未配置密钥，请在下方粘贴并保存）"
        }
    }

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
                        text = "支持接入任意 OpenAI 兼容服务商（如 DeepSeek、通义千问等）。密钥通过硬件 Keystore 本地加密保存。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                    )

                    Text("快速选择服务商预设（可左右滑动）：", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))

                    val presets = remember {
                        listOf(
                            Triple("DeepSeek V3 (推荐)", "https://api.deepseek.com/v1", "deepseek-chat"),
                            Triple("DeepSeek R1 (推理)", "https://api.deepseek.com/v1", "deepseek-reasoner"),
                            Triple("通义千问 Max", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-max"),
                            Triple("通义千问 Plus", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
                            Triple("千问 VL (多模态)", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-vl-max"),
                            Triple("GPT-4o", "https://api.openai.com/v1", "gpt-4o"),
                            Triple("智谱 GLM-4", "https://open.bigmodel.cn/api/paas/v4", "glm-4")
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { (name, url, model) ->
                            FilterChip(
                                selected = modelName == model && baseUrl == url,
                                onClick = {
                                    selectPreset(name, url, model, model.contains("vl") || model.contains("4o"))
                                },
                                label = { Text(name, fontSize = 12.sp) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = {
                            baseUrl = it
                            saveFeedback = null
                            connectionTestResult = null
                        },
                        label = { Text("接口 Base URL") },
                        placeholder = { Text("如 https://api.deepseek.com/v1") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            if (baseUrl.isNotBlank()) {
                                IconButton(onClick = { baseUrl = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "清空")
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            saveFeedback = null
                            connectionTestResult = null
                        },
                        label = {
                            val providerName = when (vault.getProviderId(baseUrl)) {
                                "deepseek" -> "DeepSeek"
                                "dashscope" -> "通义千问 / 百炼"
                                "openai" -> "OpenAI"
                                "zhipu" -> "智谱 AI"
                                else -> "服务商"
                            }
                            Text("API Key ($providerName 专属密钥)")
                        },
                        placeholder = { Text("通常以 sk- 开头，直接粘贴即可") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (apiKey.isNotBlank()) {
                                    IconButton(onClick = { apiKey = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "清空")
                                    }
                                }
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        imageVector = if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (showPassword) "隐藏" else "查看"
                                    )
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = modelName,
                        onValueChange = {
                            modelName = it
                            saveFeedback = null
                            connectionTestResult = null
                        },
                        label = { Text("模型名称") },
                        placeholder = { Text("如 deepseek-chat 或 qwen-max") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                val cleanUrl = LlmClient.cleanBaseUrl(baseUrl)
                                val cleanKey = LlmClient.cleanApiKey(apiKey)
                                val cleanModel = modelName.trim()
                                baseUrl = cleanUrl
                                apiKey = cleanKey
                                modelName = cleanModel
                                vault.saveBaseUrl(cleanUrl)
                                vault.saveApiKey(cleanKey)
                                vault.saveModelName(cleanModel)
                                val provider = vault.getProviderId(cleanUrl)
                                vault.saveApiKeyForProvider(provider, cleanKey)
                                saveFeedback = "【${provider.uppercase()}】专属密钥已加密保存！"
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("保存配置")
                        }

                        OutlinedButton(
                            onClick = {
                                val cleanUrl = LlmClient.cleanBaseUrl(baseUrl)
                                val cleanKey = LlmClient.cleanApiKey(apiKey)
                                val cleanModel = modelName.trim()
                                baseUrl = cleanUrl
                                apiKey = cleanKey
                                modelName = cleanModel
                                vault.saveBaseUrl(cleanUrl)
                                vault.saveApiKey(cleanKey)
                                vault.saveModelName(cleanModel)
                                val provider = vault.getProviderId(cleanUrl)
                                vault.saveApiKeyForProvider(provider, cleanKey)
                                saveFeedback = "配置已自动保存"

                                coroutineScope.launch {
                                    isTestingConnection = true
                                    connectionTestResult = null
                                    val client = LlmClient(cleanUrl, cleanKey, cleanModel)
                                    val res = client.testConnection()
                                    connectionTestResult = res
                                    isTestingConnection = false
                                }
                            },
                            enabled = !isTestingConnection && apiKey.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isTestingConnection) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                            } else {
                                Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text("测试连接")
                        }
                    }

                    saveFeedback?.let { msg ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "✓ $msg", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }

                    connectionTestResult?.let { (success, msg) ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = if (success) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = msg,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp),
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

        // Section: Vision Configuration
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Visibility, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("截图视觉理解（多模态）", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Switch(
                            checked = visionEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    requestVisionConsent = true
                                } else {
                                    visionEnabled = false
                                    vault.setVisionEnabled(false)
                                    if (com.aiagent.android.AIAgentApplication.instance.conversation.isProcessing) {
                                        com.aiagent.android.AIAgentApplication.instance.conversation.stop()
                                    }
                                }
                            }
                        )
                    }

                    Text(
                        "用于无障碍控件树无法读取的自绘画面（如游戏、自绘 Canvas、无标签图标）。默认关闭；需要配置支持图片输入的多模态模型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (visionEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "使用独立的第三方视觉服务商",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = useCustomVision,
                                onCheckedChange = { custom ->
                                    useCustomVision = custom
                                    if (!custom) {
                                        visionBaseUrl = ""
                                        visionApiKey = ""
                                        vault.saveVisionBaseUrl("")
                                        vault.saveVisionApiKeyOverride("")
                                    }
                                }
                            )
                        }

                        if (!useCustomVision) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "已启用：自动沿用主模型的 Base URL 与 API Key（无需重复填写密钥）。只需确保模型支持图片输入即可。",
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(10.dp),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        OutlinedTextField(
                            value = visionModel,
                            onValueChange = {
                                visionModel = it
                                vault.saveVisionModel(it)
                                visionSaveFeedback = null
                                visionTestResult = null
                            },
                            label = { Text("视觉模型名称 (如 qwen-vl-max 或 gpt-4o)") },
                            placeholder = { Text("如 qwen-vl-max") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (useCustomVision) {
                            OutlinedTextField(
                                value = visionBaseUrl,
                                onValueChange = {
                                    visionBaseUrl = it
                                    visionSaveFeedback = null
                                    visionTestResult = null
                                },
                                label = { Text("独立视觉接口 Base URL") },
                                placeholder = { Text("如 https://dashscope.aliyuncs.com/compatible-mode/v1") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = visionApiKey,
                                onValueChange = {
                                    visionApiKey = it
                                    visionSaveFeedback = null
                                    visionTestResult = null
                                },
                                label = { Text("独立视觉接口 API Key") },
                                singleLine = true,
                                visualTransformation = if (showVisionPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showVisionPassword = !showVisionPassword }) {
                                        Icon(
                                            imageVector = if (showVisionPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = if (showVisionPassword) "隐藏" else "查看"
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    vault.saveVisionModel(visionModel.trim())
                                    if (useCustomVision) {
                                        vault.saveVisionBaseUrl(LlmClient.cleanBaseUrl(visionBaseUrl))
                                        vault.saveVisionApiKeyOverride(LlmClient.cleanApiKey(visionApiKey))
                                    } else {
                                        vault.saveVisionBaseUrl("")
                                        vault.saveVisionApiKeyOverride("")
                                    }
                                    visionSaveFeedback = "视觉配置已保存！"
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("保存视觉配置")
                            }

                            OutlinedButton(
                                onClick = {
                                    val vModel = visionModel.trim()
                                    val vUrl = if (useCustomVision && visionBaseUrl.isNotBlank()) LlmClient.cleanBaseUrl(visionBaseUrl) else vault.getBaseUrl()
                                    val vKey = if (useCustomVision && visionApiKey.isNotBlank()) LlmClient.cleanApiKey(visionApiKey) else vault.getApiKey()

                                    coroutineScope.launch {
                                        isTestingVision = true
                                        visionTestResult = null
                                        val client = LlmClient(vUrl, vKey, vModel)
                                        val res = client.testVisionConnection()
                                        visionTestResult = res
                                        isTestingVision = false
                                    }
                                },
                                enabled = !isTestingVision && visionModel.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isTestingVision) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text("测试视觉连接")
                            }
                        }

                        visionSaveFeedback?.let { msg ->
                            Text(text = "✓ $msg", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }

                        visionTestResult?.let { (success, msg) ->
                            Surface(
                                color = if (success) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = msg,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(10.dp),
                                    color = if (success) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
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
                        text = "开启后，助手会按你的任务读取当前应用的可访问界面文字，并发送给所配置的模型规划点击、输入和滑动。密码字段会隐藏；系统删除确认由你手动处理。自绘界面暂不支持。",
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
