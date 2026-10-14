package com.swhurl.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// A flag is the value swhurl.yaml declares for this environment, otherwise its default.
class FlagStoreTest {
    private val declared = mapOf(
        "new-checkout" to FlagStore.Declared(default = false, staging = true),
        "max-basket-items" to FlagStore.Declared(default = 20, prod = 10),
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
    fun `outside the platform every flag is its default`() {
        assertEquals(false, value(null, "new-checkout"))
        assertEquals(20, value("dev", "max-basket-items"))
    }

    @Test
    fun `an undeclared flag is a mistake in the code`() {
        assertThrows<IllegalArgumentException> { value("staging", "missing") }
    }
}
