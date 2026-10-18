package com.aiagent.android

import com.aiagent.android.ai.LlmClient
import com.google.gson.JsonArray
import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LlmClientCancellationTest {
    @Test fun `stop cancels HTTP even while response body is incomplete`() = runBlocking {
        ServerSocket(0).use { server ->
            val bodyStarted = CountDownLatch(1)
            val disconnected = CountDownLatch(1)
            val worker = thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        var length = 0
                        while (true) {
                            val line = input.readLine() ?: return@use
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        repeat(length) { input.read() }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 9999\r\n\r\n{".toByteArray())
                            flush()
                        }
                        bodyStarted.countDown()
                        if (input.read() == -1) disconnected.countDown()
                    }
                }
            }
            val request = launch(Dispatchers.IO) {
                LlmClient("http://127.0.0.1:${server.localPort}", "local-test", "test")
                    .chatWithTools(JsonArray(), JsonArray())
            }
            try {
                assertTrue("HTTP response did not start", withContext(Dispatchers.IO) { bodyStarted.await(4, TimeUnit.SECONDS) })
                withTimeout(2000) { request.cancelAndJoin() }
                assertTrue("HTTP socket remained open after cancellation", withContext(Dispatchers.IO) { disconnected.await(2, TimeUnit.SECONDS) })
            } finally {
                request.cancel()
                server.close()
                worker.join(1000)
            }
        }
    }
}
