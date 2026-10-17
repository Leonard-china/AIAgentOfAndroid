package com.aiagent.android.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.aiagent.android.model.MediaItem
import com.aiagent.android.model.MediaType
import com.aiagent.android.model.SimilarPhotoGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs

class SimilarPhotoEngine(private val context: Context? = null) {

    suspend fun findSimilarCandidates(
        items: List<MediaItem>,
        timeThresholdSeconds: Long = 10L
    ): List<SimilarPhotoGroup> = withContext(Dispatchers.Default) {
        val images = items.filter { it.mediaType == MediaType.IMAGE }
            .sortedBy { it.dateModified }

        if (images.size < 2) return@withContext emptyList()

        // If context is available, compute visual perceptual hash (dHash) for candidates
        val visualHashes = mutableMapOf<Long, Long>()
        if (context != null) {
            for (item in images.take(150)) { // bound to 150 items to keep UI snappy
                computeDHash(context, Uri.parse(item.uriString))?.let { hash ->
                    visualHashes[item.id] = hash
                }
            }
        }

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

                // Visual hash similarity check
                val hash1 = visualHashes[lastItem.id]
                val hash2 = visualHashes[item.id]
                val isVisuallySimilar = if (hash1 != null && hash2 != null) {
                    hammingDistance(hash1, hash2) <= 8
                } else false

                // If visually similar OR captured within time threshold in same album or sequential shots
                if (isVisuallySimilar || (timeDiffSeconds <= timeThresholdSeconds && sameBucket) || similarName) {
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
            val first = cluster.firstOrNull()?.id
            val second = cluster.getOrNull(1)?.id
            val h1 = first?.let { visualHashes[it] }
            val h2 = second?.let { visualHashes[it] }
            val score = if (h1 != null && h2 != null) {
                val dist = hammingDistance(h1, h2)
                (1.0f - (dist.toFloat() / 64f)).coerceIn(0.6f, 0.99f)
            } else {
                0.85f
            }

            SimilarPhotoGroup(
                groupId = UUID.randomUUID().toString(),
                similarityScore = score,
                items = cluster
            )
        }
    }

    fun computeDHash(context: Context, uri: Uri): Long? {
        return try {
            val contentResolver = context.contentResolver
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            val width = options.outWidth
            val height = options.outHeight
            if (width <= 0 || height <= 0) return null

            var inSampleSize = 1
            while (width / (inSampleSize * 2) >= 9 && height / (inSampleSize * 2) >= 8) {
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            val decodedBitmap = contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            val scaledBitmap = Bitmap.createScaledBitmap(decodedBitmap, 9, 8, true)
            if (decodedBitmap != scaledBitmap) {
                decodedBitmap.recycle()
            }

            var hash = 0L
            var bitIndex = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val p1 = scaledBitmap.getPixel(x, y)
                    val p2 = scaledBitmap.getPixel(x + 1, y)
                    val gray1 = (Color.red(p1) * 299 + Color.green(p1) * 587 + Color.blue(p1) * 114) / 1000
                    val gray2 = (Color.red(p2) * 299 + Color.green(p2) * 587 + Color.blue(p2) * 114) / 1000
                    if (gray1 > gray2) {
                        hash = hash or (1L shl bitIndex)
                    }
                    bitIndex++
                }
            }
            scaledBitmap.recycle()
            hash
        } catch (e: Exception) {
            null
        }
    }

    fun hammingDistance(hash1: Long, hash2: Long): Int {
        return java.lang.Long.bitCount(hash1 xor hash2)
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
