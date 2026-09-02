package com.aalekh.aalekh.analysis.spi

import com.aalekh.aalekh.model.Finding
import com.aalekh.aalekh.model.FindingCategory
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.model.Provenance
import com.aalekh.aalekh.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionEngineTest {

    private val graph = ModuleDependencyGraph(
        projectName = "test",
        modules = listOf(":app", ":core").map { ModuleNode(it, it.trimStart(':'), ModuleType.JVM_LIBRARY) },
        edges = emptyList(),
    )

    private fun finding(id: String) = Finding(
        id = id,
        category = FindingCategory.STRUCTURE,
        severity = Severity.INFO,
        title = "From $id",
        detail = "A finding contributed by a third-party provider.",
    )

    private class Provider(
        override val id: String,
        private val produce: () -> List<Finding>,
    ) : FindingProvider {
        override fun find(graph: ModuleDependencyGraph): List<Finding> = produce()
    }

    private class Classifier(
        override val id: String,
        private val answer: (String) -> ModuleClassification?,
    ) : ModuleClassifier {
        override fun classify(modulePath: String, graph: ModuleDependencyGraph): ModuleClassification? =
            answer(modulePath)
    }

    @Test
    fun `findings from a provider are collected`() {
        val result = ExtensionEngine.run(
            graph,
            listOf(Provider("mine") { listOf(finding("mine")) }),
            emptyList(),
        )

        assertEquals(listOf("mine"), result.findings.map { it.id })
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `a provider that throws is reported and the rest still run`() {
        val result = ExtensionEngine.run(
            graph,
            listOf(
                Provider("broken") { error("deliberate failure") },
                Provider("working") { listOf(finding("working")) },
            ),
            emptyList(),
        )

        assertEquals(listOf("working"), result.findings.map { it.id })
        assertTrue(result.failures.single().startsWith("broken:"), result.failures.toString())
    }

    @Test
    fun `a blank provider id is skipped and reported`() {
        val result = ExtensionEngine.run(graph, listOf(Provider("  ") { listOf(finding("x")) }), emptyList())

        assertTrue(result.findings.isEmpty())
        assertTrue(result.failures.single().contains("blank finding provider id"))
    }

    @Test
    fun `a duplicate provider id is ignored`() {
        val result = ExtensionEngine.run(
            graph,
            listOf(
                Provider("same") { listOf(finding("first")) },
                Provider("same") { listOf(finding("second")) },
            ),
            emptyList(),
        )

        assertEquals(listOf("first"), result.findings.map { it.id })
        assertTrue(result.failures.single().contains("duplicate"))
    }

    @Test
    fun `a classifier answers for the modules it knows`() {
        val result = ExtensionEngine.run(
            graph,
            emptyList(),
            listOf(
                Classifier("mine") { path ->
                    if (path == ":core") {
                        ModuleClassification(team = "platform", provenance = Provenance.OBSERVED)
                    } else {
                        null
                    }
                }
            ),
        )

        assertEquals("platform", result.classifications.getValue(":core").team)
        assertNull(result.classifications[":app"], "a classifier may know about only part of a project")
    }

    @Test
    fun `the first classifier to answer wins`() {
        val result = ExtensionEngine.run(
            graph,
            emptyList(),
            listOf(
                Classifier("first") { ModuleClassification(team = "first-team") },
                Classifier("second") { ModuleClassification(team = "second-team") },
            ),
        )

        assertEquals("first-team", result.classifications.getValue(":app").team)
    }

    @Test
    fun `a classifier that throws is reported and the next one is tried`() {
        val result = ExtensionEngine.run(
            graph,
            emptyList(),
            listOf(
                Classifier("broken") { error("deliberate failure") },
                Classifier("working") { ModuleClassification(team = "platform") },
            ),
        )

        assertEquals("platform", result.classifications.getValue(":app").team)
        assertTrue(result.failures.any { it.startsWith("broken:") }, result.failures.toString())
    }

    @Test
    fun `no extensions yields an empty result rather than an error`() {
        assertEquals(ExtensionResult.EMPTY, ExtensionEngine.run(graph, emptyList(), emptyList()))
    }

    @Test
    fun `service loader discovery never throws on an empty classpath`() {
        val result = ExtensionEngine.loadAndRun(graph, ExtensionEngineTest::class.java.classLoader)

        assertTrue(result.findings.isEmpty())
        assertTrue(result.classifications.isEmpty())
    }
}
