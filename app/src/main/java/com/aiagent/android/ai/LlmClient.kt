package com.aiagent.android.ai

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class LlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    data class ToolCall(
        val id: String,
        val functionName: String,
        val argumentsJson: String
    )

    data class LlmResponse(
        val content: String?,
        val toolCalls: List<ToolCall> = emptyList(),
        val rawMessage: JsonObject? = null
    )

    suspend fun testConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext false to "未配置 API Key"
        }
        try {
            val url = "${baseUrl.trim().removeSuffix("/")}/chat/completions"
            val payload = JsonObject().apply {
                addProperty("model", model)
                val messages = JsonArray().apply {
                    val msg = JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", "ping")
                    }
                    add(msg)
                }
                add("messages", messages)
                addProperty("max_tokens", 5)
            }

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            response.use {
                if (it.isSuccessful) {
                    true to "连接成功 (HTTP ${it.code})"
                } else {
                    val errorBody = it.body?.string() ?: ""
                    false to "连接失败 (HTTP ${it.code}): $errorBody"
                }
            }
        } catch (e: Exception) {
            false to "网络异常: ${e.message}"
        }
    }

    suspend fun chatWithTools(
        messages: JsonArray,
        tools: JsonArray
    ): LlmResponse = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trim().removeSuffix("/")}/chat/completions"
        val payload = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
            add("tools", tools)
            addProperty("tool_choice", "auto")
            addProperty("temperature", 0.2)
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        val bodyString = awaitBody(request)
        val root = gson.fromJson(bodyString, JsonObject::class.java)
        val choices = root.getAsJsonArray("choices")
        if (choices == null || choices.size() == 0) {
            throw IllegalStateException("模型未返回选项")
        }

        val firstChoice = choices[0].asJsonObject
        val message = firstChoice.getAsJsonObject("message")
        val content = if (message.has("content") && !message.get("content").isJsonNull) {
            message.get("content").asString
        } else null

        val toolCalls = mutableListOf<ToolCall>()
        if (message.has("tool_calls") && message.get("tool_calls").isJsonArray) {
            val array = message.getAsJsonArray("tool_calls")
            for (elem in array) {
                val tcObj = elem.asJsonObject
                val id = tcObj.get("id").asString
                val fnObj = tcObj.getAsJsonObject("function")
                val fnName = fnObj.get("name").asString
                val args = fnObj.get("arguments").asString
                toolCalls.add(ToolCall(id, fnName, args))
            }
        }

        LlmResponse(content = content, toolCalls = toolCalls, rawMessage = message)
    }

    private suspend fun awaitBody(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use {
                        if (!it.isSuccessful) throw IOException("模型服务返回 HTTP ${it.code}")
                        it.body?.string() ?: throw IOException("模型返回空响应体")
                    }
                    if (continuation.isActive) continuation.resume(body)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
}
