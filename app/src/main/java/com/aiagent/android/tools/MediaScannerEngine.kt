package com.aiagent.android.tools

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.aiagent.android.model.MediaItem
import com.aiagent.android.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaScannerEngine(private val context: Context) {

    fun isVisualMediaPartialGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasImages = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
            val hasPartial = ContextCompat.checkSelfPermission(
                context,
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
            ) == PackageManager.PERMISSION_GRANTED
            return hasPartial && !hasImages
        }
        return false
    }

    suspend fun queryMediaItems(
        type: MediaType? = null,
        minSizeBytes: Long = 0L,
        isScreenshotOnly: Boolean = false,
        dateFromSec: Long? = null,
        dateToSec: Long? = null,
        bucketFilter: String? = null,
        limit: Int = 500,
        offset: Int = 0
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MediaItem>()
        val uri = when (type) {
            MediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
        )

        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        if (minSizeBytes > 0) {
            selectionParts.add("${MediaStore.MediaColumns.SIZE} >= ?")
            selectionArgs.add(minSizeBytes.toString())
        }

        if (dateFromSec != null) {
            selectionParts.add("${MediaStore.MediaColumns.DATE_MODIFIED} >= ?")
            selectionArgs.add(dateFromSec.toString())
        }

        if (dateToSec != null) {
            selectionParts.add("${MediaStore.MediaColumns.DATE_MODIFIED} <= ?")
            selectionArgs.add(dateToSec.toString())
        }

        if (type == MediaType.APK) {
            selectionParts.add("(${MediaStore.MediaColumns.DATA} LIKE '%.apk' OR ${MediaStore.MediaColumns.MIME_TYPE} = ?)")
            selectionArgs.add("application/vnd.android.package-archive")
        } else if (type == MediaType.DOCUMENT) {
            selectionParts.add("(${MediaStore.MediaColumns.MIME_TYPE} LIKE ? OR ${MediaStore.MediaColumns.DATA} LIKE '%.pdf' OR ${MediaStore.MediaColumns.DATA} LIKE '%.doc' OR ${MediaStore.MediaColumns.DATA} LIKE '%.docx' OR ${MediaStore.MediaColumns.DATA} LIKE '%.txt')")
            selectionArgs.add("application/%")
        }

        val selection = if (selectionParts.isNotEmpty()) selectionParts.joinToString(" AND ") else null
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        try {
            val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val queryArgs = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                    if (selection != null) {
                        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs.toTypedArray())
                    }
                    putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                    putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                }
                context.contentResolver.query(uri, projection, queryArgs, null)
            } else {
                context.contentResolver.query(
                    uri,
                    projection,
                    selection,
                    selectionArgs.toTypedArray(),
                    "$sortOrder LIMIT $limit OFFSET $offset"
                )
            }

            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dataCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val sizeCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val bucketCol = it.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)

                while (it.moveToNext()) {
                    val id = it.getLong(idCol)
                    val name = it.getString(nameCol) ?: "unknown"
                    val path = it.getString(dataCol) ?: ""
                    val size = it.getLong(sizeCol)
                    val date = it.getLong(dateCol)
                    val mime = it.getString(mimeCol) ?: "application/octet-stream"
                    val bucket = if (bucketCol >= 0 && !it.isNull(bucketCol)) it.getString(bucketCol) else ""

                    val itemType = resolveMediaType(mime, path)
                    val isScreenshot = bucket.contains("screenshot", ignoreCase = true) ||
                            path.contains("screenshot", ignoreCase = true) ||
                            name.contains("screenshot", ignoreCase = true) ||
                            bucket.contains("截屏") || path.contains("截屏")

                    if (isScreenshotOnly && !isScreenshot) {
                        continue
                    }

                    val specificTableUri = when (itemType) {
                        MediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        MediaType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        else -> MediaStore.Files.getContentUri("external")
                    }
                    val contentUri = ContentUris.withAppendedId(specificTableUri, id)

                    results.add(
                        MediaItem(
                            id = id,
                            uriString = contentUri.toString(),
                            displayName = name,
                            path = path,
                            sizeBytes = size,
                            dateModified = date * 1000L, // convert to ms
                            mimeType = mime,
                            mediaType = itemType,
                            isScreenshot = isScreenshot,
                            bucketName = bucket
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        results
    }

    suspend fun queryLargeVideos(minSizeBytes: Long = 500L * 1024L * 1024L): List<MediaItem> {
        return queryMediaItems(type = MediaType.VIDEO, minSizeBytes = minSizeBytes, limit = 100)
    }

    suspend fun queryScreenshots(limit: Int = 200): List<MediaItem> {
        return queryMediaItems(type = MediaType.IMAGE, isScreenshotOnly = true, limit = limit)
    }

    suspend fun queryApks(): List<MediaItem> {
        return queryMediaItems(type = MediaType.APK, limit = 100)
    }

    suspend fun queryItemById(id: Long): MediaItem? = withContext(Dispatchers.IO) {
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
        )
        val selection = "${MediaStore.MediaColumns._ID} = ?"
        val selectionArgs = arrayOf(id.toString())

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)

                    val itemId = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "未知文件"
                    val path = cursor.getString(dataCol) ?: ""
                    val size = cursor.getLong(sizeCol)
                    val date = cursor.getLong(dateCol)
                    val mime = cursor.getString(mimeCol) ?: ""
                    val bucket = cursor.getString(bucketCol) ?: ""

                    val itemType = resolveMediaType(mime, path)
                    val contentUri = when (itemType) {
                        MediaType.IMAGE -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, itemId)
                        MediaType.VIDEO -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, itemId)
                        MediaType.AUDIO -> ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, itemId)
                        else -> ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), itemId)
                    }

                    return@withContext MediaItem(
                        id = itemId,
                        uriString = contentUri.toString(),
                        displayName = name,
                        path = path,
                        sizeBytes = size,
                        dateModified = date * 1000L,
                        mimeType = mime,
                        mediaType = itemType,
                        isScreenshot = bucket.equals("Screenshots", ignoreCase = true) || name.contains("Screenshot", ignoreCase = true),
                        bucketName = bucket
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    private fun resolveMediaType(mimeType: String, path: String): MediaType {
        val lowerMime = mimeType.lowercase()
        val lowerPath = path.lowercase()
        return when {
            lowerMime.startsWith("image/") -> MediaType.IMAGE
            lowerMime.startsWith("video/") -> MediaType.VIDEO
            lowerMime.startsWith("audio/") -> MediaType.AUDIO
            lowerPath.endsWith(".apk") || lowerMime == "application/vnd.android.package-archive" -> MediaType.APK
            lowerPath.endsWith(".pdf") || lowerPath.endsWith(".doc") || lowerPath.endsWith(".docx") ||
                    lowerPath.endsWith(".xls") || lowerPath.endsWith(".xlsx") || lowerPath.endsWith(".ppt") ||
                    lowerPath.endsWith(".pptx") || lowerPath.endsWith(".txt") -> MediaType.DOCUMENT
            else -> MediaType.OTHER
        }
    }
}
