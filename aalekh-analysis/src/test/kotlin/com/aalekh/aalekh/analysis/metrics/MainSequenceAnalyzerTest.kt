package com.aalekh.aalekh.analysis.metrics

import com.aalekh.aalekh.analysis.metrics.MainSequenceAnalyzer.TypeAbstractness
import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.MainSequenceZone
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainSequenceAnalyzerTest {

    private fun node(path: String) = ModuleNode(
        path = path,
        name = path.substringAfterLast(":"),
        type = ModuleType.JVM_LIBRARY,
    )

    // :a -> :b, so :a is fully unstable (I=1) and :b is fully stable (I=0).
    private fun pairGraph() = ModuleDependencyGraph(
        projectName = "ms",
        modules = listOf(node(":a"), node(":b")),
        edges = listOf(DependencyEdge(":a", ":b", "implementation")),
    )

    private fun position(report: com.aalekh.aalekh.model.MainSequenceReport, path: String) =
        report.modules.single { it.path == path }

    @Test
    fun `stable abstract and unstable concrete modules sit on the main sequence`() {
        val report = MainSequenceAnalyzer.analyze(
            pairGraph(),
            mapOf(
                ":b" to TypeAbstractness(abstractTypes = 4, concreteTypes = 0), // A=1, I=0 -> D=0
                ":a" to TypeAbstractness(abstractTypes = 0, concreteTypes = 4), // A=0, I=1 -> D=0
            ),
        )
        assertEquals(0.0, position(report, ":b").distance)
        assertEquals(MainSequenceZone.MAIN_SEQUENCE, position(report, ":b").zone)
        assertEquals(0.0, position(report, ":a").distance)
        assertEquals(MainSequenceZone.MAIN_SEQUENCE, position(report, ":a").zone)
    }

    @Test
    fun `stable concrete module is in the zone of pain`() {
        // :b is stable (I=0) and fully concrete (A=0) -> D=1.
        val report = MainSequenceAnalyzer.analyze(
            pairGraph(),
            mapOf(":b" to TypeAbstractness(abstractTypes = 0, concreteTypes = 5)),
        )
        val b = position(report, ":b")
        assertEquals(0.0, b.abstractness)
        assertEquals(0.0, b.instability)
        assertEquals(1.0, b.distance)
        assertEquals(MainSequenceZone.ZONE_OF_PAIN, b.zone)
    }

    @Test
    fun `unstable abstract module is in the zone of uselessness`() {
        // :a is unstable (I=1) and fully abstract (A=1) -> D=1.
        val report = MainSequenceAnalyzer.analyze(
            pairGraph(),
            mapOf(":a" to TypeAbstractness(abstractTypes = 3, concreteTypes = 0)),
        )
        assertEquals(MainSequenceZone.ZONE_OF_USELESSNESS, position(report, ":a").zone)
    }

    @Test
    fun `modules with no counted types are skipped`() {
        val report = MainSequenceAnalyzer.analyze(
            pairGraph(),
            mapOf(
                ":a" to TypeAbstractness(0, 2),
                ":b" to TypeAbstractness(0, 0), // no types -> skipped
            ),
        )
        assertEquals(1, report.modules.size)
        assertEquals(":a", report.modules.single().path)
    }

    @Test
    fun `an empty count map yields the empty report`() {
        val report = MainSequenceAnalyzer.analyze(pairGraph(), emptyMap())
        assertTrue(report.modules.isEmpty())
        assertEquals(0.0, report.averageDistance)
    }

    @Test
    fun `countTypes classifies abstract vs concrete and ignores comments and class references`() {
        val counts = MainSequenceAnalyzer.countTypes(
            """
            package demo
            // this class should not count
            /* another class here */
            interface Repo
            abstract class Base
            sealed class State
            data class User(val id: Int)
            object Registry
            class Impl : Base()
            enum class Color { RED }
            fun k() = String::class
            """.trimIndent()
        )
        // Abstract: interface Repo, abstract class Base, sealed class State.
        assertEquals(3, counts.abstractTypes)
        // Concrete: data class User, object Registry, class Impl, enum class Color.
        assertEquals(4, counts.concreteTypes)
    }

    @Test
    fun `countTypes treats a Java interface as abstract and an enum as concrete`() {
        val counts = MainSequenceAnalyzer.countTypes("public interface Foo {}\nenum Bar { A }")
        assertEquals(1, counts.abstractTypes)
        assertEquals(1, counts.concreteTypes)
    }

    @Test
    fun `results are sorted worst distance first`() {
        val report = MainSequenceAnalyzer.analyze(
            pairGraph(),
            mapOf(
                ":a" to TypeAbstractness(3, 0), // I=1, A=1 -> D=1
                ":b" to TypeAbstractness(4, 0), // I=0, A=1 -> D=0
            ),
        )
        assertEquals(":a", report.modules.first().path)
        assertTrue(report.modules.first().distance >= report.modules.last().distance)
    }
}
