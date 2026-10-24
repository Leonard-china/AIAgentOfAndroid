package com.aiagent.android

import com.aiagent.android.ai.AgentLoop
import com.aiagent.android.ai.LlmClient
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AgentLoopTest {
    private fun call(name: String, args: String = "{}") = LlmClient.ToolCall("call-$name", name, args)
    private fun response(vararg calls: LlmClient.ToolCall) = LlmClient.LlmResponse(null, calls.toList())
    private fun screen(id: String = "s1", text: String = "搜索结果") = JsonObject().apply {
        addProperty("snapshot_id", id)
        addProperty("fingerprint", text)
        add("nodes", JsonArray().apply { add(JsonObject().apply { addProperty("text", text) }) })
    }
    private val finish = """{"status":"completed","summary":"已找到结果","snapshot_id":"s1","evidence":"搜索结果"}"""

    @Test fun `plain success reply after launch cannot complete a screen task`() = runTest {
        var turn = 0
        val result = AgentLoop(maxTurns = 3).run(JsonArray(), request = {
            if (turn++ == 0) response(call("launch_app")) else LlmClient.LlmResponse("任务已完成")
        }, execute = { AgentLoop.Outcome("ok", "仅发出启动请求", screen()) }, onAction = { _, _ -> })
        assertEquals("INCOMPLETE", result.status)
        assertEquals(3, turn)
    }

    @Test fun `completion requires current snapshot and exact visible evidence`() = runTest {
        val replies = ArrayDeque(listOf(
            response(call("inspect_current_screen")),
            response(call("finish_task", finish.replace("s1", "fabricated"))),
            response(call("finish_task", finish.replace("搜索结果", "不存在的证据"))),
            response(call("finish_task", finish))
        ))
        val result = AgentLoop().run(JsonArray(), { replies.removeFirst() },
            { AgentLoop.Outcome("ok", "已观察", screen()) }, { _, _ -> })
        assertEquals("COMPLETED", result.status)
        assertTrue(result.summary.contains("界面依据：搜索结果"))
        assertTrue(replies.isEmpty())
    }

    @Test fun `failed click cannot be marked completed`() = runTest {
        var turn = 0
        val result = AgentLoop(maxTurns = 2).run(JsonArray(), {
            if (turn++ == 0) response(call("operate_screen_click")) else response(call("finish_task", finish))
        }, { AgentLoop.Outcome("error", "控件失效", screen()) }, { _, _ -> })
        assertEquals("INCOMPLETE", result.status)
    }

    @Test fun `three unchanged operations stop the loop`() = runTest {
        var executed = 0
        val result = AgentLoop().run(JsonArray(), { response(call("inspect_current_screen")) }, {
            executed++
            AgentLoop.Outcome("ok", "页面未变化", screen())
        }, { _, _ -> })
        assertEquals("BLOCKED", result.status)
        assertEquals(3, executed)
    }

    @Test fun `needs user stops remaining actions in a model batch`() = runTest {
        var executed = 0
        val result = AgentLoop().run(JsonArray(), { response(call("launch_app"), call("operate_screen_click")) }, {
            executed++
            AgentLoop.Outcome("needs_user", "请先登录")
        }, { _, _ -> })
        assertEquals("NEEDS_USER", result.status)
        assertEquals(1, executed)
    }

    @Test fun `cancellation propagates without fallback actions`() = runTest {
        var executed = false
        try {
            AgentLoop().run(JsonArray(), { throw CancellationException("用户停止") }, {
                executed = true
                AgentLoop.Outcome("ok", "不应执行")
            }, { _, _ -> })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertFalse(executed)
        }
    }

    @Test fun `tool action budget stops before dispatching an extra action`() = runTest {
        var executed = 0
        val result = AgentLoop(maxActions = 2).run(JsonArray(), {
            response(call("launch_app"), call("inspect_current_screen"), call("operate_screen_click"))
        }, { executed++; AgentLoop.Outcome("ok", "已操作", screen()) }, { _, _ -> })
        assertEquals("INCOMPLETE", result.status)
        assertEquals(2, executed)
    }

    @Test fun `recovery requires evidence from the newly observed screen`() = runTest {
        val replies = ArrayDeque(listOf(response(call("finish_task", finish)),
            response(call("finish_task", finish.replace("s1", "fresh")))))
        val result = AgentLoop().run(JsonArray(), { replies.removeFirst() },
            { error("Recovery must not replay an old action") }, { _, _ -> }, initialScreen = screen("fresh"))
        assertEquals("COMPLETED", result.status)
        assertTrue(replies.isEmpty())
    }

    @Test fun `changing visual frame can answer but cannot prove completion`() = runTest {
        val observation = screen().apply {
            add("nodes", JsonArray())
            add("visual", JsonObject().apply {
                addProperty("fresh", false)
                add("evidence", JsonArray().apply { add("搜索结果") })
            })
        }
        val replies = ArrayDeque(listOf(response(call("inspect_screen_visual")),
            response(call("finish_task", finish)),
            response(call("finish_task", finish.replace("completed", "answered")))))
        val result = AgentLoop().run(JsonArray(), { replies.removeFirst() },
            { AgentLoop.Outcome("ok", "单帧观察", observation) }, { _, _ -> })
        assertEquals("ANSWERED", result.status)
        assertTrue(result.summary.contains("截图单帧识别"))
        assertTrue(replies.isEmpty())
    }

    @Test fun `answered cannot conceal an unverified screen mutation`() = runTest {
        val replies = ArrayDeque(listOf(response(call("operate_screen_click")),
            response(call("finish_task", finish.replace("completed", "answered")))))
        val result = AgentLoop(maxTurns = 2).run(JsonArray(), { replies.removeFirst() },
            { AgentLoop.Outcome("ok", "已点击", screen()) }, { _, _ -> })
        assertEquals("INCOMPLETE", result.status)
    }

    @Test fun `execute_action_pipeline mutates screen and enables completed with valid evidence`() = runTest {
        val pipelineResultScreen = screen("fresh").apply {
            getAsJsonArray("nodes").add(JsonObject().apply {
                addProperty("id", "n1")
                addProperty("text", "搜索结果")
                addProperty("description", "")
            })
        }
        val replies = ArrayDeque(listOf(
            response(call("execute_action_pipeline", """{"steps":[{"action":"launch_app","app_name":"哔哩哔哩"},{"action":"wait_and_click","target_text":"搜索"}]}""")),
            response(call("finish_task", finish.replace("s1", "fresh").replace("设置", "搜索结果")))
        ))
        val result = AgentLoop().run(
            messages = JsonArray(),
            request = { replies.removeFirst() },
            execute = { AgentLoop.Outcome("ok", "流水线已完成", pipelineResultScreen) },
            onAction = { _, _ -> }
        )
        assertEquals("COMPLETED", result.status)
        assertTrue(result.summary.contains("搜索结果"))
        assertTrue(replies.isEmpty())
    }
}
