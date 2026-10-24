package com.aiagent.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.aiagent.android.AIAgentApplication
import com.aiagent.android.ui.MainActivity

/** Keeps a user-started run visible. A killed service never automatically replays its task. */
class AgentTaskService : Service() {
    companion object {
        private const val CHANNEL = "agent_active_task"
        private const val NOTIFICATION = 4202
        private const val STOP = "com.aiagent.android.task.STOP"
        private const val UPDATE = "com.aiagent.android.task.UPDATE"
        @Volatile private var running = false
        private var live: AgentTaskService? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AgentTaskService::class.java))
        }
        fun update(context: Context, step: String) {
            if (running) context.startService(Intent(context, AgentTaskService::class.java).setAction(UPDATE).putExtra("step", step.take(80)))
        }
        fun stop(context: Context) { live?.expectedStop = true; context.stopService(Intent(context, AgentTaskService::class.java)) }
    }

    private var expectedStop = false

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "正在执行的手机任务", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION, notification("正在准备任务"))
        running = true
        live = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            expectedStop = true
            AIAgentApplication.instance.conversation.stop()
            stopSelf()
        } else if (!AIAgentApplication.instance.conversation.isProcessing) {
            expectedStop = true
            stopSelf()
        } else if (intent?.action == UPDATE) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(intent.getStringExtra("step") ?: "正在执行"))
        }
        return START_NOT_STICKY
    }

    private fun notification(step: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, AgentTaskService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Pocket Agent 正在执行任务")
            .setContentText(step)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "停止任务", stop).build())
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        if (live === this) { live = null; running = false }
        if (!expectedStop && AIAgentApplication.instance.conversation.isProcessing) AIAgentApplication.instance.conversation.stop()
        super.onDestroy()
    }
}
