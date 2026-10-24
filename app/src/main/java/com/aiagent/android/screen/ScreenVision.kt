package com.aiagent.android.screen

import com.aiagent.android.ai.AgentLoop
import com.aiagent.android.ai.LlmClient
import com.aiagent.android.security.KeyStoreVault
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

data class VisualTarget(val id: String, val label: String, val x: Double, val y: Double)
data class VisualAnalysis(val summary: String, val evidence: List<String>, val targets: List<VisualTarget>)

class ScreenVision(private val vault: KeyStoreVault) {
    suspend fun inspect(session: ScreenSession, focus: String): AgentLoop.Outcome {
        if (!vault.isVisionEnabled() || vault.isOfflinePreferred()) return AgentLoop.Outcome("needs_user", "截图视觉未开启或当前为离线模式，请在设置中确认截图发送范围并开启。")
        val baseUrl = vault.resolvedVisionBaseUrl()
        val key = vault.resolvedVisionApiKey()
        val model = vault.getVisionModel()
        if (key.isBlank() || model.isBlank()) return AgentLoop.Outcome("needs_user", "请在设置中填写支持图像输入的视觉模型。自定义视觉接口需单独配置密钥。")
        try {
            val captured = ScreenCapture.capture()
            val analysis = try {
                currentCoroutineContext().ensureActive()
                if (!vault.isVisionEnabled() || vault.isOfflinePreferred()) throw IOException("视觉开关已关闭，本次不发送截图。")
                decode(LlmClient(baseUrl, key, model).describeScreenshot(captured.jpeg, focus))
            } finally { captured.jpeg.fill(0) }
            // A vision response can arrive after a page transition. Do not publish stale targets.
            val current = ScreenCapture.capture()
            try {
                if (!captured.proof.matches(current.proof)) {
                    if (captured.proof.packageName != current.proof.packageName || captured.proof.windowId != current.proof.windowId) {
                        return AgentLoop.Outcome("error", "视觉识别期间切换了应用，已丢弃旧图像目标。", session.observe())
                    }
                    return AgentLoop.Outcome("ok", "画面持续变化。摘要只描述刚才截取的一帧，不能用于点击或证明当前目标完成。",
                        session.attachVisual(captured.proof, analysis.copy(targets = emptyList()), actionable = false))
                }
                return AgentLoop.Outcome("ok", "已观察截图。视觉识别可能有误，点击前还会在本机重新核对画面。", session.attachVisual(current.proof, analysis))
            } finally { current.jpeg.fill(0) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            return AgentLoop.Outcome("error", "视觉观察未完成：${e.message?.take(160) ?: "截图或模型暂不可用"}")
        }
    }

    private fun decode(text: String): VisualAnalysis {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        require(start >= 0 && end > start) { "视觉返回格式不正确。" }
        val root = JsonParser.parseString(text.substring(start, end + 1)).asJsonObject
        val summary = root.get("summary")?.asString.orEmpty().take(800)
        require(summary.isNotBlank()) { "视觉摘要为空。" }
        val evidence = root.getAsJsonArray("evidence")?.take(8)?.mapNotNull {
            if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString.take(180).takeIf(String::isNotBlank) else null
        }.orEmpty()
        val targets = root.getAsJsonArray("targets")?.take(20)?.mapIndexedNotNull { index, item ->
            runCatching {
                val obj = item.asJsonObject
                val label = obj.get("label").asString.take(120)
                val x = obj.get("x").asDouble
                val y = obj.get("y").asDouble
                require(label.isNotBlank() && x.isFinite() && y.isFinite() && x in 0.0..1000.0 && y in 0.0..1000.0)
                VisualTarget("v${index + 1}", label, x, y)
            }.getOrNull()
        }.orEmpty()
        return VisualAnalysis(summary, evidence, targets)
    }
}
