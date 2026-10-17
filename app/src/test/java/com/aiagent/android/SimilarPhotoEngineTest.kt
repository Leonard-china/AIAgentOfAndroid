package com.aiagent.android

import com.aiagent.android.model.MediaItem
import com.aiagent.android.model.MediaType
import com.aiagent.android.tools.SimilarPhotoEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarPhotoEngineTest {

    private val engine = SimilarPhotoEngine()

    @Test
    fun testHammingDistance() {
        val h1 = 0b10101010L
        val h2 = 0b10101011L
        assertEquals(1, engine.hammingDistance(h1, h2))

        val h3 = 0b00000000L
        val h4 = 0b11111111L
        assertEquals(8, engine.hammingDistance(h3, h4))
    }

    @Test
    fun testSimilarCandidatesGroupingByTimeAndName() = runBlocking {
        val now = 1700000000000L
        val items = listOf(
            MediaItem(
                id = 1L,
                uriString = "content://media/external/images/media/1",
                displayName = "IMG_20261017_001.jpg",
                path = "/storage/emulated/0/DCIM/Camera/IMG_20261017_001.jpg",
                sizeBytes = 2_000_000L,
                dateModified = now,
                mimeType = "image/jpeg",
                mediaType = MediaType.IMAGE,
                bucketName = "Camera"
            ),
            MediaItem(
                id = 2L,
                uriString = "content://media/external/images/media/2",
                displayName = "IMG_20261017_002.jpg",
                path = "/storage/emulated/0/DCIM/Camera/IMG_20261017_002.jpg",
                sizeBytes = 2_010_000L,
                dateModified = now + 2000L, // 2 seconds later
                mimeType = "image/jpeg",
                mediaType = MediaType.IMAGE,
                bucketName = "Camera"
            ),
            MediaItem(
                id = 3L,
                uriString = "content://media/external/images/media/3",
                displayName = "OTHER_999999.jpg",
                path = "/storage/emulated/0/DCIM/Camera/OTHER_999999.jpg",
                sizeBytes = 5_000_000L,
                dateModified = now + 86400000L, // 1 day later
                mimeType = "image/jpeg",
                mediaType = MediaType.IMAGE,
                bucketName = "Camera"
            )
        )

        val groups = engine.findSimilarCandidates(items, timeThresholdSeconds = 5L)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].items.size)
        assertTrue(groups[0].items.any { it.id == 1L })
        assertTrue(groups[0].items.any { it.id == 2L })
    }
}
