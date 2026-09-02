package com.aalekh.aalekh.analysis.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [LayerSpecParser] - the single parser behind the layer rules and the HTML report.
 *
 * The resolution order these tests pin down is a contract, not an implementation detail: the report
 * draws a module in the layer this parser assigns it, and `LayerDependencyRule` enforces that same
 * layer's rules on it. If they diverged, the report would show one architecture and the build would
 * enforce another.
 */
class LayerSpecParserTest {

    @Test
    fun `parses a full entry`() {
        val specs = LayerSpecParser.parse(listOf("data|:core:data,:feature:*:data|domain|true"))

        assertEquals(1, specs.size)
        val spec = specs.single()
        assertEquals("data", spec.name)
        assertEquals(listOf(":core:data", ":feature:*:data"), spec.modulePatterns)
        assertEquals(listOf("domain"), spec.allowedLayers)
        assertTrue(spec.hasRestriction)
    }

    @Test
    fun `parses an unrestricted layer with no allowed list`() {
        val spec = LayerSpecParser.parse(listOf("domain|:core:domain||false")).single()

        assertEquals(emptyList(), spec.allowedLayers)
        assertTrue(!spec.hasRestriction)
    }

    @Test
    fun `preserves declaration order`() {
        val specs = LayerSpecParser.parse(
            listOf("domain|:core:domain||false", "data|:core:data|domain|true", "ui|:app|domain,data|true")
        )

        assertEquals(listOf("domain", "data", "ui"), specs.map { it.name })
    }

    @Test
    fun `drops malformed entries rather than half-interpreting them`() {
        val specs = LayerSpecParser.parse(listOf("domain|:core:domain", "", "data|:core:data|domain|true"))

        assertEquals(listOf("data"), specs.map { it.name })
    }

    @Test
    fun `first declared match wins`() {
        val specs = LayerSpecParser.parse(
            listOf("domain|:core:**||false", "data|:core:data|domain|true")
        )

        // :core:data matches both patterns; the earlier declaration owns it, which is the layer
        // whose canOnlyDependOn list the build will enforce.
        assertEquals("domain", LayerSpecParser.layerOf(specs, ":core:data")?.name)
    }

    @Test
    fun `unmatched module resolves to no layer`() {
        val specs = LayerSpecParser.parse(listOf("domain|:core:domain||false"))

        assertNull(LayerSpecParser.layerOf(specs, ":feature:login"))
    }

    @Test
    fun `single-segment wildcard does not cross segments`() {
        val specs = LayerSpecParser.parse(listOf("data|:feature:*:data||false"))

        assertEquals("data", LayerSpecParser.layerOf(specs, ":feature:login:data")?.name)
        assertNull(LayerSpecParser.layerOf(specs, ":feature:login:data:remote"))
    }
}
