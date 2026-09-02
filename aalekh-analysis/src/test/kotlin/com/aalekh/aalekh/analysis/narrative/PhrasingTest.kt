package com.aalekh.aalekh.analysis.narrative

import kotlin.test.Test
import kotlin.test.assertEquals

class PhrasingTest {

    @Test
    fun `count agrees in number for a regular noun`() {
        assertEquals("1 module", Phrasing.count(1, "module"))
        assertEquals("3 modules", Phrasing.count(3, "module"))
        assertEquals("0 modules", Phrasing.count(0, "module"))
    }

    @Test
    fun `count pluralises a consonant-y noun as -ies, not -ys`() {
        assertEquals("1 library", Phrasing.count(1, "library"))
        assertEquals("2 libraries", Phrasing.count(2, "library"))
        assertEquals("5 dependencies", Phrasing.count(5, "dependency"))
    }

    @Test
    fun `count keeps a vowel-y noun regular`() {
        assertEquals("2 days", Phrasing.count(2, "day"))
    }

    @Test
    fun `count honours an explicit irregular plural`() {
        assertEquals("2 indices", Phrasing.count(2, "index", "indices"))
    }
}
