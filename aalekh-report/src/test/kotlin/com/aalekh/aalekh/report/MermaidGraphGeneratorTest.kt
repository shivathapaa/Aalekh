package com.aalekh.aalekh.report

import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.report.mermaid.MermaidGraphGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MermaidGraphGeneratorTest {

    private fun sampleGraph() = ModuleDependencyGraph(
        projectName = "demo",
        modules = listOf(
            ModuleNode(":app", "app", ModuleType.ANDROID_APP),
            ModuleNode(":core:domain", "domain", ModuleType.JVM_LIBRARY),
            ModuleNode(":core:test-fixtures", "test-fixtures", ModuleType.JVM_LIBRARY),
        ),
        edges = listOf(
            DependencyEdge(":app", ":core:domain", "implementation"),
            DependencyEdge(":app", ":core:test-fixtures", "testImplementation"),
        ),
    )

    @Test
    fun `output declares a top-down graph`() {
        assertTrue(MermaidGraphGenerator.generate(sampleGraph()).contains("graph TD"))
    }

    @Test
    fun `every module appears as a labelled node`() {
        val mermaid = MermaidGraphGenerator.generate(sampleGraph())
        assertTrue(mermaid.contains("[\":app\"]"))
        assertTrue(mermaid.contains("[\":core:domain\"]"))
        assertTrue(mermaid.contains("[\":core:test-fixtures\"]"))
    }

    @Test
    fun `production edges are solid and test edges are dashed`() {
        val mermaid = MermaidGraphGenerator.generate(sampleGraph())
        assertTrue(mermaid.contains("-->"), "A production dependency must render as a solid arrow")
        assertTrue(mermaid.contains("-.->"), "A test-only dependency must render as a dashed arrow")
    }

    @Test
    fun `module types are colour-coded via classDef`() {
        val mermaid = MermaidGraphGenerator.generate(sampleGraph())
        assertTrue(mermaid.contains("classDef android_app"), "Android app type must get a classDef")
        assertTrue(mermaid.contains(ModuleType.ANDROID_APP.color), "classDef must use the module type colour")
    }

    @Test
    fun `output is deterministic across runs`() {
        assertEquals(
            MermaidGraphGenerator.generate(sampleGraph()),
            MermaidGraphGenerator.generate(sampleGraph()),
        )
    }

    @Test
    fun `self-loop edges are dropped`() {
        val graph = ModuleDependencyGraph(
            projectName = "self",
            modules = listOf(ModuleNode(":a", "a", ModuleType.JVM_LIBRARY)),
            edges = listOf(DependencyEdge(":a", ":a", "implementation")),
        )
        val mermaid = MermaidGraphGenerator.generate(graph)
        assertTrue(!mermaid.contains("-->"), "A self-loop must not produce an edge line")
    }

    @Test
    fun `markdown wrapper embeds a mermaid fenced block with a title`() {
        val md = MermaidGraphGenerator.generateMarkdown(sampleGraph(), "demo")
        assertTrue(md.contains("# demo module graph"))
        assertTrue(md.contains("```mermaid"))
        assertTrue(md.contains("graph TD"))
    }

    @Test
    fun `empty graph still produces a valid graph header`() {
        val graph = ModuleDependencyGraph("empty", emptyList(), emptyList())
        assertTrue(MermaidGraphGenerator.generate(graph).contains("graph TD"))
    }
}
