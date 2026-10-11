package com.swhurl.app

import io.micronaut.context.env.Environment
import io.micronaut.json.JsonMapper
import io.micronaut.json.tree.JsonNode
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.util.Base64

// What the swhurl platform gives this app: who is calling it, and how to call the apps it declared.
// https://github.com/samclement/swhurl-platform/blob/main/docs/apps.md#calling-another-app

/** Who sent a request: type "app" (id: the app's name), "user" (id: their email) or "anonymous". */
@Serdeable
data class Caller(val type: String, val id: String?)

/** The request carried a token this app does not accept: answer 401 with the message. */
class Unauthorized(message: String) : RuntimeException(message)

@Singleton
class Platform(private val environment: Environment, private val json: JsonMapper) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val decoder = Base64.getUrlDecoder()

    // The platform sets these as environment variables (SWHURL_APP ...); tests set the properties.
    private fun setting(variable: String): String? {
        val property = variable.lowercase().replace('_', '.')
        val value = System.getenv(variable) ?: environment.getProperty(property, String::class.java).orElse(null)
        return value?.takeIf { it.isNotBlank() }
    }

    /**
     * Who sent this request. A request with a bearer token must carry a good one (Unauthorized
     * otherwise). Without a token it is a person: signed in when the platform's sign-in put their
     * email on the request, anonymous otherwise.
     */
    fun identify(authorization: String?, email: String?): Caller {
        if (authorization != null && authorization.startsWith("Bearer ", ignoreCase = true)) {
            return appCaller(authorization.substring(7).trim())
        }
        return if (email.isNullOrBlank()) Caller("anonymous", null) else Caller("user", email)
    }

    /** For a route only other apps may use: the calling app's name, or Unauthorized. */
    fun requireApp(caller: Caller): String {
        if (caller.type != "app" || caller.id == null) {
            throw Unauthorized("this route is for apps that declare a call to this one")
        }
        return caller.id
    }

    private fun parse(part: String): JsonNode = try {
        json.readValue(decoder.decode(part), JsonNode::class.java)
    } catch (problem: Exception) {
        null
    } ?: throw Unauthorized("malformed token")

    private fun text(node: JsonNode?): String? = if (node != null && node.isString) node.stringValue else null

    private fun appCaller(token: String): Caller {
        val app = setting("SWHURL_APP")
        val env = setting("SWHURL_ENV")
        val issuer = setting("SWHURL_AUTH_ISSUER")
        // Outside the platform (local development) there are no keys to check a token against.
        val keys = setting("SWHURL_AUTH_KEYS")
            ?: throw Unauthorized("this app is not configured to verify tokens (SWHURL_AUTH_KEYS is not set)")
        if (app == null || env == null || issuer == null) {
            throw Unauthorized("this app is not configured to verify tokens (SWHURL_AUTH_KEYS is not set)")
        }
        val parts = token.split(".")
        if (parts.size != 3) throw Unauthorized("malformed token")
        val header = parse(parts[0])
        val claims = parse(parts[1])
        val key = json.readValue(keys.toByteArray(), JsonNode::class.java)?.get("keys")?.values()
            ?.firstOrNull { text(it.get("kid")) != null && text(it.get("kid")) == text(header.get("kid")) }
        if (key == null || text(header.get("alg")) != "RS256") throw Unauthorized("token signed by an unknown key")
        val valid = try {
            val public = KeyFactory.getInstance("RSA").generatePublic(
                RSAPublicKeySpec(
                    BigInteger(1, decoder.decode(text(key.get("n")))),
                    BigInteger(1, decoder.decode(text(key.get("e")))),
                ),
            )
            Signature.getInstance("SHA256withRSA").run {
                initVerify(public)
                update("${parts[0]}.${parts[1]}".toByteArray())
                verify(decoder.decode(parts[2]))
            }
        } catch (problem: Exception) {
            false
        }
        if (!valid) throw Unauthorized("bad token signature")
        if (text(claims.get("iss")) != issuer) throw Unauthorized("token from another issuer")
        val audience = claims.get("aud")
        val audiences = when {
            audience == null -> emptyList()
            audience.isArray -> audience.values().mapNotNull { text(it) }
            else -> listOfNotNull(text(audience))
        }
        if (app !in audiences) throw Unauthorized("token is for another app")
        val expires = claims.get("exp")
        if (expires == null || !expires.isNumber || expires.longValue * 1000 <= System.currentTimeMillis()) {
            throw Unauthorized("token expired")
        }
        // The caller is the service account <caller> in the namespace <caller>-<this environment>.
        val subject = Regex("^system:serviceaccount:([a-z0-9-]+):([a-z0-9-]+)$").find(text(claims.get("sub")) ?: "")
        val caller = subject?.groupValues?.get(2)
        if (caller == null || subject.groupValues[1] != "$caller-$env") {
            throw Unauthorized("token is not from an app in this environment")
        }
        val allowed = (setting("SWHURL_ALLOWED_CALLERS") ?: "").split(",").filter { it.isNotEmpty() }
        if (caller !in allowed) throw Unauthorized("$caller is not allowed to call $app")
        return Caller("app", caller)
    }

    /**
     * GET a path of another app this one declared in swhurl.yaml (`calls: [name]`): the same
     * environment's instance, with this app's token for it. The OpenTelemetry agent adds the trace
     * headers, so the callee's spans join this request's trace.
     */
    fun call(name: String, path: String): HttpResponse<String> {
        val variable = "SWHURL_SERVICE_${name.uppercase().replace('-', '_')}_URL"
        val base = System.getenv(variable)
            ?: environment.getProperty("swhurl.service.$name.url", String::class.java).orElse(null)
            ?: throw IllegalStateException(
                "$variable is not set: declare the call (calls: [$name] in swhurl.yaml, or make app-calls on the platform)",
            )
        val request = HttpRequest.newBuilder(URI.create(base.trimEnd('/') + path)).timeout(Duration.ofSeconds(10))
        // The platform replaces the token file every few minutes, so read it for each call. Locally
        // there is none: point the variable at a stub that does not check tokens.
        val token = Path.of(setting("SWHURL_TOKENS_DIR") ?: "/var/run/swhurl/tokens", name, "token")
        if (Files.exists(token)) request.header("Authorization", "Bearer ${Files.readString(token).trim()}")
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
    }
}
