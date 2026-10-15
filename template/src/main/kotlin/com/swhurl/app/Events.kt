package com.swhurl.app

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.micronaut.json.JsonMapper
import io.micronaut.json.tree.JsonNode
import io.nats.client.AuthHandler
import io.nats.client.Connection
import io.nats.client.MessageConsumer
import io.nats.client.NKey
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.impl.Headers
import io.nats.client.impl.NatsMessage
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.slf4j.LoggerFactory
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.dataformat.yaml.YAMLMapper

// Events between apps on the swhurl platform's NATS JetStream.
// https://github.com/samclement/swhurl-platform/blob/main/docs/apps.md#events
//
// What it publishes is declared in swhurl.yaml (events: {publish: [created]}) or asyncapi.yaml; what it
// receives is granted on the platform (Connect to an app, or make app-events). The platform then gives
// this app an address, a key and permission for exactly those events:
//
//   events.publish("created", mapOf("id" to 42))            // other apps see <this app>.created
//   events.subscribe("orders.created") { event -> ... }     // throw to get it again
//
// An app that describes its events in src/main/resources/asyncapi.yaml (the template's "events"
// feature) may publish only those, each with a payload that fits its schema: publish() throws
// EventInvalid otherwise, on the platform and off it. The file is what other apps read to find and
// subscribe to them.
//
// An event travels as a CloudEvents JSON envelope with the trace context in its headers, so the
// subscriber's work joins the publisher's trace. Off the platform (local development, tests) there
// is no SWHURL_EVENTS_URL: publish() does nothing and returns null, subscribe() never calls back.

/** A received event: `data` is what the publisher sent. */
data class Event(val id: String, val source: String, val type: String, val time: String, val data: JsonNode?)

/** An event that asyncapi.yaml does not describe, or a payload that does not fit its schema. */
class EventInvalid(message: String) : RuntimeException(message)

@Singleton
class Events(private val platform: Platform, private val json: JsonMapper) {
    private val log = LoggerFactory.getLogger(Events::class.java)
    private val consumers = CopyOnWriteArrayList<MessageConsumer>()
    private var connection: Connection? = null

    @Volatile
    private var closed = false

    private val app get() = platform.setting("SWHURL_APP") ?: "app"
    private val environment get() = platform.setting("SWHURL_ENV") ?: "local"
    private fun list(variable: String) = (platform.setting(variable) ?: "").split(",").filter { it.isNotEmpty() }

    /** The events this app declared it publishes, and the `<app>.<event>` pairs it declared it receives. */
    fun declaredPublishes(): List<String> = list("SWHURL_EVENTS_PUBLISH")
    fun declaredSubscriptions(): List<String> = list("SWHURL_EVENTS_SUBSCRIBE")

    /** Whether the platform connected this app to NATS. */
    fun enabled(): Boolean = platform.setting("SWHURL_EVENTS_URL") != null && platform.setting("SWHURL_EVENTS_SEED") != null

    // The platform's naming: staging and production share one NATS and are kept apart by name.
    fun subjectOf(publisher: String, event: String) = "$environment.$publisher.$event"
    fun streamOf(publisher: String) = "$environment-$publisher"
    fun consumerOf(event: String) = "${app}_$event"

    /** The CloudEvents envelope for one of this app's events, as JSON. */
    fun envelope(event: String, data: Map<String, Any?>, id: String = UUID.randomUUID().toString()): String =
        json.writeValueAsString(
            mapOf(
                "specversion" to "1.0",
                "id" to id,
                "source" to app,
                "type" to "$app.$event",
                "time" to Instant.now().toString(),
                "datacontenttype" to "application/json",
                "data" to data,
            ),
        )

