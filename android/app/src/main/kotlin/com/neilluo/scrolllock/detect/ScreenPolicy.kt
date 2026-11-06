package com.neilluo.scrolllock.detect

/** What the overlay controller should do after a detection result. */
enum class Verdict { Allow, Block, Undecided }

/**
 * Turns [Surface] results into a verdict. A run of [unknownLimit] consecutive Unknown results
 * while Instagram is in front blocks, so an Instagram update that breaks the rules fails closed
 * instead of silently letting Reels through. Allowed surfaces reset the run.
 */
class ScreenPolicy(
    private val blocked: Set<Surface> = setOf(Surface.Reels, Surface.Explore),
    private val unknownLimit: Int = 4,
) {
    private var unknownRun = 0

    fun reset() { unknownRun = 0 }

    fun feed(surface: Surface): Verdict {
        if (surface == Surface.Unknown) {
            unknownRun++
            return if (unknownRun >= unknownLimit) Verdict.Block else Verdict.Undecided
        }
        unknownRun = 0
        return if (surface in blocked) Verdict.Block else Verdict.Allow
    }

    /** True when the last result was a block caused by not recognising the screen. */
    val failingClosed: Boolean get() = unknownRun >= unknownLimit
}
