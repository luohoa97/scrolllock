package com.neilluo.scrolllock.detect

/** Screen-space rectangle, plain ints so the detection code runs on the JVM without Android. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong().coerceAtLeast(0) * height.toLong().coerceAtLeast(0)
}

/**
 * One accessibility node, flattened. This is all the detection engine ever sees.
 * It carries structure and control labels, never the text of messages or text fields:
 * [text] is deliberately absent.
 */
data class NodeSnapshot(
    val id: String? = null,
    val cls: String? = null,
    val desc: String? = null,
    val selected: Boolean = false,
    val depth: Int = 0,
    val bounds: Box = Box(0, 0, 0, 0),
)

/** A capture of one window: its nodes plus the screen size, so "covers the screen" rules work. */
data class TreeSnapshot(
    val packageName: String,
    val screen: Box,
    val nodes: List<NodeSnapshot>,
)
