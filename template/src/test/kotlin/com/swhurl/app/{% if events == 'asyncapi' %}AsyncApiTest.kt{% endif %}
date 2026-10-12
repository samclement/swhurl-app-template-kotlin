package com.swhurl.app

import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.dataformat.yaml.YAMLMapper

// asyncapi.yaml is what other apps read to subscribe to this app's events, and what Events.kt holds
// each publish to. This checks the file itself: every event it sends has a description, a payload
// schema and an example, and each example fits its schema.
@MicronautTest(startApplication = false)
class AsyncApiTest {
    @Inject
    lateinit var events: Events

    private val yaml = YAMLMapper()
    private val doc = yaml.readTree(javaClass.getResourceAsStream("/asyncapi.yaml"))
    private fun text(node: JsonNode?) = if (node != null && node.isString) node.stringValue() else ""
    private fun settled(node: JsonNode) = if (node.has("\$ref")) doc.at(text(node.get("\$ref")).removePrefix("#")) else node

    @Test
    fun `it is an AsyncAPI 3 document that sends at least one event`() {
        assertTrue(text(doc.get("asyncapi")).startsWith("3."))
        assertTrue(text(doc.path("info").get("title")).isNotEmpty())
        assertTrue(events.described!!.isNotEmpty(), "asyncapi.yaml sends no event")
    }

    @Test
    fun `every event has a description, a payload schema and an example that fits it`() {
        doc.path("channels").properties().forEach { (key, channel) ->
            val event = text(channel.get("address")).ifEmpty { key }
            if (event !in events.described!!) return@forEach // a channel this app only receives from
            assertTrue(text(channel.get("description")).isNotEmpty(), "$event: say what it means (channels.$key.description)")
            val message = settled(channel.path("messages").first())
            assertTrue(message.has("payload"), "$event: its message needs a payload schema")
            assertTrue(message.path("examples").size() > 0, "$event: give an example, which other apps see")
            message.path("examples").forEach { example ->
                @Suppress("UNCHECKED_CAST")
                val payload = yaml.convertValue(example.path("payload"), Map::class.java) as Map<String, Any?>
                assertNull(events.publish(event, payload), "fits; nothing is sent off the platform")
            }
        }
    }

    @Test
    fun `an event that is not described, or a payload that does not fit, is refused`() {
        assertThrows(EventInvalid::class.java) { events.publish("not-described", mapOf()) }
        val refused = assertThrows(EventInvalid::class.java) { events.publish(events.described!!.keys.first(), mapOf("unexpected" to true)) }
        assertTrue(refused.message!!.contains("does not fit its schema in asyncapi.yaml"), refused.message)
        assertEquals(EventInvalid::class.java, refused.javaClass)
    }
}
