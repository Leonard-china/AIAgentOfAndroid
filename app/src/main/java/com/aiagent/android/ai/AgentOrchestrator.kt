package com.aiagent.android.ai

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.aiagent.android.floating.FloatingAssistantManager
import com.aiagent.android.model.ActionPlan
import com.aiagent.android.model.ActionTarget
import com.aiagent.android.model.ActionType
import com.aiagent.android.model.AiMessage
import com.aiagent.android.model.DuplicateGroup
import com.aiagent.android.model.MediaItem
import com.aiagent.android.model.MediaType
import com.aiagent.android.model.PlanStatus
import com.aiagent.android.model.TaskStep
import com.aiagent.android.model.formatBytes
import com.aiagent.android.screen.ScreenAssistantAccessibilityService
import com.aiagent.android.security.KeyStoreVault
import com.aiagent.android.tools.DuplicateDetectionEngine
import com.aiagent.android.tools.MediaScannerEngine
import com.aiagent.android.tools.SimilarPhotoEngine
import com.aiagent.android.tools.StorageEngine
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AgentOrchestrator(
    private val context: Context,
    private val storageEngine: StorageEngine,
    private val mediaScanner: MediaScannerEngine,
    private val duplicateEngine: DuplicateDetectionEngine,
    private val similarPhotoEngine: SimilarPhotoEngine,
    private val vault: KeyStoreVault
) {
    private val gson = Gson()
    private val knownItems = mutableMapOf<Long, MediaItem>()

    suspend fun processGoal(
        userPrompt: String,
        history: List<AiMessage> = emptyList(),
        onStepUpdated: (List<TaskStep>) -> Unit
    ): AiMessage = withContext(Dispatchers.IO) {
        val steps = mutableListOf<TaskStep>()

        // Check if LLM should be used or offline rule fallback
        val hasKey = vault.hasApiKey()
        val isOfflinePreferred = vault.isOfflinePreferred()

        if (!hasKey || isOfflinePreferred) {
            return@withContext executeOfflineRulePlan(userPrompt, steps, onStepUpdated)
        }

        // Online LLM Agent Flow
        val client = LlmClient(
            baseUrl = vault.getBaseUrl(),
            apiKey = vault.getApiKey(),
            model = vault.getModelName()
        )

        steps.add(TaskStep(1, "理解用户意图与目标分析", "RUNNING", "正在连接大模型制定执行步骤..."))
        onStepUpdated(steps.toList())

        val systemPrompt = """
            你是一个专业、严谨且诚实的 Android 手机智能文件与相册管理助手。

            【核心铁律】
            1. 你必须调用工具获取真实数据，严禁编造任何虚假路径、假文件或假清理数据！
            2. 当用户表达清理、移入回收站、删除或对之前查询列出的冗余文件表示确认时（例如“确认清理”、“移入回收站”、“把刚刚找到的冗余副本移入回收站”），你必须调用 propose_trash_plan 工具并传入目标文件 ID 列表生成操作预览卡片！绝对不要只用文字敷衍回复，必须生成操作卡片供用户在界面上核验并确认。
            3. 只能操作真实有效的本地文件 ID（可来自此前查询工具返回的 items 或 duplicate_groups 中的 redundant_copies ID）。
            4. 相似照片（find_similar_photos）仅为视觉候选，非完全重复，切勿在未让用户人工挑选前盲目建议删除。
            5. 请用温和、清晰、易懂的中文与用户沟通，突出真实收益（如预计释放空间）。

            【极度省 Token 与隐私保护铁律（极其重要）】
            6. 绝对禁止逐张翻看相册或要求获取全部相册完整列表！
            7. 绝对禁止传输任何图片原始二进制或 Base64 数据！所有相册索引、SHA-256 分块查重、dHash 相似度计算全部在手机端本地离线引擎静默完成，不消耗网络与额外 Token。
            8. 本地工具返回给你的永远是高度精简的聚合统计数据（总大小、总数、精选前几项目录/ID）。只有在极少数必要情况下（用户明确要求查看某一个特定文件的具体信息时），才允许调用 query_file_detail 查询单文件详情，严禁全盘遍历！

            【屏幕操作、连点器与桌面悬浮窗辅助能力】
            9. 你拥有通过受控无障碍服务操作屏幕的能力（类似于手机连点器与手势模拟）！当用户要求你点击屏幕、操作界面、返回、滑动或导航时，【绝对不要】以“系统安全限制”、“我无法操作屏幕”为由拒绝！
            10. 屏幕操作与桌面悬浮窗工具说明：
                - 查找并点击界面文字或按钮：调用 operate_screen_click(text="文字")；
                - 连点器坐标点击：调用 operate_screen_click(x=横坐标, y=纵坐标)；
                - 滑动翻页：调用 operate_screen_swipe(direction="UP"|"DOWN"|"LEFT"|"RIGHT")；
                - 返回或回桌面：调用 operate_screen_global_action(action="BACK"|"HOME")；
                - 遇到不确定的界面时，可先调用 inspect_current_screen 获取屏幕可见文字摘要（纯文本解析，极省 Token，不消耗 Vision Token），再决定点击哪个按钮；
                - 打开桌面悬浮窗：用户要求随时随地聊天或打开悬浮窗时调用 open_floating_window；
                - 若提示无障碍服务尚未开启，可引导用户或调用 open_accessibility_settings 协助用户开启。
        """.trimIndent()

        val messages = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", systemPrompt)
            })
            // Pass recent multi-turn conversation history
            val recentHistory = history.takeLast(6)
            for (msg in recentHistory) {
                if (msg.sender == "USER" && msg.content.isNotBlank()) {
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", msg.content)
                    })
                } else if (msg.sender == "ASSISTANT" && msg.content.isNotBlank()) {
                    add(JsonObject().apply {
                        addProperty("role", "assistant")
                        addProperty("content", msg.content)
                    })
                }
            }
            add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", userPrompt)
            })
        }

        try {
            var finalPlan: ActionPlan? = null
            var finalReplyContent: String? = null
            val maxTurns = 4
            var currentTurn = 0

            while (currentTurn < maxTurns) {
                currentTurn++
                val response = client.chatWithTools(messages, ToolRegistry.getToolsSchema())

                if (currentTurn == 1) {
                    steps[0].status = "COMPLETED"
                    steps[0].detail = "意图理解完成"
                    onStepUpdated(steps.toList())
                }

                if (response.toolCalls.isEmpty()) {
                    finalReplyContent = response.content
                    break
                }

                // Append assistant message with tool calls
                response.rawMessage?.let { messages.add(it) }

                for (tc in response.toolCalls) {
                    val stepIdx = steps.size + 1
                    val step = TaskStep(stepIdx, "执行工具: ${tc.functionName}", "RUNNING", "正在调用系统接口获取真实数据...")
                    steps.add(step)
                    onStepUpdated(steps.toList())

                    val result = executeToolCall(tc.functionName, tc.argumentsJson) { plan ->
                        finalPlan = plan
                    }

                    step.status = "COMPLETED"
                    step.detail = result.take(120)
                    onStepUpdated(steps.toList())

                    // Append tool response
                    val toolMsg = JsonObject().apply {
                        addProperty("role", "tool")
                        addProperty("tool_call_id", tc.id)
                        addProperty("content", result)
                    }
                    messages.add(toolMsg)
                }
            }

            val reply = finalReplyContent ?: if (finalPlan != null) {
                "已为您制定操作计划并生成确认卡片，请在下方卡片中核对目标文件并确认是否移入回收站。"
            } else {
                "已为您完成任务分析。"
            }

            AiMessage(
                sender = "ASSISTANT",
                content = reply,
                plan = finalPlan,
                taskSteps = steps
            )

        } catch (e: Exception) {
            steps.lastOrNull()?.status = "FAILED"
            steps.lastOrNull()?.detail = "模型服务连接失败，切换为本地离线规则执行: ${e.message}"
            onStepUpdated(steps.toList())

            // Seamless fallback to offline rule plan!
            executeOfflineRulePlan(userPrompt, steps, onStepUpdated)
        }
    }

    private suspend fun executeOfflineRulePlan(
        userPrompt: String,
        steps: MutableList<TaskStep>,
        onStepUpdated: (List<TaskStep>) -> Unit
    ): AiMessage = withContext(Dispatchers.IO) {
        val lower = userPrompt.lowercase()

        when {
            lower.contains("占空间") || lower.contains("什么最占") || lower.contains("存储") -> {
                steps.add(TaskStep(1, "查询设备闪存物理占用与分类", "RUNNING", "正在调用底层 StatFs 与 MediaStore..."))
                onStepUpdated(steps.toList())

                val stats = storageEngine.getStorageOverview()
                steps[0].status = "COMPLETED"
                steps[0].detail = "统计完成: 已用 ${formatBytes(stats.usedBytes)} / ${formatBytes(stats.totalBytes)}"
                onStepUpdated(steps.toList())

                val reply = buildString {
                    appendLine("📊 **手机存储空间占用概览**：")
                    appendLine("• 设备总容量：${formatBytes(stats.totalBytes)}")
                    appendLine("• 已使用空间：${formatBytes(stats.usedBytes)}（${String.format("%.1f", stats.usedPercent)}%）")
                    appendLine("• 可用空间：${formatBytes(stats.availableBytes)}")
                    appendLine("\n**各大分类占用**：")
                    appendLine("• 视频：${formatBytes(stats.videosBytes)}")
                    appendLine("• 图片：${formatBytes(stats.imagesBytes)}")
                    appendLine("• 音频：${formatBytes(stats.audioBytes)}")
                    appendLine("• 文档与压缩包：${formatBytes(stats.documentsBytes)}")
                    appendLine("• 安装包 (.apk)：${formatBytes(stats.apksBytes)}")
                    appendLine("• 系统与其他数据：${formatBytes(stats.othersBytes)}")
                    appendLine("\n💡 您可以继续输入指令，如：“找出超过 500MB 的视频”或“排查重复照片”进行针对性清理。")
                }

                AiMessage(sender = "ASSISTANT", content = reply, taskSteps = steps)
            }

            lower.contains("视频") || lower.contains("500mb") || lower.contains("大文件") -> {
                steps.add(TaskStep(1, "检索超过 500MB 的大视频", "RUNNING", "正在扫描 MediaStore 媒体库..."))
                onStepUpdated(steps.toList())

                val videos = mediaScanner.queryLargeVideos(500L * 1024L * 1024L)
                videos.forEach { knownItems[it.id] = it }

                steps[0].status = "COMPLETED"
                steps[0].detail = "共检索到 ${videos.size} 个超过 500MB 的视频"
                onStepUpdated(steps.toList())

                if (videos.isEmpty()) {
                    AiMessage(
                        sender = "ASSISTANT",
                        content = "✅ 检查完毕！手机媒体库中当前没有单个超过 500MB 的大视频文件。",
                        taskSteps = steps
                    )
                } else {
                    val totalSize = videos.sumOf { it.sizeBytes }
                    val reply = buildString {
                        appendLine("🎬 找到 **${videos.size}** 个超过 500MB 的大视频，合计占用 **${formatBytes(totalSize)}**：\n")
                        videos.take(10).forEachIndexed { index, v ->
                            appendLine("${index + 1}. **${v.displayName}** (${v.formatSize()})")
                        }
                        if (videos.size > 10) {
                            appendLine("...及另外 ${videos.size - 10} 个文件")
                        }
                    }

                    AiMessage(
                        sender = "ASSISTANT",
                        content = reply,
                        taskSteps = steps
                    )
                }
            }

            lower.contains("重复") || lower.contains("查重") -> {
                steps.add(TaskStep(1, "扫描媒体库候选文件", "RUNNING", "正在建立候选文件索引..."))
                onStepUpdated(steps.toList())

                val media = mediaScanner.queryMediaItems(limit = 1000)
                media.forEach { knownItems[it.id] = it }

                steps[0].status = "COMPLETED"
                steps[0].detail = "已扫描 ${media.size} 个候选文件"
                steps.add(TaskStep(2, "计算内容 SHA-256 哈希比对", "RUNNING", "正在进行多文件内容哈希校验..."))
                onStepUpdated(steps.toList())

                val dupes = duplicateEngine.findDuplicates(media)
                steps[1].status = "COMPLETED"
                steps[1].detail = "比对完成，发现 ${dupes.size} 组完全重复文件"
                onStepUpdated(steps.toList())

                if (dupes.isEmpty()) {
                    AiMessage(
                        sender = "ASSISTANT",
                        content = "🎉 太棒了！在已扫描的文件范围内，没有发现完全重复的冗余文件。",
                        taskSteps = steps
                    )
                } else {
                    val totalWasted = dupes.sumOf { it.wastedBytes }
                    // Generate action plan to keep one and propose trashing others
                    val targetsToTrash = mutableListOf<ActionTarget>()
                    dupes.forEach { group ->
                        // keep first, propose trash for the rest
                        group.items.drop(1).forEach { item ->
                            targetsToTrash.add(
                                ActionTarget(
                                    id = item.id,
                                    uriString = item.uriString,
                                    displayName = item.displayName,
                                    sizeBytes = item.sizeBytes,
                                    originalPath = item.path
                                )
                            )
                        }
                    }

                    val plan = ActionPlan(
                        actionType = ActionType.TRASH,
                        title = "清理完全重复文件 (保留一份原件)",
                        description = "已通过 SHA-256 哈希比对精确确认内容完全相同。建议将每组中多余的 ${targetsToTrash.size} 个副本移入系统回收站。",
                        targets = targetsToTrash,
                        estimatedReleasableBytes = totalWasted,
                        status = PlanStatus.PREVIEW
                    )

                    val reply = buildString {
                        appendLine("🔍 扫描发现 **${dupes.size}** 组完全重复文件，多余副本共占用 **${formatBytes(totalWasted)}**。")
                        appendLine("我已经为您制定了优化方案（默认每组保留一份最新文件，其余副本移入回收站）。请检查下方操作预览卡片：")
                    }

                    AiMessage(
                        sender = "ASSISTANT",
                        content = reply,
                        plan = plan,
                        taskSteps = steps
                    )
                }
            }

            lower.contains("截图") || lower.contains("截屏") -> {
                steps.add(TaskStep(1, "查找相册截屏图片", "RUNNING", "正在筛选 Screenshots 目录..."))
                onStepUpdated(steps.toList())

                val screenshots = mediaScanner.queryScreenshots(limit = 100)
                screenshots.forEach { knownItems[it.id] = it }

                steps[0].status = "COMPLETED"
                steps[0].detail = "找到 ${screenshots.size} 张截屏"
                onStepUpdated(steps.toList())

                val totalSize = screenshots.sumOf { it.sizeBytes }
                AiMessage(
                    sender = "ASSISTANT",
                    content = "📸 找到 **${screenshots.size}** 张截屏图片，共计占用 **${formatBytes(totalSize)}**。您可在【文件与相册】界面集中浏览或批量管理。",
                    taskSteps = steps
                )
            }

            lower.contains("安装包") || lower.contains("apk") -> {
                steps.add(TaskStep(1, "检索存储目录中的安装包", "RUNNING", "正在扫描 .apk 文件..."))
                onStepUpdated(steps.toList())

                val apks = mediaScanner.queryApks()
                apks.forEach { knownItems[it.id] = it }

                steps[0].status = "COMPLETED"
                steps[0].detail = "检索完成: 找到 ${apks.size} 个 APK 文件"
                onStepUpdated(steps.toList())

                if (apks.isEmpty()) {
                    AiMessage(
                        sender = "ASSISTANT",
                        content = "✅ 扫描完毕！未在已授权的存储路径中发现闲置的 APK 安装包。",
                        taskSteps = steps
                    )
                } else {
                    val total = apks.sumOf { it.sizeBytes }
                    val reply = buildString {
                        appendLine("📦 发现 **${apks.size}** 个旧安装包，总计占用 **${formatBytes(total)}**：\n")
                        apks.forEachIndexed { i, apk ->
                            appendLine("${i + 1}. **${apk.displayName}** (${apk.formatSize()})")
                        }
                    }
                    AiMessage(sender = "ASSISTANT", content = reply, taskSteps = steps)
                }
            }

            lower.contains("相似") || lower.contains("连拍") -> {
                steps.add(TaskStep(1, "扫描相册候选照片", "RUNNING", "正在读取相册照片..."))
                onStepUpdated(steps.toList())

                val media = mediaScanner.queryMediaItems(limit = 600)
                media.forEach { knownItems[it.id] = it }

                steps[0].status = "COMPLETED"
                steps[0].detail = "已扫描 ${media.size} 张照片"
                steps.add(TaskStep(2, "计算感知哈希与时间戳聚类", "RUNNING", "正在进行轻量感知相似比对..."))
                onStepUpdated(steps.toList())

                val similarGroups = similarPhotoEngine.findSimilarCandidates(media)
                steps[1].status = "COMPLETED"
                steps[1].detail = "比对完成，发现 ${similarGroups.size} 组疑似相似照片"
                onStepUpdated(steps.toList())

                if (similarGroups.isEmpty()) {
                    AiMessage(
                        sender = "ASSISTANT",
                        content = "📷 未在相册中发现明显的相似或连拍照片，相册很清爽！",
                        taskSteps = steps
                    )
                } else {
                    val reply = buildString {
                        appendLine("📸 共发现 **${similarGroups.size}** 组视觉相似或连拍照片：\n")
                        similarGroups.take(5).forEachIndexed { i, g ->
                            appendLine("**分组 ${i + 1}**（相似度：${String.format(java.util.Locale.US, "%.0f%%", g.similarityScore * 100)}，共 ${g.items.size} 张）：")
                            g.items.forEach { appendLine("  • ${it.displayName} (${it.formatSize()})") }
                            appendLine()
                        }
                        if (similarGroups.size > 5) {
                            appendLine("...及另外 ${similarGroups.size - 5} 组相似照片。")
                        }
                        appendLine("💡 提示：相似照片可能包含不同表情或瞬间，建议在「相册与文件」中人工预览挑选保留。")
                    }
                    AiMessage(sender = "ASSISTANT", content = reply, taskSteps = steps)
                }
            }

            lower.contains("系统存储") || lower.contains("设置") || lower.contains("清理页面") -> {
                steps.add(TaskStep(1, "打开系统存储设置页面", "RUNNING", "正在调起系统存储 Intent..."))
                onStepUpdated(steps.toList())

                openStorageSettingsInternal()
                steps[0].status = "COMPLETED"
                steps[0].detail = "已请求系统打开存储管理界面"
                onStepUpdated(steps.toList())

                AiMessage(
                    sender = "ASSISTANT",
                    content = "⚙️ 正在为您打开系统存储设置页面，您可以查看系统级应用缓存与应用占用数据。",
                    taskSteps = steps
                )
            }

            lower.contains("连点") || lower.contains("点击") || lower.contains("按一下") || lower.contains("点一下") || lower.contains("点按") -> {
                steps.add(TaskStep(1, "执行屏幕辅助点击操作", "RUNNING", "正在检查无障碍辅助服务..."))
                onStepUpdated(steps.toList())

                if (!ScreenAssistantAccessibilityService.isServiceRunning()) {
                    openAccessibilitySettingsInternal()
                    steps[0].status = "FAILED"
                    steps[0].detail = "无障碍辅助服务尚未开启"
                    onStepUpdated(steps.toList())

                    AiMessage(
                        sender = "ASSISTANT",
                        content = "⚠️ 手机无障碍辅助操作服务尚未开启。已为您打开系统设置页面，请找到并开启【Android AI 助手辅助服务】后即可使用连点器与屏幕点击功能。",
                        taskSteps = steps
                    )
                } else {
                    val keyword = extractClickKeyword(userPrompt)
                    val clicked = if (keyword.isNotEmpty()) {
                        ScreenAssistantAccessibilityService.findAndClick(keyword)
                    } else {
                        false
                    }

                    steps[0].status = if (clicked) "COMPLETED" else "FAILED"
                    steps[0].detail = if (clicked) "成功点击: $keyword" else "未找到匹配控件"
                    onStepUpdated(steps.toList())

                    AiMessage(
                        sender = "ASSISTANT",
                        content = if (clicked) "✅ 成功在屏幕上模拟点击「$keyword」。" else "未能找到屏幕上包含「$keyword」的可点击控件。您可以试着提供更精准的按钮文字或坐标。",
                        taskSteps = steps
                    )
                }
            }

            lower.contains("滑动") || lower.contains("下滑") || lower.contains("上滑") || lower.contains("翻页") -> {
                steps.add(TaskStep(1, "执行屏幕手势滑动", "RUNNING", "正在检查手势服务..."))
                onStepUpdated(steps.toList())

                if (!ScreenAssistantAccessibilityService.isServiceRunning()) {
                    openAccessibilitySettingsInternal()
                    steps[0].status = "FAILED"
                    steps[0].detail = "无障碍辅助服务未开启"
                    onStepUpdated(steps.toList())
                    AiMessage(
                        sender = "ASSISTANT",
                        content = "⚠️ 手机无障碍辅助操作服务尚未开启。已为您调起系统设置页面。",
                        taskSteps = steps
                    )
                } else {
                    val dir = if (lower.contains("上滑") || lower.contains("向上")) "UP" else "DOWN"
                    val success = ScreenAssistantAccessibilityService.swipeDirection(dir)
                    steps[0].status = if (success) "COMPLETED" else "FAILED"
                    steps[0].detail = "滑动方向: $dir"
                    onStepUpdated(steps.toList())
                    AiMessage(
                        sender = "ASSISTANT",
                        content = if (success) "📱 屏幕已向 $dir 方向成功滑动。" else "屏幕滑动操作失败。",
                        taskSteps = steps
                    )
                }
            }

            lower.contains("返回") || lower.contains("回桌面") || lower.contains("桌面") -> {
                steps.add(TaskStep(1, "执行系统全局按键", "RUNNING", "正在发送全局指令..."))
                onStepUpdated(steps.toList())

                if (!ScreenAssistantAccessibilityService.isServiceRunning()) {
                    openAccessibilitySettingsInternal()
                    steps[0].status = "FAILED"
                    steps[0].detail = "无障碍服务未开启"
                    onStepUpdated(steps.toList())
                    AiMessage(
                        sender = "ASSISTANT",
                        content = "⚠️ 手机无障碍辅助操作服务尚未开启。已调起系统设置页面。",
                        taskSteps = steps
                    )
                } else {
                    val action = if (lower.contains("桌面")) "HOME" else "BACK"
                    val ok = ScreenAssistantAccessibilityService.performGlobal(action)
                    steps[0].status = if (ok) "COMPLETED" else "FAILED"
                    steps[0].detail = "执行动作: $action"
                    onStepUpdated(steps.toList())
                    AiMessage(
                        sender = "ASSISTANT",
                        content = if (ok) "📱 已成功执行全局系统操作：$action。" else "执行全局按键失败。",
                        taskSteps = steps
                    )
                }
            }

            lower.contains("屏幕有什么") || lower.contains("看屏幕") || lower.contains("查看屏幕") -> {
                if (!ScreenAssistantAccessibilityService.isServiceRunning()) {
                    openAccessibilitySettingsInternal()
                    AiMessage(
                        sender = "ASSISTANT",
                        content = "⚠️ 无障碍辅助服务尚未开启，无法分析屏幕。已为您打开系统设置页面。"
                    )
                } else {
                    val summary = ScreenAssistantAccessibilityService.inspectScreenSummary()
                    AiMessage(
                        sender = "ASSISTANT",
                        content = "🖥️ **当前屏幕可见元素摘要（本地无障碍提取，极省 Token）**：\n\n$summary"
                    )
                }
            }

            lower.contains("无障碍") || lower.contains("连点器") || lower.contains("辅助服务") -> {
                openAccessibilitySettingsInternal()
                AiMessage(
                    sender = "ASSISTANT",
                    content = "⚙️ 正在为您打开系统【无障碍设置】页面。请在列表中找到并开启【Android AI 助手辅助服务】，开启后即可支持屏幕模拟点击、手势滑动及自动导航！"
                )
            }

            lower.contains("悬浮窗") || lower.contains("悬浮球") || lower.contains("浮窗") -> {
                val hasPermission = FloatingAssistantManager.hasOverlayPermission(context)
                if (!hasPermission) {
                    FloatingAssistantManager.requestOverlayPermission(context)
                    AiMessage(
                        sender = "ASSISTANT",
                        content = "⚙️ 桌面悬浮窗需要「显示在其他应用上层」权限。已为您调起系统权限设置页面，开启后即可使用。"
                    )
                } else {
                    FloatingAssistantManager.startFloatingService(context)
                    AiMessage(
                        sender = "ASSISTANT",
                        content = "🎉 桌面悬浮窗助手已启动！您现在可以返回桌面或在任何第三方应用中使用屏幕悬浮球随时与我对话。"
                    )
                }
            }

            else -> {
                AiMessage(
                    sender = "ASSISTANT",
                    content = """
                        您好！我是您的本地 AI 文件与相册管理助手。我可以为您执行以下真实任务：
                        • **“看看手机里什么最占空间”**：分析磁盘与各类别物理占用
                        • **“找出超过 500MB 的视频”**：筛选超大媒体文件
                        • **“排查重复照片”**：计算 SHA-256 精准识别完全重复副本并提供保留建议
                        • **“排查相似照片”**：轻量感知哈希分析连拍与相似照片
                        • **“查看截图文件”**：快速统计相册截屏
                        • **“找出旧安装包”**：清理残留的 APK 安装文件
                        • **“操作屏幕/连点器”**：点击界面文字、滑动翻页、模拟手势或查看屏幕元素
                        • **“打开桌面悬浮窗”**：开启随时随地悬浮球跨屏对话
                        • **“打开系统存储页面”**：辅助导航至系统存储管理
                    """.trimIndent()
                )
            }
        }
    }

    private suspend fun executeToolCall(
        name: String,
        argumentsJson: String,
        onPlanCreated: (ActionPlan) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val args = runCatching { gson.fromJson(argumentsJson, JsonObject::class.java) }.getOrDefault(JsonObject())

        when (name) {
            "query_storage_stats" -> {
                val stats = storageEngine.getStorageOverview()
                val summary = JsonObject().apply {
                    addProperty("total_bytes", stats.totalBytes)
                    addProperty("total_formatted", formatBytes(stats.totalBytes))
                    addProperty("used_bytes", stats.usedBytes)
                    addProperty("used_formatted", formatBytes(stats.usedBytes))
                    addProperty("used_percent", String.format(java.util.Locale.US, "%.1f", stats.usedPercent))
                    addProperty("available_bytes", stats.availableBytes)
                    addProperty("available_formatted", formatBytes(stats.availableBytes))
                    addProperty("videos_formatted", formatBytes(stats.videosBytes))
                    addProperty("images_formatted", formatBytes(stats.imagesBytes))
                    addProperty("audio_formatted", formatBytes(stats.audioBytes))
                    addProperty("documents_formatted", formatBytes(stats.documentsBytes))
                    addProperty("apks_formatted", formatBytes(stats.apksBytes))
                    addProperty("system_and_others_formatted", formatBytes(stats.othersBytes))
                }
                summary.toString()
            }
            "query_large_files" -> {
                val minSizeMb = if (args.has("min_size_mb")) args.get("min_size_mb").asLong else 500L
                val items = mediaScanner.queryLargeVideos(minSizeMb * 1024L * 1024L)
                items.forEach { knownItems[it.id] = it }
                val summary = JsonObject().apply {
                    addProperty("count", items.size)
                    addProperty("total_bytes", items.sumOf { it.sizeBytes })
                    addProperty("total_formatted", formatBytes(items.sumOf { it.sizeBytes }))
                    val list = JsonArray()
                    items.take(8).forEach {
                        list.add(JsonObject().apply {
                            addProperty("id", it.id)
                            addProperty("displayName", it.displayName)
                            addProperty("sizeFormatted", it.formatSize())
                        })
                    }
                    add("items", list)
                }
                summary.toString()
            }
            "find_duplicate_files" -> {
                val items = mediaScanner.queryMediaItems(limit = 1000)
                items.forEach { knownItems[it.id] = it }
                val dupes = duplicateEngine.findDuplicates(items)
                val summary = JsonObject().apply {
                    addProperty("group_count", dupes.size)
                    addProperty("total_wasted_bytes", dupes.sumOf { it.wastedBytes })
                    addProperty("total_wasted_formatted", formatBytes(dupes.sumOf { it.wastedBytes }))
                    val groups = JsonArray()
                    dupes.take(6).forEach { g ->
                        groups.add(JsonObject().apply {
                            addProperty("size_formatted", formatBytes(g.fileSize))
                            val keep = g.items.firstOrNull()
                            if (keep != null) {
                                add("keep_original", JsonObject().apply {
                                    addProperty("id", keep.id)
                                    addProperty("displayName", keep.displayName)
                                })
                            }
                            val copies = JsonArray()
                            g.items.drop(1).forEach { cp ->
                                copies.add(JsonObject().apply {
                                    addProperty("id", cp.id)
                                    addProperty("displayName", cp.displayName)
                                })
                            }
                            add("redundant_copies", copies)
                        })
                    }
                    add("duplicate_groups", groups)
                }
                summary.toString()
            }
            "find_similar_photos" -> {
                val items = mediaScanner.queryMediaItems(limit = 600)
                items.forEach { knownItems[it.id] = it }
                val groups = similarPhotoEngine.findSimilarCandidates(items)
                val summary = JsonObject().apply {
                    addProperty("candidate_group_count", groups.size)
                    val jsonGroups = JsonArray()
                    groups.take(4).forEach { g ->
                        jsonGroups.add(JsonObject().apply {
                            addProperty("similarity_score", String.format(java.util.Locale.US, "%.2f", g.similarityScore))
                            val itemsArr = JsonArray()
                            g.items.forEach { itm ->
                                itemsArr.add(JsonObject().apply {
                                    addProperty("id", itm.id)
                                    addProperty("displayName", itm.displayName)
                                    addProperty("sizeFormatted", itm.formatSize())
                                })
                            }
                            add("photos", itemsArr)
                        })
                    }
                    add("groups", jsonGroups)
                }
                summary.toString()
            }
            "query_screenshots" -> {
                val items = mediaScanner.queryScreenshots()
                items.forEach { knownItems[it.id] = it }
                val summary = JsonObject().apply {
                    addProperty("count", items.size)
                    addProperty("total_formatted", formatBytes(items.sumOf { it.sizeBytes }))
                    val list = JsonArray()
                    items.take(8).forEach {
                        list.add(JsonObject().apply {
                            addProperty("id", it.id)
                            addProperty("displayName", it.displayName)
                            addProperty("sizeFormatted", it.formatSize())
                        })
                    }
                    add("items", list)
                }
                summary.toString()
            }
            "query_apk_files" -> {
                val items = mediaScanner.queryApks()
                items.forEach { knownItems[it.id] = it }
                val summary = JsonObject().apply {
                    addProperty("count", items.size)
                    addProperty("total_formatted", formatBytes(items.sumOf { it.sizeBytes }))
                    val list = JsonArray()
                    items.take(8).forEach {
                        list.add(JsonObject().apply {
                            addProperty("id", it.id)
                            addProperty("displayName", it.displayName)
                            addProperty("sizeFormatted", it.formatSize())
                        })
                    }
                    add("items", list)
                }
                summary.toString()
            }
            "propose_trash_plan" -> {
                val idsArray = if (args.has("target_ids")) args.getAsJsonArray("target_ids") else JsonArray()
                val reason = if (args.has("reason")) args.get("reason").asString else "移入回收站"

                val targetList = mutableListOf<ActionTarget>()
                for (idElem in idsArray) {
                    val id = idElem.asLong
                    val item = knownItems[id] ?: mediaScanner.queryItemById(id)
                    if (item != null) {
                        knownItems[item.id] = item
                        targetList.add(
                            ActionTarget(
                                id = item.id,
                                uriString = item.uriString,
                                displayName = item.displayName,
                                sizeBytes = item.sizeBytes,
                                originalPath = item.path
                            )
                        )
                    }
                }

                if (targetList.isEmpty()) {
                    "无法生成操作卡片：请求的目标 ID 均未在本地扫描库中找到真实对应对象。"
                } else {
                    val totalBytes = targetList.sumOf { it.sizeBytes }
                    val plan = ActionPlan(
                        actionType = ActionType.TRASH,
                        title = "移入系统媒体回收站",
                        description = reason,
                        targets = targetList,
                        estimatedReleasableBytes = totalBytes,
                        status = PlanStatus.PREVIEW
                    )
                    onPlanCreated(plan)
                    "已生成操作预览卡片，包含 ${targetList.size} 个文件，预计可释放 ${formatBytes(totalBytes)}。已安全暂停执行，等待用户在界面确认！"
                }
            }
            "open_storage_settings" -> {
                openStorageSettingsInternal()
                "已启动 Intent 跳转系统存储设置页面。"
            }
            "operate_screen_click" -> {
                if (!ScreenAssistantAccessibilityService.isServiceRunning()) {
                    openAccessibilitySettingsInternal()
                    "屏幕操作未完成：手机无障碍辅助服务尚未开启。已为您打开系统无障碍设置页面，请找到并开启【Android AI 助手辅助服务】即可使用连点器及屏幕操作功能。"
                } else {
                    val text = if (args.has("text") && !args.get("text").isJsonNull) args.get("text").asString else null
                    val x = if (args.has("x") && !args.get("x").isJsonNull) args.get("x").asFloat else null
                    val y = if (args.has("y") && !args.get("y").isJsonNull) args.get("y").asFloat else null

                    when {
                        !text.isNullOrBlank() -> {
                            val clicked = ScreenAssistantAccessibilityService.findAndClick(text)
                            if (clicked) {
                                "成功点击屏幕上包含文字「$text」的控件。"
                            } else {
                                "未在当前屏幕中找到包含文字「$text」的控件。您可以先调用 inspect_current_screen 查看当前屏幕元素或使用坐标模拟点击。"
                            }
                        }
                        x != null && y != null -> {
                            val clicked = ScreenAssistantAccessibilityService.clickAt(x, y)
                            if (clicked) {
                                "成功在屏幕坐标 (${x.toInt()}, ${y.toInt()}) 模拟点击（连点器手势模式）。"
                            } else {
                                "在屏幕坐标 (${x.toInt()}, ${y.toInt()}) 模拟手势点击失败。"
                            }
                        }
                        else -> "参数错误：请提供要点击的文字 (text) 或坐标 (x, y)。"
                    }
                }
            }
            "operate_screen_swipe" -> {
                if (!ScreenAssistantAccessibilityService.isServiceRunning()) {
                    openAccessibilitySettingsInternal()
                    "无障碍辅助服务尚未开启，已调起系统设置页面。"
                } else {
                    val dir = if (args.has("direction") && !args.get("direction").isJsonNull) args.get("direction").asString else "UP"
                    val success = ScreenAssistantAccessibilityService.swipeDirection(dir)
                    if (success) {
                        "成功向 ${dir.uppercase()} 方向滑动屏幕。"
                    } else {
                        "屏幕滑动操作失败。"
                    }
                }
            }
            "operate_screen_global_action" -> {
                if (!ScreenAssistantAccessibilityService.isServiceRunning()) {
                    openAccessibilitySettingsInternal()
                    "无障碍辅助服务尚未开启，已调起系统设置页面。"
                } else {
                    val action = if (args.has("action") && !args.get("action").isJsonNull) args.get("action").asString else "BACK"
                    val success = ScreenAssistantAccessibilityService.performGlobal(action)
                    if (success) {
                        "成功执行系统全局动作: $action。"
                    } else {
                        "执行全局动作 $action 失败。"
                    }
                }
            }
            "inspect_current_screen" -> {
                if (!ScreenAssistantAccessibilityService.isServiceRunning()) {
                    openAccessibilitySettingsInternal()
                    "无障碍辅助服务未开启，无法获取屏幕内容。已调起系统无障碍设置页面。"
                } else {
                    ScreenAssistantAccessibilityService.inspectScreenSummary()
                }
            }
            "open_accessibility_settings" -> {
                openAccessibilitySettingsInternal()
                "已启动 Intent 打开系统无障碍设置页面，请开启【Android AI 助手辅助服务】。"
            }
            "query_file_detail" -> {
                val fileId = if (args.has("file_id")) args.get("file_id").asLong else -1L
                if (fileId <= 0L) {
                    "参数错误：必须提供有效的 file_id。"
                } else {
                    val item = knownItems[fileId] ?: mediaScanner.queryItemById(fileId)
                    if (item == null) {
                        "未找到 ID 为 $fileId 的文件记录。"
                    } else {
                        knownItems[item.id] = item
                        val obj = JsonObject().apply {
                            addProperty("id", item.id)
                            addProperty("displayName", item.displayName)
                            addProperty("sizeFormatted", item.formatSize())
                            addProperty("sizeBytes", item.sizeBytes)
                            addProperty("mediaType", item.mediaType.name)
                            addProperty("path", item.path)
                            addProperty("bucketName", item.bucketName)
                            addProperty("isScreenshot", item.isScreenshot)
                        }
                        obj.toString()
                    }
                }
            }
            "open_floating_window" -> {
                val hasPermission = FloatingAssistantManager.hasOverlayPermission(context)
                if (!hasPermission) {
                    FloatingAssistantManager.requestOverlayPermission(context)
                    "已为您打开系统悬浮窗权限设置页面，请允许「显示在其他应用上层」后即可启用桌面悬浮窗。"
                } else {
                    FloatingAssistantManager.startFloatingService(context)
                    "已成功为您开启桌面悬浮窗助手！您可以返回桌面或切换到任意应用，点击屏幕边缘的悬浮球即可随时与我对话。"
                }
            }
            else -> "未知工具: $name"
        }
    }

    private fun extractClickKeyword(prompt: String): String {
        val triggers = listOf("点击", "点一下", "按一下", "点按", "帮我点")
        for (trig in triggers) {
            val idx = prompt.indexOf(trig)
            if (idx != -1) {
                val rem = prompt.substring(idx + trig.length).trim().trim('“', '”', '"', '\'', '「', '」', ' ')
                if (rem.isNotEmpty()) return rem
            }
        }
        return prompt.trim()
    }

    private fun openStorageSettingsInternal() {
        try {
            val intent = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallback)
        }
    }

    private fun openAccessibilitySettingsInternal() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallback)
        }
    }
}
