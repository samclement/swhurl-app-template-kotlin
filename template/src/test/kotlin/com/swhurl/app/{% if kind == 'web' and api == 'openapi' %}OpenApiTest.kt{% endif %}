package com.swhurl.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.dataformat.yaml.YAMLMapper

// openapi.yaml is what other apps read to call this one. It is generated from the controllers at
// build time; this fails when a route changed and the committed file was not regenerated.
class OpenApiTest {
    private val yaml = YAMLMapper()

    @Test
    fun `openapi yaml matches the routes`() {
        val committed = yaml.readTree(javaClass.getResourceAsStream("/openapi.yaml"))
        val folder = java.io.File(javaClass.getResource("/META-INF/swagger")!!.toURI())
        val generated = yaml.readTree(folder.listFiles { file -> file.extension == "yml" }!!.single())
        assertEquals(generated, committed, "run ./gradlew openapi and commit src/main/resources/openapi.yaml")
        assertTrue(committed.path("paths").has("/"))
    }
}
