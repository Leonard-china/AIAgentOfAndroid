package com.aiagent.android.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aiagent.android.model.AiMessage
import com.aiagent.android.model.TaskStep
import kotlinx.coroutines.*

/** One in-process conversation shared by the activity, floating window and quick chat. */
class AgentConversation(private val orchestrator: AgentOrchestrator) {
    val messages = mutableStateListOf<AiMessage>()
    val activeSteps = mutableStateListOf<TaskStep>()
    var isProcessing by mutableStateOf(false)
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun newChat() {
        if (isProcessing) return
        messages.clear()
        activeSteps.clear()
    }

    fun submit(prompt: String) {
        if (isProcessing || prompt.isBlank()) return
        val history = messages.toList()
        messages.add(AiMessage(sender = "USER", content = prompt.trim()))
        isProcessing = true
        activeSteps.clear()
        scope.launch {
            try {
                val reply = orchestrator.processGoal(prompt.trim(), history) { steps ->
                    activeSteps.clear()
                    activeSteps.addAll(steps)
                }
                messages.add(reply)
            } catch (_: CancellationException) {
                messages.add(AiMessage(sender = "ASSISTANT", content = "已停止任务。已发生的操作不会自动撤销。",
                    taskStatus = "CANCELLED", taskSteps = activeSteps.map {
                        it.copy(status = if (it.status == "RUNNING") "CANCELLED" else it.status)
                    }))
            } catch (_: Exception) {
                messages.add(AiMessage(sender = "ASSISTANT", content = "任务中断，请检查连接后重试。", taskStatus = "BLOCKED",
                    taskSteps = activeSteps.map { it.copy(status = if (it.status == "RUNNING") "FAILED" else it.status) }))
            } finally {
                isProcessing = false
            }
        }
    }
}
