package com.aiagent.android.model

import java.util.UUID

data class TaskCheckpoint(
    val taskId: String = UUID.randomUUID().toString(),
    val goal: String,
    val status: String = "RUNNING",
    val steps: List<TaskStep> = emptyList(),
    val pendingTool: String? = null,
    val lastPackage: String = "",
    val planId: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val canResume: Boolean
        get() = status in setOf("INTERRUPTED", "BLOCKED", "NEEDS_USER", "INCOMPLETE", "CANCELLED")
}

data class ConversationSnapshot(
    val sessionId: String = UUID.randomUUID().toString(),
    val title: String = "新对话",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val revision: Long = 0,
    val messages: List<AiMessage> = emptyList(),
    val checkpoint: TaskCheckpoint? = null
)

data class ConversationSummary(
    val sessionId: String,
    val title: String,
    val updatedAt: Long,
    val taskStatus: String?
)

data class TaskProgress(
    val steps: List<TaskStep>,
    val pendingTool: String?,
    val lastPackage: String
)