    /**
     * The events asyncapi.yaml says this app sends, each with a check for its payload (null: any
     * payload); null when the app has no such file, and nothing is checked. A channel's address is
     * the event's name; with an `operations` section, only channels an operation sends to count.
     */
    val described: Map<String, Schema?>? by lazy {
        val file = platform.setting("SWHURL_EVENTS_FILE") ?: "asyncapi.yaml"
        val source = Events::class.java.classLoader.getResourceAsStream(file) ?: return@lazy null
        val doc = source.use { YAMLMapper().readTree(it) }
        fun text(node: tools.jackson.databind.JsonNode?) = if (node != null && node.isString) node.stringValue() else ""
        // Write out local references ("#/components/..."), so a schema stands on its own.
        fun settled(node: tools.jackson.databind.JsonNode, depth: Int = 0): tools.jackson.databind.JsonNode {
            require(depth < 32) { "asyncapi.yaml: references nest too deeply (a cycle?)" }
            if (node.isObject && node.has("\$ref")) return settled(doc.at(text(node.get("\$ref")).removePrefix("#")), depth + 1)
            if (node.isObject) {
                val copy = JsonNodeFactory.instance.objectNode()
                node.properties().forEach { (key, value) -> copy.set(key, settled(value, depth + 1)) }
                return copy
            }
            if (node.isArray) {
                val copy = JsonNodeFactory.instance.arrayNode()
                node.forEach { copy.add(settled(it, depth + 1)) }
                return copy
            }
            return node
        }
        val operations = doc.path("operations").toList()
        val sends = operations.filter { text(it.get("action")) == "send" }.map { text(it.path("channel").get("\$ref")) }.toSet()
        val schemas = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7)
        val found = LinkedHashMap<String, Schema?>()
        doc.path("channels").properties().forEach { (key, channel) ->
            if (operations.isNotEmpty() && "#/channels/$key" !in sends) return@forEach
            val message = channel.path("messages").firstOrNull()?.let { settled(it) }
            found[text(channel.get("address")).ifEmpty { key }] = message?.get("payload")?.let { schemas.getSchema(it) }
        }
        found
    }

    private fun checkDescribed(event: String, data: Map<String, Any?>) {
        val events = described ?: return
        if (event !in events) {
            throw EventInvalid("\"$event\" is not an event asyncapi.yaml describes (${events.keys.joinToString().ifEmpty { "none" }})")
        }
        // As it will travel: the JSON the subscriber receives.
        val problems = events[event]?.validate(json.writeValueAsString(data), InputFormat.JSON) ?: return
        if (problems.isNotEmpty()) {
            throw EventInvalid("\"$event\" does not fit its schema in asyncapi.yaml: ${problems.joinToString("; ") { it.message }}")
        }
    }

    /** The app's NATS connection, opened on first use: events and feature flag overrides (FlagWatcher) share it. */
    fun connection(): Connection = connected()

    @Synchronized
    private fun connected(): Connection {
        connection?.let { return it }
        // The app's own key: the server knows the public half, the seed is this app's Secret.
        val key = NKey.fromSeed(platform.setting("SWHURL_EVENTS_SEED")!!.toCharArray())
        val options = Options.Builder()
            .server(platform.setting("SWHURL_EVENTS_URL"))
            .connectionName("$app-$environment")
            .authHandler(object : AuthHandler {
                override fun sign(nonce: ByteArray): ByteArray = key.sign(nonce)
                override fun getID(): CharArray = key.publicKey
                override fun getJWT(): CharArray? = null
            })
            // Replies come back under this app's own prefix; it may read no other.
            .inboxPrefix("_INBOX.$environment.$app")
            .maxReconnects(-1)
            .build()
        return Nats.connect(options).also { connection = it }
    }

    /** Publish one of this app's events. Returns its id once NATS has stored it; null off the platform. */
    fun publish(event: String, data: Map<String, Any?>): String? {
        checkDescribed(event, data)
        if (!enabled()) return null
        val subject = subjectOf(app, event)
        val span = GlobalOpenTelemetry.getTracer("swhurl-events").spanBuilder("publish $app.$event")
            .setSpanKind(SpanKind.PRODUCER).setAttribute("messaging.system", "nats")
            .setAttribute("messaging.destination.name", subject).startSpan()
        try {
            span.makeCurrent().use {
                val id = UUID.randomUUID().toString()
                val headers = Headers()
                GlobalOpenTelemetry.getPropagators().textMapPropagator
                    .inject(Context.current(), headers) { carrier, name, value -> carrier?.put(name, value) }
                headers.put("Nats-Msg-Id", id)
                val message = NatsMessage.builder().subject(subject).headers(headers)
                    .data(envelope(event, data, id).toByteArray()).build()
                connected().jetStream().publish(message)
                return id
            }
        } catch (problem: Exception) {
            span.recordException(problem)
            span.setStatus(StatusCode.ERROR)
            throw problem
        } finally {
            span.end()
        }
    }

    /**
     * Receive a declared event, "<app>.<event>", from where this app last stopped. The handler's
     * work is a span in the publisher's trace. If it throws, the event comes back after a few
     * seconds (five tries in all).
     *
     * Returns at once and keeps trying in the background until it is receiving: a new app can
     * start before NATS has loaded its user or the platform has created its consumer.
     */
    fun subscribe(name: String, handler: (Event) -> Unit) {
        val parts = name.split(".")
        require(parts.size == 2 && parts.all { it.isNotEmpty() }) { "subscribe to \"<app>.<event>\", not \"$name\"" }
        if (!enabled()) return
        val (publisher, event) = parts
        thread(name = "events-$name", isDaemon = true) {
            while (!closed) {
                try {
                    // The platform created this consumer from the declaration; an undeclared one does not exist.
                    val consumer = connected().getConsumerContext(streamOf(publisher), consumerOf(event))
                    consumers += consumer.consume { message ->
                        val parent = GlobalOpenTelemetry.getPropagators().textMapPropagator
                            .extract(Context.current(), message.headers, HeaderGetter)
                        val span = GlobalOpenTelemetry.getTracer("swhurl-events").spanBuilder("process $name")
                            .setParent(parent).setSpanKind(SpanKind.CONSUMER)
                            .setAttribute("messaging.system", "nats")
                            .setAttribute("messaging.destination.name", message.subject).startSpan()
                        try {
                            span.makeCurrent().use {
                                val body = json.readValue(message.data, JsonNode::class.java)
                                fun text(field: String) = body?.get(field)?.takeIf { it.isString }?.stringValue ?: ""
                                handler(Event(text("id"), text("source"), text("type"), text("time"), body?.get("data")))
                            }
                            message.ack()
                        } catch (problem: Exception) {
                            span.recordException(problem)
                            span.setStatus(StatusCode.ERROR)
                            message.nakWithDelay(Duration.ofSeconds(5))
                        } finally {
                            span.end()
                        }
                    }
                    return@thread
                } catch (problem: Exception) {
                    log.warn("not receiving $name yet; trying again: ${problem.message}")
                    synchronized(this) {
                        connection?.takeIf { it.status != Connection.Status.CONNECTED }?.let {
                            runCatching { it.close() }
                            connection = null
                        }
                    }
                    Thread.sleep(5000)
                }
            }
        }
    }

    /** Finish and leave NATS when the app stops. */
    @PreDestroy
    fun close() {
        closed = true
        consumers.forEach { runCatching { it.stop() } }
        synchronized(this) { connection?.let { runCatching { it.close() } } }
    }
}

private object HeaderGetter : TextMapGetter<Headers> {
    override fun keys(carrier: Headers): Iterable<String> = carrier.keySet()
    override fun get(carrier: Headers?, key: String): String? = carrier?.getFirst(key)
}
