package com.swhurl.app

import com.sun.net.httpserver.HttpServer
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.nio.file.Files

// Platform.call against a stand-in for another app: this app's token for it, read again for each
// call, and the signed-in person the call is for when onBehalfOf names one.
@MicronautTest(startApplication = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CallTest : TestPropertyProvider {
    private data class Seen(val path: String, val authorization: String?, val user: String?)

    private val seen = mutableListOf<Seen>()
    private val tokens = Files.createTempDirectory("tokens")
    private val orders = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            seen += Seen(
                exchange.requestURI.path,
                exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestHeaders.getFirst(USER_HEADER),
            )
            val reply = "{}".toByteArray()
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        start()
    }

    @Inject
    lateinit var platform: Platform

    override fun getProperties(): Map<String, String> {
        Files.createDirectories(tokens.resolve("orders-api"))
        Files.writeString(tokens.resolve("orders-api").resolve("token"), "first\n")
        return mapOf(
            "swhurl.service.orders-api.url" to "http://127.0.0.1:${orders.address.port}",
            "swhurl.tokens.dir" to tokens.toString(),
        )
    }

    @AfterAll
    fun stop() = orders.stop(0)

    @Test
    fun `call sends this app's token and the person it is calling for`() {
        platform.call("orders-api", "/stock/42")
        Files.writeString(tokens.resolve("orders-api").resolve("token"), "second\n") // the platform replaces the file
        platform.call("orders-api", "/stock/43", onBehalfOf = Caller("user", "sam@example.com"))
        platform.call("orders-api", "/stock/44", onBehalfOf = Caller("app", "shop", "ann@example.com"))
        platform.call("orders-api", "/stock/45", onBehalfOf = Caller("anonymous", null))
        assertEquals(
            listOf(
                Seen("/stock/42", "Bearer first", null),
                Seen("/stock/43", "Bearer second", "sam@example.com"),
                Seen("/stock/44", "Bearer second", "ann@example.com"),
                Seen("/stock/45", "Bearer second", null),
            ),
            seen,
        )
    }

    @Test
    fun `personOf is the person behind a caller`() {
        assertEquals("sam@example.com", personOf(Caller("user", "sam@example.com")))
        assertEquals("sam@example.com", personOf(Caller("app", "shop", "sam@example.com")))
        assertNull(personOf(Caller("app", "shop")))
        assertNull(personOf(Caller("anonymous", null)))
        assertNull(personOf(null))
        assertTrue(USER_HEADER.equals("x-swhurl-user", ignoreCase = true))
    }
}
