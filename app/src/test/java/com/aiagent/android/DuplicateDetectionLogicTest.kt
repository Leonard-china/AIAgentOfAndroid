package com.aiagent.android

import com.aiagent.android.model.MediaItem
import com.aiagent.android.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicateDetectionLogicTest {

    @Test
    fun testSizeGroupingExcludesUniqueSizes() {
        val items = listOf(
            createMockItem(1, "file1.jpg", 1000L),
            createMockItem(2, "file2.jpg", 2000L),
            createMockItem(3, "file3.jpg", 3000L),
            createMockItem(4, "file4_copy.jpg", 1000L),
            createMockItem(5, "file5_copy.jpg", 1000L)
        )

        val sizeBuckets = items
            .filter { it.sizeBytes > 0 }
            .groupBy { it.sizeBytes }
            .filter { it.value.size >= 2 }

        // Only the 1000L bucket has >= 2 items
        assertEquals(1, sizeBuckets.size)
        assertEquals(3, sizeBuckets[1000L]?.size)
        // 2000L and 3000L are immediately eliminated from hash computation
    }

    private fun createMockItem(id: Long, name: String, size: Long): MediaItem {
        return MediaItem(
            id = id,
            uriString = "content://media/external/images/media/$id",
            displayName = name,
            path = "/storage/emulated/0/DCIM/$name",
            sizeBytes = size,
            dateModified = 1700000000000L,
            mimeType = "image/jpeg",
            mediaType = MediaType.IMAGE
        )
    }
}
