package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaxGraphHeightRuleTest {

    private fun node(path: String) =
        ModuleNode(path = path, name = path.substringAfterLast(":"), type = ModuleType.JVM_LIBRARY)

    private fun edge(from: String, to: String, config: String = "implementation") =
        DependencyEdge(from = from, to = to, configuration = config)

    private fun chain(length: Int): ModuleDependencyGraph {
        val modules = (0 until length).map { node(":m$it") }
        return ModuleDependencyGraph("chain", modules, modules.zipWithNext { a, b -> edge(a.path, b.path) })
    }

    @Test
    fun `no violation when height is within the limit`() {
        assertTrue(MaxGraphHeightRule(maxHeight = 5).evaluate(chain(4)).isEmpty())
    }

    @Test
    fun `no violation when height equals the limit`() {
        assertTrue(MaxGraphHeightRule(maxHeight = 4).evaluate(chain(4)).isEmpty())
    }

    @Test
    fun `violation when height exceeds the limit`() {
        val violations = MaxGraphHeightRule(maxHeight = 3).evaluate(chain(5))
        assertEquals(1, violations.size)
        assertTrue(violations.first().message.contains("height is 5"))
        assertTrue(violations.first().message.contains("limit: 3"))
    }

    @Test
    fun `violation severity is WARNING by default`() {
        assertEquals(Severity.WARNING, MaxGraphHeightRule(maxHeight = 1).evaluate(chain(5)).first().severity)
    }

    @Test
    fun `violation ruleId is stable`() {
        assertEquals("max-graph-height", MaxGraphHeightRule(maxHeight = 1).evaluate(chain(5)).first().ruleId)
    }

    @Test
    fun `moduleHint points at the deepest module on the critical path`() {
        val violation = MaxGraphHeightRule(maxHeight = 1).evaluate(chain(5)).first()
        assertEquals(":m4", violation.moduleHint)
    }

    @Test
    fun `plainLanguageExplanation is set`() {
        val violation = MaxGraphHeightRule(maxHeight = 1).evaluate(chain(5)).first()
        assertTrue(violation.plainLanguageExplanation?.isNotBlank() == true)
    }

    @Test
    fun `cyclic graph produces no violation because height is undefined`() {
        val graph = ModuleDependencyGraph(
            projectName = "cyclic",
            modules = listOf(node(":a"), node(":b")),
            edges = listOf(edge(":a", ":b"), edge(":b", ":a")),
        )
        assertTrue(MaxGraphHeightRule(maxHeight = 1).evaluate(graph).isEmpty())
    }
}
