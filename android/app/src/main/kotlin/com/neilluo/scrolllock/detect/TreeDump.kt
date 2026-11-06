package com.neilluo.scrolllock.detect

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class DumpNode(
    val id: String? = null,
    val cls: String? = null,
    val desc: String? = null,
    val selected: Boolean = false,
    val depth: Int = 0,
    val box: List<Int>,
)

@Serializable
private data class Dump(
    val packageName: String,
    val screen: List<Int>,
    val surface: String,
    val rule: String?,
    val nodes: List<DumpNode>,
)

/**
 * Turns a captured screen into JSON for fixtures and for fixing rules after an Instagram update.
 * Descriptions are redacted to their length unless the node is selected or looks like a tab, so a
 * dump never carries message previews, usernames or captions. Message text is never captured at all.
 */
object TreeDump {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun keepDescription(n: NodeSnapshot): Boolean =
        n.selected || n.id?.contains("tab", ignoreCase = true) == true

    fun toJson(tree: TreeSnapshot, surface: Surface, rule: String?): String {
        val nodes = tree.nodes.map { n ->
            DumpNode(
                id = n.id,
                cls = n.cls,
                desc = n.desc?.let { if (keepDescription(n)) it else "[redacted len=${it.length}]" },
                selected = n.selected,
                depth = n.depth,
                box = listOf(n.bounds.left, n.bounds.top, n.bounds.right, n.bounds.bottom),
            )
        }
        val b = tree.screen
        return json.encodeToString(Dump(tree.packageName, listOf(b.left, b.top, b.right, b.bottom), surface.name, rule, nodes))
    }
}
