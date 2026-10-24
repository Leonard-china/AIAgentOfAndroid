package com.aiagent.android.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.coroutines.resumeWithException
import kotlin.math.min

data class ScreenProof(
    val packageName: String,
    val windowId: Int,
    val bounds: Rect,
    val rotation: Int,
    val fingerprint: String,
    val capturedAt: Long
) {
    fun matches(other: ScreenProof): Boolean {
        if (packageName != other.packageName || windowId != other.windowId || bounds != other.bounds || rotation != other.rotation) return false
        if (fingerprint == other.fingerprint) return true
        // Perceptual dHash tolerance for micro-animations and cursors (<= 12 bits out of 256)
        if (fingerprint.length == 64 && other.fingerprint.length == 64) {
            var diff = 0
            for (i in 0 until 64) {
                val v1 = fingerprint[i].digitToIntOrNull(16) ?: 0
                val v2 = other.fingerprint[i].digitToIntOrNull(16) ?: 0
                diff += Integer.bitCount(v1 xor v2)
                if (diff > 12) return false
            }
            return true
        }
        return false
    }
}

data class CapturedScreen(val jpeg: ByteArray, val proof: ScreenProof)

/** Memory-only, opt-in caller. Never writes screenshots or bypasses FLAG_SECURE. */
object ScreenCapture {
    private val mutex = Mutex()
    private var lastCapture = 0L
    private val executor = Executors.newSingleThreadExecutor { command -> Thread(command, "agent-screenshot").apply { isDaemon = true } }

    suspend fun capture(): CapturedScreen = mutex.withLock {
        val service = ScreenAssistantAccessibilityService.instance ?: throw IOException("无障碍服务未连接。")
        currentCoroutineContext().ensureActive()
        if (ScreenAssistantAccessibilityService.isEmergencyStopped()) throw CancellationException("任务已停止")
        if (ScreenAssistantAccessibilityService.isDestructiveSystemDialogActive()) throw IOException("系统确认页面需用户手动处理。")
        if (service.windows.any { it.isActive && it.type !in setOf(AccessibilityWindowInfo.TYPE_APPLICATION, AccessibilityWindowInfo.TYPE_INPUT_METHOD) }) {
            throw IOException("当前为系统层窗口，请先关闭系统面板后再使用视觉观察。")
        }
        var visited = 0
        fun hasPassword(node: AccessibilityNodeInfo?, depth: Int = 0): Boolean {
            if (node == null) return false
            if (depth > 30) return true
            // A traversal limit must not silently turn a failed privacy check into permission.
            if (++visited > 2000) return true
            if (node.isVisibleToUser && node.isPassword) return true
            for (i in 0 until node.childCount) if (hasPassword(node.getChild(i), depth + 1)) return true
            return false
        }
        if (service.windows.any { hasPassword(it.root) }) throw IOException("检测到密码输入或无法完成隐私检查，本次不截屏。")
        val root = ScreenSession.currentRoot() ?: throw IOException("当前没有可确认的应用窗口。")
        val packageName = root.packageName?.toString().orEmpty()
        val windowId = root.windowId
        val appBounds = Rect().also { root.getBoundsInScreen(it) }
        val rotation = service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
        val wait = 1000L - (SystemClock.elapsedRealtime() - lastCapture)
        if (wait > 0) delay(wait)
        val capturedAt = SystemClock.elapsedRealtime()
        val bitmap = ScreenActionHud.withoutOverlay {
            withTimeoutOrNull(5000) {
                suspendCancellableCoroutine<Bitmap> { continuation ->
                    service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val buffer = result.hardwareBuffer
                            var wrapped: Bitmap? = null
                            try {
                                if (!continuation.isActive) return
                                wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false) ?: throw IOException("系统截图无法转换。")
                                continuation.resume(copy) { _, orphan, _ -> orphan.recycle() }
                            } catch (e: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(e)
                            } finally { wrapped?.recycle(); buffer.close() }
                        }
                        override fun onFailure(errorCode: Int) {
                            if (continuation.isActive) continuation.resumeWithException(IOException("系统拒绝截图（代码 $errorCode）；受保护内容不会被绕过。"))
                        }
                    })
                }
            } ?: throw IOException("系统截图超时。")
        }
        lastCapture = SystemClock.elapsedRealtime()
        try {
            currentCoroutineContext().ensureActive()
            val current = ScreenSession.currentRoot()
            if (current == null || current.windowId != windowId || current.packageName?.toString() != packageName) throw IOException("截图期间应用已切换，请重新观察。")
            visited = 0
            if (service.windows.any { hasPassword(it.root) }) throw IOException("截图时出现密码输入，本次不发送图像。")
            if (!appBounds.intersect(0, 0, bitmap.width, bitmap.height)) throw IOException("应用窗口边界不可用。")
            // Do not include an overlapping docked input method in the uploaded application image.
            service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }.forEach { keyboard ->
                val bounds = Rect().also { keyboard.getBoundsInScreen(it) }
                if (bounds.left <= appBounds.left && bounds.right >= appBounds.right && bounds.top > appBounds.top && bounds.top < appBounds.bottom) appBounds.bottom = bounds.top
            }
            withContext(Dispatchers.Default) { encode(bitmap, ScreenProof(packageName, windowId, Rect(appBounds), rotation, "", capturedAt)) }
        } finally { bitmap.recycle() }
    }

    private fun encode(bitmap: Bitmap, metadata: ScreenProof): CapturedScreen {
        val bounds = metadata.bounds
        require(bounds.width() > 0 && bounds.height() > 0)
        val cropped = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
        try {
            val fingerprintImage = Bitmap.createScaledBitmap(cropped, 128, 128, false)
            val fingerprint = try {
                // 17x16 for 256-bit difference hash
                val dHashImage = Bitmap.createScaledBitmap(cropped, 17, 16, true)
                try {
                    val p = IntArray(17 * 16)
                    dHashImage.getPixels(p, 0, 17, 0, 0, 17, 16)
                    val gray = IntArray(17 * 16) { i ->
                        val c = p[i]
                        (c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114
                    }
                    val bits = StringBuilder(256)
                    for (row in 0 until 16) {
                        for (col in 0 until 16) {
                            bits.append(if (gray[row * 17 + col] < gray[row * 17 + col + 1]) '1' else '0')
                        }
                    }
                    bits.chunked(4).joinToString("") { it.toInt(2).toString(16) }
                } finally { if (dHashImage !== cropped) dHashImage.recycle() }
            } finally { if (fingerprintImage !== cropped) fingerprintImage.recycle() }
            val ratio = min(1f, 1280f / maxOf(cropped.width, cropped.height))
            val scaled = Bitmap.createScaledBitmap(cropped, maxOf(1, (cropped.width * ratio).toInt()), maxOf(1, (cropped.height * ratio).toInt()), true)
            try {
                val output = ByteArrayOutputStream()
                check(scaled.compress(Bitmap.CompressFormat.JPEG, 70, output))
                if (output.size() > 1_048_576) { output.reset(); check(scaled.compress(Bitmap.CompressFormat.JPEG, 40, output)) }
                if (output.size() > 1_048_576) throw IOException("截图超出本次视觉请求的大小上限。")
                return CapturedScreen(output.toByteArray(), metadata.copy(fingerprint = fingerprint))
            } finally { if (scaled !== cropped) scaled.recycle() }
        } finally { if (cropped !== bitmap) cropped.recycle() }
    }
}
