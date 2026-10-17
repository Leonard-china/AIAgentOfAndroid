package com.aiagent.android.tools

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import com.aiagent.android.data.AppDatabaseHelper
import com.aiagent.android.model.ActionPlan
import com.aiagent.android.model.ActionTarget
import com.aiagent.android.model.ActionType
import com.aiagent.android.model.AuditRecord
import com.aiagent.android.model.PlanStatus
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TrashOperationExecutor(
    private val context: Context,
    private val dbHelper: AppDatabaseHelper
) {

    sealed class TrashResult {
        data class RequiresUserConsent(val intentSenderRequest: IntentSenderRequest, val plan: ActionPlan) : TrashResult()
        data class Completed(val plan: ActionPlan, val releasedBytes: Long, val auditId: Long) : TrashResult()
        data class Failed(val plan: ActionPlan, val errorMessage: String) : TrashResult()
        data class TargetChanged(val plan: ActionPlan, val missingCount: Int) : TrashResult()
    }

    suspend fun prepareTrashRequest(plan: ActionPlan): TrashResult = withContext(Dispatchers.IO) {
        // Step 1: Re-verify that target files still exist
        val verifiedTargets = mutableListOf<ActionTarget>()
        val missingTargets = mutableListOf<ActionTarget>()

        for (target in plan.targets) {
            val uri = Uri.parse(target.uriString)
            val exists = verifyUriExists(uri)
            if (exists) {
                verifiedTargets.add(target)
            } else {
                missingTargets.add(target)
            }
        }

        if (missingTargets.isNotEmpty()) {
            return@withContext TrashResult.TargetChanged(
                plan = plan.copy(targets = verifiedTargets),
                missingCount = missingTargets.size
            )
        }

        if (verifiedTargets.isEmpty()) {
            return@withContext TrashResult.Failed(plan, "没有可操作的有效文件（目标可能已被外部移除）")
        }

        val uris = verifiedTargets.map { Uri.parse(it.uriString) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent: PendingIntent = MediaStore.createTrashRequest(
                    context.contentResolver,
                    uris,
                    true // true = move to trash
                )
                val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                return@withContext TrashResult.RequiresUserConsent(request, plan)
            } catch (e: Exception) {
                return@withContext TrashResult.Failed(plan, "创建系统回收站请求失败: ${e.message}")
            }
        } else {
            // Android 10 or below fallback
            return@withContext TrashResult.Failed(plan, "当前 Android 版本低于 Android 11，不支持系统媒体回收站")
        }
    }

    suspend fun onConsentResultReceived(
        plan: ActionPlan,
        resultCode: Int
    ): TrashResult = withContext(Dispatchers.IO) {
        if (resultCode == Activity.RESULT_OK) {
            // Re-verify files are now in trash
            val totalBytes = plan.targets.sumOf { it.sizeBytes }
            val updatedPlan = plan.copy(status = PlanStatus.SUCCESS)

            val auditId = dbHelper.insertAuditRecord(
                AuditRecord(
                    actionType = ActionType.TRASH,
                    planTitle = plan.title,
                    targetCount = plan.targets.size,
                    releasedBytes = totalBytes,
                    status = "SUCCESS_TRASHED",
                    detailsJson = Gson().toJson(plan.targets.map { it.displayName to it.originalPath })
                )
            )

            TrashResult.Completed(updatedPlan, totalBytes, auditId)
        } else {
            val updatedPlan = plan.copy(status = PlanStatus.CANCELLED)
            dbHelper.insertAuditRecord(
                AuditRecord(
                    actionType = ActionType.TRASH,
                    planTitle = plan.title,
                    targetCount = plan.targets.size,
                    releasedBytes = 0L,
                    status = "CANCELLED_BY_USER",
                    detailsJson = Gson().toJson(plan.targets.map { it.displayName })
                )
            )
            TrashResult.Failed(updatedPlan, "用户在系统弹窗中取消了移入回收站操作")
        }
    }

    suspend fun prepareRestoreRequest(uris: List<Uri>): IntentSenderRequest? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent = MediaStore.createTrashRequest(
                    context.contentResolver,
                    uris,
                    false // false = restore from trash
                )
                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            } catch (e: Exception) {
                null
            }
        } else null
    }

    private fun verifyUriExists(uri: Uri): Boolean {
        return try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                null,
                null,
                null
            )
            cursor?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) {
            false
        }
    }
}
