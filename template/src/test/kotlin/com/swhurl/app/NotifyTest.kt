package com.swhurl.app

import com.sun.net.httpserver.HttpServer
import io.micronaut.json.JsonMapper
import io.micronaut.json.tree.JsonNode
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.nio.file.Files

// Platform.notify against a stand-in for the platform's notify service: what is sent, with which
// token, and that a refusal or an unreachable service is an answer, never an exception.
@MicronautTest(startApplication = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotifyTest : TestPropertyProvider {
    private data class Seen(val method: String, val authorization: String?, val body: String)

    private val seen = mutableListOf<Seen>()
    private var status = 202
    private val tokens = Files.createTempDirectory("tokens")
    private val service = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/notify") { exchange ->
            seen += Seen(
                exchange.requestMethod,
                exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestBody.readAllBytes().decodeToString(),
            )
            val reply = """{"error":"try again later"}""".toByteArray()
            exchange.sendResponseHeaders(status, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        start()
    }

    @Inject
    lateinit var platform: Platform

    @Inject
    lateinit var json: JsonMapper

    override fun getProperties(): Map<String, String> {
        Files.createDirectories(tokens.resolve("notify"))
        Files.writeString(tokens.resolve("notify").resolve("token"), "for-notify\n")
        return mapOf(
            "swhurl.notify.url" to "http://127.0.0.1:${service.address.port}",
            "swhurl.tokens.dir" to tokens.toString(),
        )
    }

    @AfterAll
    fun stop() = service.stop(0)

    @Test
    fun `notify posts the message with this app's token for the notify service`() {
        status = 202
        seen.clear()
        assertTrue(platform.notify("Order backlog", message = "412 waiting", priority = "high", key = "backlog-1"))
        assertEquals(1, seen.size)
        assertEquals("POST", seen[0].method)
        assertEquals("Bearer for-notify", seen[0].authorization)
        val body = json.readValue(seen[0].body, JsonNode::class.java)!!
        assertEquals("Order backlog", body.get("title")!!.stringValue)
        assertEquals("412 waiting", body.get("message")!!.stringValue)
        assertEquals("high", body.get("priority")!!.stringValue)
        assertEquals("backlog-1", body.get("key")!!.stringValue)
        assertNull(body.get("click"), "what was not given is not sent")
    }

    @Test
    fun `a refusal is an answer, not an exception`() {
        status = 429
        assertFalse(platform.notify("Order backlog"))
    }
}

// Off the platform there is no notify service: nothing is sent and nothing fails.
@MicronautTest(startApplication = false)
class NotifyOffPlatformTest {
    @Inject
    lateinit var platform: Platform

    @Test
    fun `without the platform's address nothing is sent`() {
        assertFalse(platform.notify("Order backlog"))
    }
}
