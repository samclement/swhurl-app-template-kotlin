package com.swhurl.app

import com.sun.net.httpserver.HttpServer
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

private const val ISSUER = "https://kubernetes.default.svc.cluster.local"

// Platform.call against a stand-in for another app: this app's token for it, read again for each
// call, and the signed-in person the call is for when onBehalfOf names one. And the other side of a
// call: the verification facts read from the files the platform mounts, so a change needs no restart.
@MicronautTest(startApplication = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CallTest : TestPropertyProvider {
    private data class Seen(val path: String, val authorization: String?, val user: String?)

    private val seen = mutableListOf<Seen>()
    private val tokens = Files.createTempDirectory("tokens")
    private val auth = Files.createTempDirectory("auth")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val first = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val second = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
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
        Files.writeString(auth.resolve("issuer"), "$ISSUER\n")
        Files.writeString(auth.resolve("keys"), jwks("k1", first))
        Files.writeString(auth.resolve("callers"), "reports\n")
        return mapOf(
            "swhurl.service.orders-api.url" to "http://127.0.0.1:${orders.address.port}",
            "swhurl.tokens.dir" to tokens.toString(),
            // What the platform sets as SWHURL_* environment variables: both forms of each fact, the files
            // under /var/run/swhurl and, for older images, the values themselves.
            "swhurl.app" to "orders",
            "swhurl.env" to "staging",
            "swhurl.auth.issuer.file" to auth.resolve("issuer").toString(),
            "swhurl.auth.keys.file" to auth.resolve("keys").toString(),
            "swhurl.allowed.callers.file" to auth.resolve("callers").toString(),
            "swhurl.auth.issuer" to ISSUER,
            "swhurl.auth.keys" to jwks("k1", first),
            "swhurl.allowed.callers" to "billing",
        )
    }

    private fun jwks(kid: String, keys: KeyPair): String {
        val public = keys.public as RSAPublicKey
        val n = encoder.encodeToString(public.modulus.toByteArray())
        val e = encoder.encodeToString(public.publicExponent.toByteArray())
        return """{"keys":[{"kty":"RSA","alg":"RS256","kid":"$kid","n":"$n","e":"$e"}]}"""
    }

    private fun bearer(kid: String, keys: KeyPair): String {
        val head = encoder.encodeToString("""{"alg":"RS256","kid":"$kid"}""".toByteArray())
        val expires = System.currentTimeMillis() / 1000 + 600
        val claims = """{"iss":"$ISSUER","aud":["orders"],"exp":$expires,"sub":"system:serviceaccount:reports-staging:reports"}"""
        val body = "$head.${encoder.encodeToString(claims.toByteArray())}"
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(body.toByteArray())
            sign()
        }
        return "Bearer $body.${encoder.encodeToString(signature)}"
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
    fun `the mounted files win, so a rotation or a new caller needs no restart`() {
        // The environment allows only billing; the file allows reports. The file wins, and its newline is trimmed.
        assertEquals(Caller("app", "reports"), platform.identify(bearer("k1", first), null))
        // The same process: the kubelet rewrites the mounted file and who may call changes. An empty list
        // allows nobody; it does not fall back to the environment's.
        Files.writeString(auth.resolve("callers"), "billing\n")
        assertThrows(Unauthorized::class.java) { platform.identify(bearer("k1", first), null) }
        Files.writeString(auth.resolve("callers"), "")
        assertThrows(Unauthorized::class.java) { platform.identify(bearer("k1", first), null) }
        Files.writeString(auth.resolve("callers"), "billing,reports\n")
        // A rotated key: the new one verifies at once, and the old one no longer does.
        Files.writeString(auth.resolve("keys"), jwks("k2", second))
        assertEquals(Caller("app", "reports"), platform.identify(bearer("k2", second), null))
        assertThrows(Unauthorized::class.java) { platform.identify(bearer("k1", first), null) }
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
