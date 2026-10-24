package com.aiagent.android.ai

import android.content.Context
import androidx.compose.runtime.*
import com.aiagent.android.data.ConversationStore
import com.aiagent.android.model.*
import com.aiagent.android.service.AgentTaskService
import kotlinx.coroutines.*

/** Durable, shared conversation. Restoring a record never dispatches phone actions. */
class AgentConversation(
    private val context: Context,
    private val orchestrator: AgentOrchestrator,
    private val store: ConversationStore
) {
    val messages = mutableStateListOf<AiMessage>()
    val activeSteps = mutableStateListOf<TaskStep>()
    val sessions = mutableStateListOf<ConversationSummary>()
    var currentSessionId by mutableStateOf("")
        private set
    var currentTitle by mutableStateOf("新对话")
        private set
    var checkpoint by mutableStateOf<TaskCheckpoint?>(null)
        private set
    var isProcessing by mutableStateOf(false)
        private set
    var isLoading by mutableStateOf(true)
        private set
    var storageError by mutableStateOf<String?>(null)
        private set
    private var createdAt = System.currentTimeMillis()
    private var updatedAt = createdAt
    private var revision = 0L
    private var initialized = false
    private var runJob: Job? = null
    private var activeAttempt: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init { restoreLatest() }

    private fun restoreLatest() {
        scope.launch {
            isLoading = true
            try {
                val loaded = store.latest()
                val restored = loaded?.let { restoredSnapshot(it) } ?: ConversationSnapshot()
                if (loaded != null && restored != loaded) store.save(restored)
                apply(restored)
                refreshSessions()
                initialized = true
                storageError = null
            } catch (_: Exception) {
                storageError = "本地会话暂时无法读取，已保留原始数据。请重试；不会清空或覆盖旧会话。"
            } finally { isLoading = false }
        }
    }

    private fun restoredSnapshot(saved: ConversationSnapshot): ConversationSnapshot {
        val interrupted = saved.checkpoint?.status == "RUNNING"
        val restoredCheckpoint = saved.checkpoint?.let { task ->
            if (interrupted) task.copy(status = "INTERRUPTED", updatedAt = System.currentTimeMillis(),
                steps = task.steps.map { it.copy(status = if (it.status == "RUNNING") "INTERRUPTED" else it.status) }) else task
        }
        var plansChanged = false
        val restoredMessages = saved.messages.map { message ->
            val plan = message.plan
            if (plan != null && plan.status in setOf(PlanStatus.PREVIEW, PlanStatus.EXECUTING)) {
                plansChanged = true
                message.copy(plan = plan.copy(status = PlanStatus.STALE))
            } else message
        }.toMutableList()
        if (interrupted) restoredMessages.add(AiMessage(sender = "ASSISTANT", taskStatus = "INTERRUPTED",
            content = "上次任务因应用退出而中断。已恢复对话与步骤；请核对手机当前状态后点“继续任务”。恢复记录不会自动操作手机。",
            taskSteps = restoredCheckpoint?.steps.orEmpty()))
        return if (interrupted || plansChanged) saved.copy(messages = restoredMessages, checkpoint = restoredCheckpoint,
            revision = saved.revision + 1, updatedAt = System.currentTimeMillis()) else saved
    }

    private fun apply(saved: ConversationSnapshot) {
        currentSessionId = saved.sessionId
        currentTitle = saved.title
        createdAt = saved.createdAt
        updatedAt = saved.updatedAt
        revision = saved.revision
        messages.clear(); messages.addAll(saved.messages)
        checkpoint = saved.checkpoint
        activeSteps.clear(); activeSteps.addAll(saved.checkpoint?.steps.orEmpty())
    }

    private fun changed() { revision++; updatedAt = System.currentTimeMillis() }
    private fun snapshot() = ConversationSnapshot(currentSessionId, currentTitle, createdAt, updatedAt, revision,
        messages.map { message -> message.copy(plan = message.plan?.let { it.copy(targets = it.targets.toList()) },
            taskSteps = message.taskSteps.map { it.copy() }) },
        checkpoint?.let { it.copy(steps = it.steps.map { step -> step.copy() }) })

    private suspend fun persistCurrent() {
        try { store.save(snapshot(), activate = true); storageError = null }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            storageError = "会话保存失败，已暂停后续操作。请重试本地保存后再继续。"
            throw e
        }
    }

    private fun saveSoon() {
        if (!initialized) return
        val saved = snapshot()
        scope.launch {
            try { store.save(saved); refreshSessions() }
            catch (_: Exception) { storageError = "会话保存失败，请重试本地保存。" }
        }
    }

    private suspend fun refreshSessions() {
        val saved = store.listSessions()
        sessions.clear(); sessions.addAll(saved)
    }

    fun retryStorage() {
        if (isProcessing || isLoading) return
        if (!initialized) { restoreLatest(); return }
        scope.launch {
            isLoading = true
            try { persistCurrent(); refreshSessions() }
            catch (_: Exception) { /* Keep the visible error and the original records. */ }
            finally { isLoading = false }
        }
    }

    fun newChat() {
        if (isProcessing || isLoading || !initialized || storageError != null) return
        scope.launch {
            isLoading = true
            try {
                if (messages.isNotEmpty()) persistCurrent()
                apply(ConversationSnapshot())
                refreshSessions()
            } catch (_: Exception) { /* Do not discard the active conversation on save failure. */ }
            finally { isLoading = false }
        }
    }

    fun selectSession(id: String) {
        if (id == currentSessionId || isProcessing || isLoading || !initialized || storageError != null) return
        scope.launch {
            isLoading = true
            try {
                if (messages.isNotEmpty()) persistCurrent()
                val saved = store.load(id) ?: error("会话不存在")
                val restored = restoredSnapshot(saved)
                if (restored != saved) store.save(restored)
                store.activate(restored.sessionId)
                apply(restored)
                refreshSessions()
            } catch (_: Exception) { storageError = "无法打开该会话，已保留当前会话和磁盘数据。" }
            finally { isLoading = false }
        }
    }

    fun recordMessage(message: AiMessage) { messages.add(message); changed(); saveSoon() }
    fun replaceMessageAt(index: Int, message: AiMessage) { messages[index] = message; changed(); saveSoon() }
    fun recordStateChange() { changed(); saveSoon() }
    fun resolvePlan(plan: ActionPlan) {
        val task = checkpoint ?: return
        if (task.planId != plan.planId) return
        val status = when (plan.status) {
            PlanStatus.SUCCESS -> "COMPLETED"
            PlanStatus.PARTIAL_SUCCESS -> "INCOMPLETE"
            PlanStatus.FAILED -> "BLOCKED"
            PlanStatus.CANCELLED -> "CANCELLED"
            else -> return
        }
        checkpoint = task.copy(status = status, updatedAt = System.currentTimeMillis())
        changed(); saveSoon()
    }
    fun stop() { orchestrator.cancelCurrentTask(); runJob?.cancel() }

    fun submit(prompt: String) = start(prompt, null)

    /** Called only after the UI confirmation bound to this exact checkpoint. */
    fun resumeTask(taskId: String) {
        val saved = checkpoint ?: return
        if (saved.taskId != taskId || !saved.canResume) return
        start(saved.goal, saved)
    }

    private fun start(prompt: String, recovery: TaskCheckpoint?) {
        if (isProcessing || isLoading || !initialized || storageError != null || prompt.isBlank()) return
        val history = messages.toList()
        val attempt = java.util.UUID.randomUUID().toString()
        activeAttempt = attempt
        messages.add(AiMessage(sender = "USER", content = if (recovery == null) prompt.trim() else "继续任务：${recovery.goal}"))
        if (currentTitle == "新对话") currentTitle = prompt.trim().take(40)
        checkpoint = recovery?.copy(status = "RUNNING", updatedAt = System.currentTimeMillis()) ?: TaskCheckpoint(goal = prompt.trim())
        activeSteps.clear(); activeSteps.addAll(recovery?.steps.orEmpty())
        changed()
        isProcessing = true
        runJob = scope.launch {
            try {
                // This save must complete before any model request or Android action is dispatched.
                persistCurrent()
                AgentTaskService.start(context)
                val reply = orchestrator.processGoal(
                    userPrompt = prompt.trim(), history = history, recovery = recovery,
                    onProgress = { progress ->
                        withContext(Dispatchers.Main.immediate) {
                            val previous = checkpoint
                            checkpoint = checkpoint?.copy(steps = progress.steps.map { it.copy() },
                                pendingTool = progress.pendingTool, lastPackage = progress.lastPackage,
                                updatedAt = System.currentTimeMillis())
                            changed()
                            try { persistCurrent() } catch (e: Exception) {
                                checkpoint = checkpoint?.copy(pendingTool = previous?.pendingTool ?: progress.pendingTool)
                                changed()
                                throw e
                            }
                            AgentTaskService.update(context, progress.steps.lastOrNull()?.title ?: "正在规划任务")
                        }
                    },
                    onStepUpdated = { steps ->
                        if (activeAttempt == attempt && isProcessing) { activeSteps.clear(); activeSteps.addAll(steps) }
                    }
                )
                messages.add(reply)
                checkpoint = checkpoint?.copy(status = reply.taskStatus, steps = reply.taskSteps.map { it.copy() }, planId = reply.plan?.planId,
                    pendingTool = if (reply.taskStatus in setOf("COMPLETED", "ANSWERED")) null else checkpoint?.pendingTool,
                    updatedAt = System.currentTimeMillis())
                changed(); persistCurrent()
            } catch (_: CancellationException) {
                val cancelled = activeSteps.map { it.copy(status = if (it.status == "RUNNING") "CANCELLED" else it.status) }
                activeSteps.clear(); activeSteps.addAll(cancelled)
                messages.add(AiMessage(sender = "ASSISTANT", content = "已停止任务。已发生的操作不会自动撤销。",
                    taskStatus = "CANCELLED", taskSteps = activeSteps.map { it.copy() }))
                checkpoint = checkpoint?.copy(status = "CANCELLED", steps = activeSteps.map { it.copy() }, updatedAt = System.currentTimeMillis())
                changed()
                withContext(NonCancellable) { runCatching { persistCurrent() } }
            } catch (_: Exception) {
                messages.add(AiMessage(sender = "ASSISTANT", content = storageError ?: "任务中断，已保存进度。请检查连接并核对当前界面后继续。", taskStatus = "BLOCKED", taskSteps = activeSteps.map { it.copy() }))
                checkpoint = checkpoint?.copy(status = "BLOCKED", updatedAt = System.currentTimeMillis())
                changed()
                withContext(NonCancellable) { runCatching { persistCurrent() } }
            } finally {
                AgentTaskService.stop(context)
                isProcessing = false
                runJob = null
                activeAttempt = null
                runCatching { refreshSessions() }
            }
        }
    }
}
