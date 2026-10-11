package com.swhurl.app

import io.micronaut.context.annotation.Property
import io.micronaut.json.JsonMapper
import io.micronaut.json.tree.JsonNode
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

// The events helper without a NATS server: the platform's naming, the envelope, and that an app
// off the platform publishes and receives nothing instead of failing.
@MicronautTest(startApplication = false)
@Property(name = "swhurl.app", value = "notifier")
@Property(name = "swhurl.env", value = "staging")
@Property(name = "swhurl.events.publish", value = "sent,failed")
@Property(name = "swhurl.events.subscribe", value = "orders.created")
class EventsTest {
    @Inject
    lateinit var events: Events

    @Inject
    lateinit var json: JsonMapper

    @Test
    fun `names follow the platform's`() {
        assertEquals("staging.orders.created", events.subjectOf("orders", "created"))
        assertEquals("staging-orders", events.streamOf("orders"))
        assertEquals("notifier_created", events.consumerOf("created"))
        assertEquals(listOf("sent", "failed"), events.declaredPublishes())
        assertEquals(listOf("orders.created"), events.declaredSubscriptions())
    }

    @Test
    fun `an event is a CloudEvents envelope from this app`() {
        val event = json.readValue(events.envelope("sent", mapOf("to" to "sam", "tries" to 2), "id-1"), JsonNode::class.java)!!
        assertEquals("1.0", event.get("specversion")!!.stringValue)
        assertEquals("id-1", event.get("id")!!.stringValue)
        assertEquals("notifier", event.get("source")!!.stringValue)
        assertEquals("notifier.sent", event.get("type")!!.stringValue)
        assertEquals("sam", event.get("data")!!.get("to")!!.stringValue)
        assertEquals(2, event.get("data")!!.get("tries")!!.intValue)
    }

    @Test
    fun `off the platform nothing is published or received, and nothing fails`() {
        assertFalse(events.enabled())
        assertNull(events.publish("sent", mapOf()))
        events.subscribe("orders.created") { throw AssertionError("no event can arrive") }
        assertThrows(IllegalArgumentException::class.java) { events.subscribe("created") {} }
    }
}
