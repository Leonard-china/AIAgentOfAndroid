package com.aiagent.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiagent.android.model.ActionPlan
import com.aiagent.android.model.AiMessage
import com.aiagent.android.model.PlanStatus
import com.aiagent.android.model.TaskStep
import com.aiagent.android.model.formatBytes

@Composable
fun AssistantScreen(
    messages: List<AiMessage>,
    activeSteps: List<TaskStep>,
    isProcessing: Boolean,
    onSendMessage: (String) -> Unit,
    onConfirmPlan: (ActionPlan) -> Unit,
    onCancelPlan: (ActionPlan) -> Unit,
    onNewChat: () -> Unit,
    onStopTask: () -> Unit,
    onOpenSettings: () -> Unit,
    screenConnected: Boolean,
    modelLabel: String,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, activeSteps.size, isProcessing) {
        val count = messages.size + if (isProcessing) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.onBackground) {
                Icon(Icons.Default.Terminal, null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.background)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Pocket Agent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("你的手机任务助手", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onNewChat, enabled = !isProcessing) {
                Icon(Icons.Default.Add, contentDescription = "新建对话")
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(onClick = onOpenSettings, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (screenConnected) SuccessGreen else MaterialTheme.colorScheme.outline))
                    Spacer(Modifier.width(6.dp))
                    Text(if (screenConnected) "屏幕已连接" else "连接屏幕操作", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(modelLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onOpenSettings).widthIn(max = 170.dp), maxLines = 1)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        if (messages.isEmpty() && !isProcessing) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Spacer(Modifier.height(24.dp))
                    Text("从一个目标开始", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("把手机上的事\n交给我。", fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
                    Spacer(Modifier.height(12.dp))
                    Text("打开应用、搜索内容、接着完成操作。\n执行过程随时可见，也随时可以停止。", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 23.sp)
                    Spacer(Modifier.height(18.dp))
                }
                item { GoalSuggestion("跨应用完成任务", "搜索内容，再打开结果", Icons.Default.TouchApp) {
                    inputText = "在哔哩哔哩搜索 3Blue1Brown，打开一个数学视频"
                } }
                item { GoalSuggestion("看看当前屏幕", "读取界面，帮我找到下一步", Icons.Default.CenterFocusStrong) {
                    inputText = "看看当前屏幕，告诉我有哪些可以操作的内容"
                } }
                item { GoalSuggestion("整理手机空间", "用本地工具找出大文件", Icons.Default.FolderOpen) {
                    inputText = "找出超过 500MB 的视频，先列出来给我看"
                } }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                items(messages, key = { it.id }) { message -> MessageItem(message, onConfirmPlan, onCancelPlan) }
                if (isProcessing) item {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(activeSteps.lastOrNull()?.title ?: "正在规划任务", style = MaterialTheme.typography.labelMedium)
                        }
                        if (activeSteps.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            ActiveStepsCard(activeSteps)
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(8.dp)) {
                    TextField(value = inputText, onValueChange = { inputText = it },
                        placeholder = { Text("告诉我，你想完成什么…", fontSize = 14.sp) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp, max = 140.dp),
                        enabled = !isProcessing, maxLines = 5,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent))
                    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Smartphone, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(if (isProcessing) "正在操作 · 可随时停止" else "手机任务", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        FilledIconButton(onClick = {
                            if (isProcessing) onStopTask() else if (inputText.isNotBlank()) {
                                val prompt = inputText.trim(); inputText = ""; onSendMessage(prompt)
                            }
                        }, enabled = isProcessing || inputText.isNotBlank(), modifier = Modifier.size(40.dp), shape = CircleShape) {
                            Icon(if (isProcessing) Icons.Default.Stop else Icons.Default.ArrowUpward,
                                contentDescription = if (isProcessing) "停止任务" else "发送任务", modifier = Modifier.size(21.dp))
                        }
                    }
                }
            }
            Text("重要操作由你确认", Modifier.fillMaxWidth().padding(top = 8.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun GoalSuggestion(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun MessageItem(message: AiMessage, onConfirmPlan: (ActionPlan) -> Unit, onCancelPlan: (ActionPlan) -> Unit) {
    val user = message.sender == "USER"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        if (user) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.widthIn(max = 320.dp)) {
                SelectionContainer { Text(message.content, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), fontSize = 15.sp, lineHeight = 24.sp) }
            }
        } else {
            val label = when (message.taskStatus) {
                "COMPLETED" -> "已核验界面"
                "NEEDS_USER" -> "需要你接手"
                "BLOCKED", "INCOMPLETE" -> "尚未完成"
                "CANCELLED" -> "已停止"
                else -> "Pocket Agent"
            }
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = if (message.taskStatus in listOf("BLOCKED", "INCOMPLETE")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            SelectionContainer { Text(message.content, fontSize = 15.sp, lineHeight = 25.sp) }
            if (message.taskSteps.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                    Text("${message.taskSteps.size} 个执行步骤", style = MaterialTheme.typography.labelMedium)
                }
                if (expanded) ActiveStepsCard(message.taskSteps)
            }
        }
        message.plan?.let { plan ->
            Spacer(Modifier.height(12.dp))
            ActionPlanPreviewCard(plan, { onConfirmPlan(plan) }, { onCancelPlan(plan) })
        }
    }
}

@Composable
fun ActionPlanPreviewCard(
    plan: ActionPlan,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = plan.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = plan.description,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "受影响目标：${plan.targets.size} 个文件",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "预计可释放：${formatBytes(plan.estimatedReleasableBytes)}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (expanded) "收起目标文件清单 ▲" else "展开查看目标文件清单 (${plan.targets.size}) ▼",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    plan.targets.take(20).forEachIndexed { idx, target ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${idx + 1}. ${target.displayName}",
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = formatBytes(target.sizeBytes),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    if (plan.targets.size > 20) {
                        Text(
                            text = "... 以及另外 ${plan.targets.size - 20} 个文件",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons
            if (plan.status == PlanStatus.PREVIEW) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("确认移入回收站")
                    }
                }
            } else {
                Surface(
                    color = when (plan.status) {
                        PlanStatus.SUCCESS -> Color(0xFFE8F5E9)
                        PlanStatus.CANCELLED -> Color(0xFFF5F5F5)
                        else -> Color(0xFFFFEBEE)
                    },
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = when (plan.status) {
                            PlanStatus.SUCCESS -> "✓ 已确认并成功移入回收站"
                            PlanStatus.CANCELLED -> "✗ 用户已取消此操作"
                            else -> "状态: ${plan.status.name}"
                        },
                        fontSize = 12.sp,
                        color = when (plan.status) {
                            PlanStatus.SUCCESS -> Color(0xFF2E7D32)
                            PlanStatus.CANCELLED -> Color(0xFF757575)
                            else -> Color(0xFFC62828)
                        },
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ActiveStepsCard(steps: List<TaskStep>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "任务执行步骤",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            steps.forEach { step ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (step.status) {
                        "COMPLETED" -> Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF43A047),
                            modifier = Modifier.size(16.dp)
                        )
                        "RUNNING" -> CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp)
                        )
                        "FAILED" -> Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        else -> Icon(
                            imageVector = Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(text = step.title, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        if (step.detail.isNotBlank()) {
                            Text(text = step.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
}
