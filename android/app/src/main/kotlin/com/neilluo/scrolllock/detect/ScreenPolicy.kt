package com.neilluo.scrolllock.detect

/** What the overlay controller should do after a detection result. */
enum class Verdict { Allow, Block, Undecided }

/**
 * Default deny. While locked, Instagram is blocked unless the screen is positively recognised as
 * one of [allowed] (messages, by default). A screen the rules don't recognise is blocked, not
 * waved through, so an Instagram update that breaks the rules can only over-block, never leak.
 * Blocking is immediate. Unblocking needs [allowConfirm] matching results in a row, so one stray
 * match can't drop the overlay.
 */
class ScreenPolicy(
    private val allowed: Set<Surface> = setOf(Surface.Dm),
    private val allowConfirm: Int = 2,
) {
    private var allowRun = 0

    fun reset() { allowRun = 0 }

    fun feed(surface: Surface): Verdict {
        if (surface !in allowed) {
            allowRun = 0
            return Verdict.Block
        }
        allowRun++
        return if (allowRun >= allowConfirm) Verdict.Allow else Verdict.Undecided
    }
}
