package com.aiagent.android.floating

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object FloatingAssistantManager {

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
        val intent = Intent(context, FloatingAssistantService::class.java)
        context.startService(intent)
        return true
    }

    fun stopFloatingService(context: Context) {
        val intent = Intent(context, FloatingAssistantService::class.java)
        context.stopService(intent)
    }
}
