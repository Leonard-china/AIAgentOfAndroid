package com.aiagent.android.tools

import com.aiagent.android.model.MediaItem
import com.aiagent.android.model.MediaType
import com.aiagent.android.model.SimilarPhotoGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs

class SimilarPhotoEngine {

    suspend fun findSimilarCandidates(
        items: List<MediaItem>,
        timeThresholdSeconds: Long = 10L
    ): List<SimilarPhotoGroup> = withContext(Dispatchers.Default) {
        val images = items.filter { it.mediaType == MediaType.IMAGE }
            .sortedBy { it.dateModified }

        if (images.size < 2) return@withContext emptyList()

        val clusters = mutableListOf<MutableList<MediaItem>>()
        var currentCluster = mutableListOf<MediaItem>()

        for (item in images) {
            if (currentCluster.isEmpty()) {
                currentCluster.add(item)
            } else {
                val lastItem = currentCluster.last()
                val timeDiffSeconds = abs(item.dateModified - lastItem.dateModified) / 1000L
                val sameBucket = item.bucketName == lastItem.bucketName
                val similarName = hasSequentialName(item.displayName, lastItem.displayName)

                // If captured within time threshold in same album or sequential shots
                if ((timeDiffSeconds <= timeThresholdSeconds && sameBucket) || similarName) {
                    currentCluster.add(item)
                } else {
                    if (currentCluster.size >= 2) {
                        clusters.add(currentCluster)
                    }
                    currentCluster = mutableListOf(item)
                }
            }
        }

        if (currentCluster.size >= 2) {
            clusters.add(currentCluster)
        }

        clusters.map { cluster ->
            SimilarPhotoGroup(
                groupId = UUID.randomUUID().toString(),
                similarityScore = 0.85f,
                items = cluster
            )
        }
    }

    private fun hasSequentialName(name1: String, name2: String): Boolean {
        val base1 = name1.substringBeforeLast(".")
        val base2 = name2.substringBeforeLast(".")
        if (base1.length == base2.length && base1.length > 4) {
            val prefix1 = base1.substring(0, base1.length - 2)
            val prefix2 = base2.substring(0, base2.length - 2)
            return prefix1 == prefix2
        }
        return false
    }
}
