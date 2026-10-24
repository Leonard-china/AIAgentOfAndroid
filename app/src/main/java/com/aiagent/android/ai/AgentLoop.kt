package com.aiagent.android.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Platform-independent control loop; device actions and model transport are injected. */
class AgentLoop(private val maxTurns: Int = 24, private val maxActions: Int = 48) {
    data class Outcome(val status: String, val message: String, val observation: JsonObject? = null) {
        fun toJson() = JsonObject().apply {
            addProperty("status", status)
            addProperty("message", message)
            observation?.let { add("screen", it) }
        }
    }
    data class Result(val status: String, val summary: String)

    suspend fun run(
        messages: JsonArray,
        request: suspend (JsonArray) -> LlmClient.LlmResponse,
        execute: suspend (LlmClient.ToolCall) -> Outcome,
        onAction: suspend (String, Outcome?) -> Unit,
        initialScreen: JsonObject? = null
    ): Result {
        var actions = 0
        var latestScreen: JsonObject? = initialScreen
        var lastOutcome: Outcome? = initialScreen?.let { Outcome("ok", "恢复时重新观察的界面", it) }
        var screenTask = initialScreen != null
        var hasMutatedScreen = false
        var repeatedCall: String? = null
        var repeatedScreen: String? = null
        var repeats = 0
        for (turn in 1..maxTurns) {
            currentCoroutineContext().ensureActive()
            val response = request(messages)
            if (response.toolCalls.isEmpty()) {
                if (!screenTask) return Result("ANSWERED", response.content ?: "模型没有返回可用答复。")
                messages.add(JsonObject().apply {
                    addProperty("role", "assistant")
                    addProperty("content", response.content.orEmpty())
                })
                messages.add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", "屏幕任务尚未核验。请继续操作；完成后调用 finish_task，引用最新 snapshot_id 与屏幕原文证据。遇到阻塞则以 blocked 或 needs_user 结束，不能用普通回复宣称成功。")
                })
                continue
            }
            // Reconstruct only standard fields: providers may include incompatible reasoning fields.
            messages.add(JsonObject().apply {
                addProperty("role", "assistant")
                response.content?.let { addProperty("content", it) }
                add("tool_calls", JsonArray().apply {
                    response.toolCalls.forEach { call -> add(JsonObject().apply {
                        addProperty("id", call.id)
                        addProperty("type", "function")
                        add("function", JsonObject().apply {
                            addProperty("name", call.functionName)
                            addProperty("arguments", call.argumentsJson)
                        })
                    }) }
                })
            })
            for (call in response.toolCalls) {
                currentCoroutineContext().ensureActive()
                if (++actions > maxActions) return Result("INCOMPLETE", "已达到本次任务的 $maxActions 步上限，目标尚未核验。请缩小任务范围后继续。")
                if (call.functionName == "finish_task") {
                    val args = runCatching { com.google.gson.JsonParser.parseString(call.argumentsJson).asJsonObject }.getOrNull()
                    val status = args?.get("status")?.asString
                    val summary = args?.get("summary")?.asString.orEmpty()
                    val snapshotId = args?.get("snapshot_id")?.asString
                    val evidence = args?.get("evidence")?.asString.orEmpty()
                    val screenId = latestScreen?.get("snapshot_id")?.asString
                    val nativeEvidence = evidence.length >= 2 && latestScreen?.getAsJsonArray("nodes")?.any {
                        val node = it.asJsonObject
                        listOf("text", "description").any { key ->
                            node.get(key)?.asString?.contains(evidence, ignoreCase = true) == true
                        }
                    } == true
                    val vision = latestScreen?.getAsJsonObject("visual")
                    val visualEvidence = evidence.length >= 2 && vision?.getAsJsonArray("evidence")?.any {
                        it.asString.contains(evidence, ignoreCase = true)
                    } == true
                    val freshVisualEvidence = visualEvidence && vision?.get("fresh")?.asBoolean == true
                    val validEvidence = nativeEvidence || freshVisualEvidence
                    if (status == "answered" && summary.isNotBlank() && snapshotId != null && snapshotId == screenId && lastOutcome?.status == "ok" && !hasMutatedScreen && (nativeEvidence || visualEvidence)) {
                        return Result("ANSWERED", "$summary\n\n${if (nativeEvidence) "界面依据" else "截图单帧识别（可能有误，不代表当前画面）"}：$evidence")
                    }
                    if (summary.isNotBlank() && status in listOf("blocked", "needs_user")) {
                        return Result(if (status == "blocked") "BLOCKED" else "NEEDS_USER", summary)
                    }
                    if (status == "completed" && summary.isNotBlank() && screenTask &&
                        snapshotId != null && snapshotId == screenId && validEvidence && lastOutcome?.status == "ok") {
                        return Result("COMPLETED", "$summary\n\n${if (nativeEvidence) "界面依据" else "视觉模型识别依据（可能有误）"}：$evidence")
                    }
                    appendTool(messages, call.id, Outcome("error", "完成核验未通过：必须引用最新 snapshot_id 和实际节点中的原文 evidence，且最近操作须成功。请重新观察或报告阻塞。"))
                    continue
                }
                screenTask = screenTask || call.functionName in screenTools
                if (call.functionName in screenTools && call.functionName !in setOf("inspect_current_screen", "inspect_screen_visual")) hasMutatedScreen = true
                onAction(call.functionName, null)
                val outcome = execute(call)
                lastOutcome = outcome
                outcome.observation?.let { latestScreen = it }
                onAction(call.functionName, outcome)
                appendTool(messages, call.id, outcome)
                if (outcome.status == "needs_user") return Result("NEEDS_USER", outcome.message)
                val signatureArgs = runCatching { com.google.gson.JsonParser.parseString(call.argumentsJson).asJsonObject.deepCopy() }.getOrNull()
                signatureArgs?.remove("snapshot_id")
                val signature = call.functionName + (signatureArgs?.toString() ?: call.argumentsJson)
                val screen = outcome.observation?.get("fingerprint")?.asString
                if (signature == repeatedCall && screen == repeatedScreen) repeats++ else repeats = 1
                repeatedCall = signature
                repeatedScreen = screen
                if (repeats >= 3) return Result("BLOCKED", "同一操作连续三次没有取得进展，已停止重试。最后结果：${outcome.message}")
            }
        }
        return Result("INCOMPLETE", "已达到本次任务的 $maxTurns 轮上限，目标尚未核验。已停止操作，可根据执行记录继续。")
    }

    private fun appendTool(messages: JsonArray, id: String, outcome: Outcome) {
        messages.add(JsonObject().apply {
            addProperty("role", "tool")
            addProperty("tool_call_id", id)
            addProperty("content", outcome.toJson().toString())
        })
    }

    companion object {
        val screenTools = setOf("launch_app", "inspect_current_screen", "inspect_screen_visual", "operate_screen_click",
            "operate_screen_input", "operate_screen_swipe", "operate_screen_global_action",
            "open_accessibility_settings", "open_storage_settings", "execute_action_pipeline")
    }
}
