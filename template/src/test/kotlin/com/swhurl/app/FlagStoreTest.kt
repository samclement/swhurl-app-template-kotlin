package com.swhurl.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// A flag is an override from the console, else the value swhurl.yaml declares for this environment,
// else its default; an override that does not fit the declaration is ignored.
class FlagStoreTest {
    private val declared = mapOf(
        "new-checkout" to FlagStore.Declared("boolean", default = false, staging = true),
        "max-basket-items" to FlagStore.Declared("integer", default = 20, prod = 10, min = 1.0, max = 100.0),
        "discount-rate" to FlagStore.Declared("number", default = 0.0, max = 0.5),
        "banner" to FlagStore.Declared("string", default = "none", choices = listOf("none", "sale")),
    )

    private fun value(env: String?, name: String) = FlagStore(declared, env).value(name)

    @Test
    fun `the value declared for this environment wins over the default`() {
        assertEquals(true, value("staging", "new-checkout"))
        assertEquals(false, value("prod", "new-checkout"))
        assertEquals(10, value("prod", "max-basket-items"))
        assertEquals(20, value("staging", "max-basket-items"))
    }

    @Test
    fun `outside the platform every flag is its default and nothing is watched`() {
        assertEquals(false, value(null, "new-checkout"))
        assertEquals(20, value("dev", "max-basket-items"))
        val store = FlagStore(declared, null)
        assertEquals(false, store.startWatching())
        assertTrue(store.loaded)
    }

    @Test
    fun `an override wins until it is cleared, and must fit the declaration`() {
        val store = FlagStore(declared, "prod")
        assertEquals("", store.apply("new-checkout", true))
        assertEquals(true, store.value("new-checkout"))
        assertEquals("", store.apply("new-checkout", null))
        assertEquals(false, store.value("new-checkout"))
        assertTrue(store.apply("new-checkout", "yes").contains("is not a boolean"))
        assertTrue(store.apply("max-basket-items", 1.5).contains("is not an integer"))
        assertTrue(store.apply("max-basket-items", 500).contains("outside min and max"))
        assertTrue(store.apply("banner", "loud").contains("not one of none, sale"))
        assertTrue(store.apply("gone", true).contains("which swhurl.yaml does not declare"))
        assertEquals(10, store.value("max-basket-items"))
        assertEquals("", store.apply("discount-rate", 0))
        assertEquals(0.0, store.value("discount-rate"), "a whole number is still a Double")
        assertEquals("", store.apply("max-basket-items", 30L))
        assertEquals(30, store.value("max-basket-items"), "JSON may give a Long: kept as an Int")
    }

    @Test
    fun `an undeclared flag is a mistake in the code`() {
        assertThrows<IllegalArgumentException> { value("staging", "missing") }
    }
}
