package com.aalekh.aalekh.gradle.extractor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeownersParserTest {

    private fun rules(text: String) = CodeownersParser.parse(text.trimIndent().lines())

    @Test
    fun `parses patterns and owners`() {
        val parsed = rules(
            """
            # Comment
            *           @org/platform
            /core/      @org/core-team @alice
            """
        )

        assertEquals(2, parsed.size)
        assertEquals("*", parsed[0].pattern)
        assertEquals(listOf("@org/platform"), parsed[0].owners)
        assertEquals("core/", parsed[1].pattern)
        assertEquals(listOf("@org/core-team", "@alice"), parsed[1].owners)
    }

    @Test
    fun `ignores comments, blanks and ownerless lines`() {
        val parsed = rules(
            """
            # nothing here

            /orphan/pattern-with-no-owner
            /real/  @team
            """
        )

        assertEquals(1, parsed.size)
    }

    @Test
    fun `skips rules it cannot model rather than guessing an owner`() {
        // Negation and brace expansion change which paths match; a half-applied rule would
        // silently assign the wrong owner, which is worse than assigning none.
        val parsed = rules(
            """
            !/excluded/  @team
            /src/{a,b}/  @team
            /fine/       @team
            """
        )

        assertEquals(listOf("fine/"), parsed.map { it.pattern })
    }

    @Test
    fun `a directory rule covers everything beneath it`() {
        val parsed = rules("/core/  @core-team")

        assertEquals(listOf("@core-team"), CodeownersParser.ownersFor("core", parsed))
        assertEquals(listOf("@core-team"), CodeownersParser.ownersFor("core/data/remote", parsed))
        assertTrue(CodeownersParser.ownersFor("feature/login", parsed).isEmpty())
    }

    @Test
    fun `a star pattern owns everything`() {
        val parsed = rules("*  @everyone")

        assertEquals(listOf("@everyone"), CodeownersParser.ownersFor("anything/at/all", parsed))
    }

    @Test
    fun `the last matching rule wins`() {
        // Both GitHub and GitLab give precedence to the last match, not the most specific one.
        val parsed = rules(
            """
            *            @default
            /core/       @core-team
            """
        )

        assertEquals(listOf("@core-team"), CodeownersParser.ownersFor("core/data", parsed))
        assertEquals(listOf("@default"), CodeownersParser.ownersFor("app", parsed))
    }

    @Test
    fun `a wildcard segment matches one level`() {
        val parsed = rules("/feature/*/data  @data-team")

        assertEquals(listOf("@data-team"), CodeownersParser.ownersFor("feature/login/data", parsed))
        assertEquals(listOf("@data-team"), CodeownersParser.ownersFor("feature/login/data/remote", parsed))
        assertTrue(CodeownersParser.ownersFor("feature/login/ui", parsed).isEmpty())
    }

    @Test
    fun `an empty file yields no rules`() {
        assertTrue(CodeownersParser.parse(emptyList()).isEmpty())
    }
}
