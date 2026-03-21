package com.aalekh.aalekh.analysis.rules

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobMatcherTest {

    // Exact match

    @Test
    fun `exact path matches itself`() =
        assertTrue(GlobMatcher.matches(":core:domain", ":core:domain"))

    @Test
    fun `exact path does not match different path`() =
        assertFalse(GlobMatcher.matches(":core:domain", ":core:data"))

    @Test
    fun `single segment matches itself`() =
        assertTrue(GlobMatcher.matches(":app", ":app"))

    // Single-segment wildcard *

    @Test
    fun `star matches one segment`() =
        assertTrue(GlobMatcher.matches(":feature:*:domain", ":feature:login:domain"))

    @Test
    fun `star matches different single segment`() =
        assertTrue(GlobMatcher.matches(":feature:*:domain", ":feature:home:domain"))

    @Test
    fun `star does not match two segments`() =
        assertFalse(GlobMatcher.matches(":feature:*:domain", ":feature:login:sub:domain"))

    @Test
    fun `star does not match zero segments`() =
        assertFalse(GlobMatcher.matches(":feature:*:domain", ":feature:domain"))

    @Test
    fun `star at end matches one trailing segment`() =
        assertTrue(GlobMatcher.matches(":core:*", ":core:domain"))

    @Test
    fun `star at end does not match two trailing segments`() =
        assertFalse(GlobMatcher.matches(":core:*", ":core:domain:model"))

    // Multi-segment wildcard **

    @Test
    fun `double star matches one segment`() =
        assertTrue(GlobMatcher.matches(":feature:**", ":feature:login"))

    @Test
    fun `double star matches two segments`() =
        assertTrue(GlobMatcher.matches(":feature:**", ":feature:login:ui"))

    @Test
    fun `double star matches deep path`() =
        assertTrue(GlobMatcher.matches(":feature:**", ":feature:login:data:remote"))

    @Test
    fun `double star matches zero trailing segments`() =
        assertTrue(GlobMatcher.matches(":feature:**", ":feature"))

    @Test
    fun `double star at root matches any path`() =
        assertTrue(GlobMatcher.matches(":**", ":anything:deep:path"))

    @Test
    fun `double star does not match different prefix`() =
        assertFalse(GlobMatcher.matches(":feature:**", ":core:domain"))

    // Mixed wildcards

    @Test
    fun `star then double star`() =
        assertTrue(GlobMatcher.matches(":feature:*:**", ":feature:login:ui:extra"))

    @Test
    fun `pattern with no wildcard does not partially match`() =
        assertFalse(GlobMatcher.matches(":core:domain", ":core:domain:model"))

    // matchesAny

    @Test
    fun `matchesAny returns true when one pattern matches`() =
        assertTrue(GlobMatcher.matchesAny(listOf(":core:*", ":feature:**"), ":core:domain"))

    @Test
    fun `matchesAny returns false when no pattern matches`() =
        assertFalse(GlobMatcher.matchesAny(listOf(":core:*", ":feature:**"), ":app"))

    @Test
    fun `matchesAny returns false for empty pattern list`() =
        assertFalse(GlobMatcher.matchesAny(emptyList(), ":anything"))
}