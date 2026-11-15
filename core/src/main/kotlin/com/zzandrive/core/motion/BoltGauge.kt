package com.zzandrive.core.motion

/**
 * The bolt above the character's head as a gauge (spec D-40). The team's v23 clips come in six fills (0·30·50·70·90·100 %)
 * that differ only there: the bolt fills with yellow from the bottom up, and the rest is the same animation. The app ships
 * the full one (`_bolt100`) and empties the top at run time (app `BoltShade`). These are the bolt's places on the 720 px
 * canvas, measured on every frame of the designer's files.
 */
object BoltGauge {
    const val CANVAS = 720f

    /** The box the bolt never leaves, in canvas px (right and bottom excluded). Nothing else inside it is yellow. */
    const val LEFT = 296f
    const val TOP = 18f
    const val RIGHT = 409f
    const val BOTTOM = 147f

    /**
     * The canvas row above which the bolt is empty when [fill] (0..1) of it is yellow at [level]. A straight line through
     * the designer's 30/50/70/90 % files: rows 101.5/80.5/60.5/39.5 at levels 1–10, and 105.5/84.7/64.3/41.5 at level 0,
     * whose bolt sits lower. At 0 the line drops below the box, so all of the bolt is empty.
     */
    fun lineRow(level: Int, fill: Float): Float {
        val f = fill.coerceIn(0f, 1f)
        if (f <= 0f) return BOTTOM
        val empty = if (level == 0) 137.7f else 132.3f
        val full = if (level == 0) 31.5f else 29.3f
        return empty - f * (empty - full)
    }
}

/**
 * The ring above the second character's head as the same gauge (spec D-42). The team's v05 clips come in six steps
 * (0·30·50·70·90·100 %) that differ only there: the whole ring goes from half-transparent white to gold, close to a
 * straight line in between (the designer's 30/50/70/90 % files sit 0.33/0.53/0.73/0.91 of the way). The app ships the
 * gold one (`_halo100`) and moves it toward the empty look at run time (app `BoltShade`).
 */
object HaloGauge {
    /**
     * The box the ring stays in, in canvas px (right and bottom excluded): measured on every third frame of the level
     * 0, 3, 6 and 10 clips, the ring keeps to rows 54..117 and columns 218..492 and the head stays below row 129, so
     * nothing else is inside.
     */
    const val LEFT = 200f
    const val TOP = 40f
    const val RIGHT = 520f
    const val BOTTOM = 124f
}
