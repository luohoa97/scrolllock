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
    /** Consecutive allowed results needed before the overlay may come down. Stops flicker. */
    private val allowConfirm: Int = 2,
) {
    private var unknownRun = 0
    private var allowRun = 0

    fun reset() { unknownRun = 0; allowRun = 0 }

    fun feed(surface: Surface): Verdict {
        if (surface == Surface.Unknown) {
            allowRun = 0
            unknownRun++
            return if (unknownRun >= unknownLimit) Verdict.Block else Verdict.Undecided
        }
        unknownRun = 0
        if (surface in blocked) {
            allowRun = 0
            return Verdict.Block
        }
        // Blocking is instant, unblocking has to be sure: one stray match must not drop the overlay.
        allowRun++
        return if (allowRun >= allowConfirm) Verdict.Allow else Verdict.Undecided
    }

    /** True when the last result was a block caused by not recognising the screen. */
    val failingClosed: Boolean get() = unknownRun >= unknownLimit
}
