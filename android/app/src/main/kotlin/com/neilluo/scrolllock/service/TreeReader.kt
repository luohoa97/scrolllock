package com.neilluo.scrolllock.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.neilluo.scrolllock.detect.Box
import com.neilluo.scrolllock.detect.NodeSnapshot
import com.neilluo.scrolllock.detect.TreeSnapshot

/**
 * Flattens an accessibility tree into [NodeSnapshot]s. Text is never read, only view ids, class
 * names, content descriptions, selected state and bounds. Depth and node counts are capped so one
 * pass stays cheap even on a heavy screen.
 */
object TreeReader {
    const val MAX_DEPTH = 12
    const val MAX_NODES = 600

    fun read(root: AccessibilityNodeInfo, screen: Box): TreeSnapshot {
        val out = ArrayList<NodeSnapshot>(128)
        val rect = Rect()
        walk(root, 0, out, rect)
        return TreeSnapshot(root.packageName?.toString().orEmpty(), screen, out)
    }

    @Suppress("DEPRECATION")
    private fun walk(node: AccessibilityNodeInfo, depth: Int, out: MutableList<NodeSnapshot>, rect: Rect) {
        if (out.size >= MAX_NODES) return
        node.getBoundsInScreen(rect)
        out += NodeSnapshot(
            id = node.viewIdResourceName,
            cls = node.className?.toString(),
            desc = node.contentDescription?.toString(),
            selected = node.isSelected,
            depth = depth,
            bounds = Box(rect.left, rect.top, rect.right, rect.bottom),
        )
        if (depth >= MAX_DEPTH) return
        for (i in 0 until node.childCount) {
            if (out.size >= MAX_NODES) return
            val child = node.getChild(i) ?: continue
            try {
                walk(child, depth + 1, out, rect)
            } finally {
                child.recycle() // no-op from API 33, still needed on 31 and 32
            }
        }
    }
}

/** Collects visible labels from a Settings or installer screen. Used only while Lockdown is on. */
object TextReader {
    private const val MAX_NODES = 400

    fun labels(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>(64)
        var seen = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (seen >= MAX_NODES) return
            seen++
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            if (depth >= TreeReader.MAX_DEPTH) return
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                try { walk(c, depth + 1) } finally { @Suppress("DEPRECATION") c.recycle() }
            }
        }
        walk(root, 0)
        return out
    }
}
