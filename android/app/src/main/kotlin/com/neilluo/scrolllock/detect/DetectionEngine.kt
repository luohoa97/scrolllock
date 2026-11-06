package com.neilluo.scrolllock.detect

/**
 * Pure rule matcher: takes a flattened tree, returns the first matching [Surface].
 * No Android types, so it is covered by JVM unit tests on captured fixtures.
 */
class DetectionEngine(private val rules: Rules) {

    private val ordered = rules.rules.sortedBy { it.priority }

    fun detect(tree: TreeSnapshot): Surface {
        if (tree.packageName != rules.packageName) return Surface.Unknown
        for (rule in ordered) {
            if (rule.all.isEmpty()) continue
            val allHold = rule.all.all { m -> tree.nodes.any { matches(m, it, tree.screen) } }
            if (!allHold) continue
            val noneHold = rule.none.none { m -> tree.nodes.any { matches(m, it, tree.screen) } }
            if (!noneHold) continue
            return Surface.entries.firstOrNull { it.name.equals(rule.surface, ignoreCase = true) } ?: continue
        }
        return Surface.Unknown
    }

    /** Name of the rule that fired, for the debug screen. */
    fun explain(tree: TreeSnapshot): String? {
        if (tree.packageName != rules.packageName) return null
        return ordered.firstOrNull { rule ->
            rule.all.isNotEmpty() &&
                rule.all.all { m -> tree.nodes.any { matches(m, it, tree.screen) } } &&
                rule.none.none { m -> tree.nodes.any { matches(m, it, tree.screen) } }
        }?.name
    }

    companion object {
        fun matches(m: Matcher, n: NodeSnapshot, screen: Box): Boolean {
            m.idContains?.let { if (n.id?.contains(it, ignoreCase = true) != true) return false }
            m.descEquals?.let { if (n.desc?.trim()?.equals(it, ignoreCase = true) != true) return false }
            m.descContains?.let { if (n.desc?.contains(it, ignoreCase = true) != true) return false }
            m.classContains?.let { if (n.cls?.contains(it, ignoreCase = true) != true) return false }
            m.selected?.let { if (n.selected != it) return false }
            m.coversScreen?.let {
                val covers = screen.area > 0 && n.bounds.area.toDouble() / screen.area >= m.coverRatio
                if (covers != it) return false
            }
            return true
        }
    }
}
