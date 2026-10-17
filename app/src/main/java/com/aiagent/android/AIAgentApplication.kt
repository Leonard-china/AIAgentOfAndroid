package com.aiagent.android

import android.app.Application
import com.aiagent.android.ai.AgentOrchestrator
import com.aiagent.android.data.AppDatabaseHelper
import com.aiagent.android.security.KeyStoreVault
import com.aiagent.android.tools.DuplicateDetectionEngine
import com.aiagent.android.tools.MediaScannerEngine
import com.aiagent.android.tools.SimilarPhotoEngine
import com.aiagent.android.tools.StorageEngine
import com.aiagent.android.tools.TrashOperationExecutor

class AIAgentApplication : Application() {

    lateinit var dbHelper: AppDatabaseHelper
        private set
    lateinit var vault: KeyStoreVault
        private set
    lateinit var storageEngine: StorageEngine
        private set
    lateinit var mediaScanner: MediaScannerEngine
        private set
    lateinit var duplicateEngine: DuplicateDetectionEngine
        private set
    lateinit var similarPhotoEngine: SimilarPhotoEngine
        private set
    lateinit var trashExecutor: TrashOperationExecutor
        private set
    lateinit var orchestrator: AgentOrchestrator
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        dbHelper = AppDatabaseHelper.getInstance(this)
        vault = KeyStoreVault(this)
        storageEngine = StorageEngine(this)
        mediaScanner = MediaScannerEngine(this)
        duplicateEngine = DuplicateDetectionEngine(this, dbHelper)
        similarPhotoEngine = SimilarPhotoEngine(this)
        trashExecutor = TrashOperationExecutor(this, dbHelper)
        orchestrator = AgentOrchestrator(this, storageEngine, mediaScanner, duplicateEngine, similarPhotoEngine, vault)
    }

    companion object {
        lateinit var instance: AIAgentApplication
            private set
    }
}
