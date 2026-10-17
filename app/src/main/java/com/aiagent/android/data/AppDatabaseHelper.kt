package com.aiagent.android.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.aiagent.android.model.ActionType
import com.aiagent.android.model.AuditRecord
import com.aiagent.android.model.MediaItem
import com.aiagent.android.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

class AppDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "ai_agent_vault.db"
        const val DATABASE_VERSION = 1

        @Volatile
        private var instance: AppDatabaseHelper? = null

        fun getInstance(context: Context): AppDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: AppDatabaseHelper(context.applicationContext).also { instance = it }
            }
        }
    }

    private val auditUpdateFlow = MutableSharedFlow<Unit>(replay = 1)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE audit_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                action_type TEXT NOT NULL,
                plan_title TEXT NOT NULL,
                target_count INTEGER NOT NULL,
                released_bytes INTEGER NOT NULL,
                status TEXT NOT NULL,
                details_json TEXT NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE media_cache (
                id INTEGER PRIMARY KEY,
                uri_string TEXT NOT NULL,
                display_name TEXT NOT NULL,
                path TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                date_modified INTEGER NOT NULL,
                mime_type TEXT NOT NULL,
                media_type TEXT NOT NULL,
                is_screenshot INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                bucket_name TEXT NOT NULL,
                content_hash TEXT
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_audit_time ON audit_records(timestamp DESC)")
        db.execSQL("CREATE INDEX idx_media_hash ON media_cache(content_hash)")
        db.execSQL("CREATE INDEX idx_media_size ON media_cache(size_bytes)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS audit_records")
        db.execSQL("DROP TABLE IF EXISTS media_cache")
        onCreate(db)
    }

    suspend fun insertAuditRecord(record: AuditRecord): Long = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("timestamp", record.timestamp)
            put("action_type", record.actionType.name)
            put("plan_title", record.planTitle)
            put("target_count", record.targetCount)
            put("released_bytes", record.releasedBytes)
            put("status", record.status)
            put("details_json", record.detailsJson)
        }
        val id = db.insert("audit_records", null, values)
        auditUpdateFlow.tryEmit(Unit)
        id
    }

    suspend fun getAllAuditRecords(): List<AuditRecord> = withContext(Dispatchers.IO) {
        val list = mutableListOf<AuditRecord>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM audit_records ORDER BY timestamp DESC", null)
        cursor.use {
            val idIdx = cursor.getColumnIndexOrThrow("id")
            val timeIdx = cursor.getColumnIndexOrThrow("timestamp")
            val typeIdx = cursor.getColumnIndexOrThrow("action_type")
            val titleIdx = cursor.getColumnIndexOrThrow("plan_title")
            val countIdx = cursor.getColumnIndexOrThrow("target_count")
            val bytesIdx = cursor.getColumnIndexOrThrow("released_bytes")
            val statusIdx = cursor.getColumnIndexOrThrow("status")
            val detailsIdx = cursor.getColumnIndexOrThrow("details_json")

            while (cursor.moveToNext()) {
                list.add(
                    AuditRecord(
                        id = cursor.getLong(idIdx),
                        timestamp = cursor.getLong(timeIdx),
                        actionType = runCatching { ActionType.valueOf(cursor.getString(typeIdx)) }.getOrDefault(ActionType.TRASH),
                        planTitle = cursor.getString(titleIdx),
                        targetCount = cursor.getInt(countIdx),
                        releasedBytes = cursor.getLong(bytesIdx),
                        status = cursor.getString(statusIdx),
                        detailsJson = cursor.getString(detailsIdx)
                    )
                )
            }
        }
        list
    }

    suspend fun updateAuditStatus(id: Long, newStatus: String): Boolean = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply { put("status", newStatus) }
        val rows = db.update("audit_records", values, "id = ?", arrayOf(id.toString()))
        if (rows > 0) {
            auditUpdateFlow.tryEmit(Unit)
            true
        } else false
    }

    suspend fun saveMediaCache(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            items.forEach { item ->
                val values = ContentValues().apply {
                    put("id", item.id)
                    put("uri_string", item.uriString)
                    put("display_name", item.displayName)
                    put("path", item.path)
                    put("size_bytes", item.sizeBytes)
                    put("date_modified", item.dateModified)
                    put("mime_type", item.mimeType)
                    put("media_type", item.mediaType.name)
                    put("is_screenshot", if (item.isScreenshot) 1 else 0)
                    put("duration_ms", item.durationMs)
                    put("bucket_name", item.bucketName)
                }
                db.insertWithOnConflict("media_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun updateMediaHash(id: Long, hash: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply { put("content_hash", hash) }
        db.update("media_cache", values, "id = ?", arrayOf(id.toString()))
    }

    suspend fun getCachedHash(id: Long): String? = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT content_hash FROM media_cache WHERE id = ?", arrayOf(id.toString()))
        cursor.use {
            if (cursor.moveToFirst()) {
                val hashIdx = cursor.getColumnIndexOrThrow("content_hash")
                if (!cursor.isNull(hashIdx)) {
                    return@withContext cursor.getString(hashIdx)
                }
            }
        }
        null
    }
}
