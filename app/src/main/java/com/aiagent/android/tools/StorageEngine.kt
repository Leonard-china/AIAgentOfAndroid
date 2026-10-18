package com.aiagent.android.tools

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import com.aiagent.android.model.StorageStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StorageEngine(private val context: Context) {

    suspend fun getStorageOverview(): StorageStat = withContext(Dispatchers.IO) {
        val path = Environment.getDataDirectory().path
        val statFs = StatFs(path)
        val blockSize = statFs.blockSizeLong
        val totalBlocks = statFs.blockCountLong
        val availableBlocks = statFs.availableBlocksLong

        val totalBytes = totalBlocks * blockSize
        val availableBytes = availableBlocks * blockSize
        val usedBytes = totalBytes - availableBytes

        val imagesBytes = queryCategoryBytes(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        val videosBytes = queryCategoryBytes(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        val audioBytes = queryCategoryBytes(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)

        val documentsBytes = queryFilesCategoryBytes(
            // 注意：APK 的 MIME 同样属于 application/*，必须显式排除，否则 APK 会被
            // “文档与压缩包”和“安装包”两个分类重复计入，导致“系统与其他数据”被低估。
            selection = "(${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR " +
                    "${MediaStore.Files.FileColumns.DATA} LIKE '%.pdf' OR " +
                    "${MediaStore.Files.FileColumns.DATA} LIKE '%.doc' OR " +
                    "${MediaStore.Files.FileColumns.DATA} LIKE '%.docx' OR " +
                    "${MediaStore.Files.FileColumns.DATA} LIKE '%.xls' OR " +
                    "${MediaStore.Files.FileColumns.DATA} LIKE '%.xlsx' OR " +
                    "${MediaStore.Files.FileColumns.DATA} LIKE '%.ppt' OR " +
                    "${MediaStore.Files.FileColumns.DATA} LIKE '%.pptx' OR " +
                    "${MediaStore.Files.FileColumns.DATA} LIKE '%.txt' OR " +
                    "${MediaStore.Files.FileColumns.DATA} LIKE '%.zip' OR " +
                    "${MediaStore.Files.FileColumns.DATA} LIKE '%.rar') " +
                    "AND COALESCE(${MediaStore.Files.FileColumns.MIME_TYPE}, '') <> ? " +
                    "AND COALESCE(${MediaStore.Files.FileColumns.DATA}, '') NOT LIKE '%.apk'",
            selectionArgs = arrayOf(
                "application/%",
                "application/vnd.android.package-archive"
            )
        )

        val apksBytes = queryFilesCategoryBytes(
            selection = "${MediaStore.Files.FileColumns.DATA} LIKE '%.apk' OR ${MediaStore.Files.FileColumns.MIME_TYPE} = ?",
            selectionArgs = arrayOf("application/vnd.android.package-archive")
        )

        val categorizedSum = imagesBytes + videosBytes + audioBytes + documentsBytes + apksBytes
        val othersBytes = if (usedBytes > categorizedSum) usedBytes - categorizedSum else 0L

        StorageStat(
            totalBytes = totalBytes,
            availableBytes = availableBytes,
            usedBytes = usedBytes,
            imagesBytes = imagesBytes,
            videosBytes = videosBytes,
            audioBytes = audioBytes,
            documentsBytes = documentsBytes,
            apksBytes = apksBytes,
            othersBytes = othersBytes
        )
    }

    private fun queryCategoryBytes(uri: Uri): Long {
        var total = 0L
        val projection = arrayOf("SUM(${MediaStore.MediaColumns.SIZE})")
        try {
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst() && !it.isNull(0)) {
                    total = it.getLong(0)
                }
            }
        } catch (e: Exception) {
            // In case SUM() is not supported on certain content providers, fallback to row iteration
            total = fallbackSumBytes(context.contentResolver, uri, null, null)
        }
        return total
    }

    private fun queryFilesCategoryBytes(selection: String, selectionArgs: Array<String>): Long {
        val uri = MediaStore.Files.getContentUri("external")
        return fallbackSumBytes(context.contentResolver, uri, selection, selectionArgs)
    }

    private fun fallbackSumBytes(
        resolver: ContentResolver,
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): Long {
        var total = 0L
        val projection = arrayOf(MediaStore.MediaColumns.SIZE)
        try {
            val cursor = resolver.query(uri, projection, selection, selectionArgs, null)
            cursor?.use {
                val sizeIdx = it.getColumnIndex(MediaStore.MediaColumns.SIZE)
                if (sizeIdx >= 0) {
                    while (it.moveToNext()) {
                        if (!it.isNull(sizeIdx)) {
                            total += it.getLong(sizeIdx)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignored, returns whatever was counted
        }
        return total
    }
}
