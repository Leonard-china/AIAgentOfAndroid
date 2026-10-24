package com.aiagent.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.aiagent.android.model.ConversationSnapshot
import com.aiagent.android.model.ConversationSummary
import com.aiagent.android.security.KeyStoreVault
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** A separate database under noBackupFilesDir; the existing audit/media database is untouched. */
class ConversationStore(context: Context, private val vault: KeyStoreVault) : SQLiteOpenHelper(
    context.applicationContext,
    File(context.noBackupFilesDir, "agent_conversations.db").absolutePath,
    null,
    1
) {
    private val gson = Gson()
    private val writes = Mutex()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE conversations (
            session_id TEXT PRIMARY KEY,
            updated_at INTEGER NOT NULL,
            revision INTEGER NOT NULL,
            title TEXT NOT NULL DEFAULT '',
            task_status TEXT,
            encrypted_payload TEXT NOT NULL
        )""")
        db.execSQL("CREATE INDEX conversations_updated ON conversations(updated_at DESC)")
        db.execSQL("CREATE TABLE session_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("会话数据库需要显式迁移，禁止删除已有会话。")
    }

    suspend fun save(snapshot: ConversationSnapshot, activate: Boolean = false) = withContext(Dispatchers.IO) {
        writes.withLock {
            val encrypted = vault.sealLocalPayload(gson.toJson(snapshot))
            val db = writableDatabase
            db.beginTransaction()
            try {
                val values = ContentValues().apply {
                    put("session_id", snapshot.sessionId)
                    put("updated_at", snapshot.updatedAt)
                    put("revision", snapshot.revision)
                    put("title", snapshot.title)
                    put("task_status", snapshot.checkpoint?.status)
                    put("encrypted_payload", encrypted)
                }
                // A queued older autosave must never overwrite a durable pre-action checkpoint.
                val existingRevision = db.rawQuery("SELECT revision FROM conversations WHERE session_id = ?", arrayOf(snapshot.sessionId)).use {
                    if (it.moveToFirst()) it.getLong(0) else -1L
                }
                if (snapshot.revision >= existingRevision) {
                    db.insertWithOnConflict("conversations", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                        .also { check(it != -1L) { "会话写入失败" } }
                }
                if (activate) db.insertWithOnConflict("session_meta", null, ContentValues().apply {
                    put("key", "active_session"); put("value", snapshot.sessionId)
                }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "当前会话保存失败" } }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    suspend fun load(sessionId: String): ConversationSnapshot? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT encrypted_payload FROM conversations WHERE session_id = ?", arrayOf(sessionId)).use {
            if (!it.moveToFirst()) null else decode(it.getString(0))
        }
    }

    suspend fun activate(sessionId: String) = withContext(Dispatchers.IO) {
        writes.withLock {
            writableDatabase.insertWithOnConflict("session_meta", null, ContentValues().apply {
                put("key", "active_session"); put("value", sessionId)
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "当前会话保存失败" } }
        }
    }

    suspend fun latest(): ConversationSnapshot? = withContext(Dispatchers.IO) {
        val active = readableDatabase.rawQuery("SELECT c.encrypted_payload FROM conversations c JOIN session_meta m ON c.session_id = m.value WHERE m.key = 'active_session'", null).use {
            if (it.moveToFirst()) decode(it.getString(0)) else null
        }
        active ?: readableDatabase.rawQuery("SELECT encrypted_payload FROM conversations ORDER BY updated_at DESC LIMIT 1", null).use {
            if (!it.moveToFirst()) null else decode(it.getString(0))
        }
    }

    suspend fun listSessions(): List<ConversationSummary> = withContext(Dispatchers.IO) {
        val result = mutableListOf<ConversationSummary>()
        readableDatabase.rawQuery("SELECT session_id, title, updated_at, task_status FROM conversations ORDER BY updated_at DESC", null).use {
            while (it.moveToNext()) {
                val title = it.getString(1).takeIf { t -> !t.isNullOrBlank() } ?: "新对话"
                result.add(ConversationSummary(it.getString(0), title, it.getLong(2), it.getString(3)))
            }
        }
        result
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        writes.withLock {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.delete("conversations", "session_id = ?", arrayOf(sessionId))
                db.delete("session_meta", "key = 'active_session' AND value = ?", arrayOf(sessionId))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun decode(encrypted: String): ConversationSnapshot {
        val snapshot = gson.fromJson(vault.openLocalPayload(encrypted), ConversationSnapshot::class.java)
            ?: error("会话内容无法读取，已保留原始文件。")
        require(snapshot.sessionId.isNotBlank() && snapshot.revision >= 0) { "会话记录损坏，已保留原始文件。" }
        return snapshot
    }
}
