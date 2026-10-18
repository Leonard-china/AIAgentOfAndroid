package com.aiagent.android.floating

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat

object FloatingAssistantManager {
    const val ACTION_SHOW = "com.aiagent.android.floating.ACTION_SHOW"
    const val ACTION_HIDE = "com.aiagent.android.floating.ACTION_HIDE"
    const val ACTION_DOCK = "com.aiagent.android.floating.ACTION_DOCK"
    const val ACTION_TOGGLE = "com.aiagent.android.floating.ACTION_TOGGLE"
    const val ACTION_STOP = "com.aiagent.android.floating.ACTION_STOP"

    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun requestOverlayPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun isServiceRunning(): Boolean {
        return FloatingAssistantService.isRunning
    }

    fun startFloatingService(context: Context): Boolean {
        if (!hasOverlayPermission(context)) {
            requestOverlayPermission(context)
            return false
        }
        val intent = Intent(context, FloatingAssistantService::class.java).apply {
            action = ACTION_SHOW
        }
        ContextCompat.startForegroundService(context, intent)
        return true
    }

    fun showFloatingService(context: Context): Boolean {
        if (!hasOverlayPermission(context)) {
            requestOverlayPermission(context)
            return false
        }
        val intent = Intent(context, FloatingAssistantService::class.java).apply {
            action = ACTION_SHOW
        }
        ContextCompat.startForegroundService(context, intent)
        return true
    }

    fun hideFloatingService(context: Context) {
        val intent = Intent(context, FloatingAssistantService::class.java).apply {
            action = ACTION_HIDE
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun dockFloatingService(context: Context) {
        val intent = Intent(context, FloatingAssistantService::class.java).apply {
            action = ACTION_DOCK
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun toggleFloatingService(context: Context) {
        val intent = Intent(context, FloatingAssistantService::class.java).apply {
            action = ACTION_TOGGLE
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopFloatingService(context: Context) {
        val intent = Intent(context, FloatingAssistantService::class.java)
        context.stopService(intent)
    }
}
