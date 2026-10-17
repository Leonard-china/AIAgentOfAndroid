package com.aiagent.android.tools

import android.content.Context
import android.net.Uri
import com.aiagent.android.data.AppDatabaseHelper
import com.aiagent.android.model.DuplicateGroup
import com.aiagent.android.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class DuplicateDetectionEngine(
    private val context: Context,
    private val dbHelper: AppDatabaseHelper
) {

    suspend fun findDuplicates(
        items: List<MediaItem>,
        onProgress: (current: Int, total: Int, currentName: String) -> Unit = { _, _, _ -> }
    ): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        // Step 1: Quick O(1) group by byte size.
        val sizeBuckets = items
            .filter { it.sizeBytes > 0 }
            .groupBy { it.sizeBytes }
            .filter { it.value.size >= 2 }

        val candidateItems = sizeBuckets.values.flatten()
        val totalCandidates = candidateItems.size

        if (totalCandidates == 0) {
            return@withContext emptyList()
        }

        val hashMap = mutableMapOf<String, MutableList<MediaItem>>()
        var processedCount = 0

        for (item in candidateItems) {
            coroutineContext.ensureActive()
            onProgress(processedCount, totalCandidates, item.displayName)

            // Check if we have cached hash in DB
            var hash = dbHelper.getCachedHash(item.id)
            if (hash == null) {
                hash = calculateSha256(item.uriString)
                if (hash != null) {
                    dbHelper.updateMediaHash(item.id, hash)
                }
            }

            if (hash != null) {
                val list = hashMap.getOrPut(hash) { mutableListOf() }
                list.add(item)
            }

            processedCount++
            onProgress(processedCount, totalCandidates, item.displayName)
        }

        val duplicateGroups = hashMap
            .filter { it.value.size >= 2 }
            .map { (hash, groupItems) ->
                DuplicateGroup(
                    contentHash = hash,
                    fileSize = groupItems.first().sizeBytes,
                    items = groupItems
                )
            }
            .sortedByDescending { it.wastedBytes }

        duplicateGroups
    }

    private suspend fun calculateSha256(uriString: String): String? = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriString)
            val stream: InputStream? = context.contentResolver.openInputStream(uri)
            if (stream == null) return@withContext null

            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536) // 64 KB buffer

            stream.use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    coroutineContext.ensureActive()
                    digest.update(buffer, 0, bytesRead)
                }
            }

            val hashBytes = digest.digest()
            val sb = StringBuilder()
            for (b in hashBytes) {
                sb.append(String.format("%02x", b))
            }
            sb.toString()
        } catch (e: Exception) {
            null
        }
    }
}
