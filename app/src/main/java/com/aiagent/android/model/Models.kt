package com.aiagent.android.model

import java.util.UUID

enum class MediaType {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    APK,
    OTHER
}

data class MediaItem(
    val id: Long,
    val uriString: String,
    val displayName: String,
    val path: String,
    val sizeBytes: Long,
    val dateModified: Long,
    val mimeType: String,
    val mediaType: MediaType,
    val isScreenshot: Boolean = false,
    val durationMs: Long = 0L,
    val bucketName: String = "",
    val isTrashed: Boolean = false
) {
    fun formatSize(): String = formatBytes(sizeBytes)
}

data class StorageStat(
    val totalBytes: Long,
    val availableBytes: Long,
    val usedBytes: Long,
    val imagesBytes: Long = 0L,
    val videosBytes: Long = 0L,
    val audioBytes: Long = 0L,
    val documentsBytes: Long = 0L,
    val apksBytes: Long = 0L,
    val othersBytes: Long = 0L
) {
    val usedPercent: Float
        get() = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes) * 100f else 0f
}

data class DuplicateGroup(
    val contentHash: String,
    val fileSize: Long,
    val items: List<MediaItem>
) {
    val wastedBytes: Long
        get() = if (items.size > 1) fileSize * (items.size - 1) else 0L
}

data class SimilarPhotoGroup(
    val groupId: String,
    val similarityScore: Float,
    val items: List<MediaItem>
)

enum class ActionType {
    TRASH,
    RESTORE,
    MOVE,
    RENAME
}

enum class PlanStatus {
    DRAFT,
    PREVIEW,
    EXECUTING,
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    CANCELLED
}

data class ActionTarget(
    val id: Long,
    val uriString: String,
    val displayName: String,
    val sizeBytes: Long,
    val originalPath: String,
    val targetPath: String? = null,
    val status: String = "PENDING"
)

data class ActionPlan(
    val planId: String = UUID.randomUUID().toString(),
    val actionType: ActionType,
    val title: String,
    val description: String,
    val targets: List<ActionTarget>,
    val estimatedReleasableBytes: Long,
    val isRecoverable: Boolean = true,
    var status: PlanStatus = PlanStatus.PREVIEW,
    val createdAt: Long = System.currentTimeMillis()
)

data class AuditRecord(
    val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val actionType: ActionType,
    val planTitle: String,
    val targetCount: Int,
    val releasedBytes: Long,
    val status: String,
    val detailsJson: String
)

data class TaskStep(
    val stepIndex: Int,
    val title: String,
    var status: String = "PENDING", // PENDING, RUNNING, COMPLETED, FAILED
    var detail: String = ""
)

data class AiMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String, // "USER", "ASSISTANT", "SYSTEM"
    val content: String,
    var plan: ActionPlan? = null,
    var taskSteps: List<TaskStep> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    if (digitGroups >= units.size) digitGroups = units.size - 1
    val df = java.text.DecimalFormat("#,##0.#")
    return "${df.format(bytes / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
}
