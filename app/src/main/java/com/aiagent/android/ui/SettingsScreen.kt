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
                            selected = baseUrl.contains("aliyuncs") || modelName.contains("qwen"),
                            onClick = {
                                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
                                modelName = "qwen-plus"
                                vault.saveBaseUrl(baseUrl)
                                vault.saveModelName(modelName)
                            },
                            label = { Text("通义千问 (Qwen)", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = baseUrl.contains("deepseek"),
                            onClick = {
                                baseUrl = "https://api.deepseek.com/v1"
                                modelName = "deepseek-chat"
                                vault.saveBaseUrl(baseUrl)
                                vault.saveModelName(modelName)
                            },
                            label = { Text("DeepSeek", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = baseUrl.contains("openai.com"),
                            onClick = {
                                baseUrl = "https://api.openai.com/v1"
                                modelName = "gpt-4o-mini"
                                vault.saveBaseUrl(baseUrl)
                                vault.saveModelName(modelName)
                            },
                            label = { Text("OpenAI", fontSize = 11.sp) }
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
