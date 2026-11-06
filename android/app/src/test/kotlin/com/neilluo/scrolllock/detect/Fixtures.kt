package com.neilluo.scrolllock.detect

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class FxNode(
    val id: String? = null,
    val cls: String? = null,
    val desc: String? = null,
    val selected: Boolean = false,
    val depth: Int = 0,
    val b: List<Int> = listOf(0, 0, 0, 0),
)

@Serializable
private data class FxTree(val `package`: String, val screen: List<Int>, val nodes: List<FxNode>)

object Fixtures {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(name: String): TreeSnapshot {
        val text = javaClass.getResourceAsStream("/fixtures/$name.json")!!.bufferedReader().readText()
        val t = json.decodeFromString(FxTree.serializer(), text)
        return TreeSnapshot(
            packageName = t.`package`,
            screen = Box(t.screen[0], t.screen[1], t.screen[2], t.screen[3]),
            nodes = t.nodes.map { Node(it) },
        )
    }

    private fun Node(n: FxNode) = NodeSnapshot(n.id, n.cls, n.desc, n.selected, n.depth, Box(n.b[0], n.b[1], n.b[2], n.b[3]))

    /** The shipped rules, read from the real asset so a test failure means a shipped-rules failure. */
    fun shippedRules(): Rules {
        val f = File("src/main/assets/rules.json")
        return Rules.parse(f.readText())
    }
}
