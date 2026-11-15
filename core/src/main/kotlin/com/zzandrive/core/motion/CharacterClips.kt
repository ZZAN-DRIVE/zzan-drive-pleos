package com.zzandrive.core.motion

/**
 * What a clip is for (spec 7.1 레벨표 "캐릭터 모습", D-18). Whether it loops is up to the player: the resting clip of a
 * level loops, the others play once and hand back to it.
 */
enum class ClipKind {
    IDLE,
    HOLD,
    SAD,
    EAT,
    LEVEL_UP,
    HAPPY,
}

/**
 * One animation file. [level] is set for food clips (eat/hold) only. [bolt] is how full the bolt above the head is drawn
 * in the file, in % (`_bolt100`), when the name says so (spec D-40); [halo] is the same for the ring above the second
 * character's head (`_halo100`, spec D-42).
 */
data class Clip(val path: String, val kind: ClipKind, val level: Int? = null, val bolt: Int? = null, val halo: Int? = null) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * The character's clips and the order they play in. The team's art is one camera for every clip,
 * so a screen swaps files in one place. Roles come from the file names:
 *
 * - `<any>_idle.webp`, `<any>_levelup.webp`, `<any>_happy.webp`, `<any>_sad.webp`
 * - `<any>_lv<N>_<food>_eat.webp`, `<any>_lv<N>_<food>_hold.webp`
 * - the same with the art's bolt and version, `<any>_lv<N>_<food>_eat_bolt<NNN>_v<N>.webp`, and a level's clip without
 *   a verb, `<any>_lv<N>_<food>_bolt<NNN>[_v<N>].webp` (level 0's empty wallet), which counts as its eating clip
 * - the second character's art names its ring instead of a bolt: `_halo<NNN>` in the same places
 *
 * Several files for one role (bolt steps, versions) resolve to the fullest bolt, then the newest version. Other names are
 * ignored, so new levels and the second character drop in without code changes.
 */
class CharacterClips private constructor(
    val clips: List<Clip>,
    /** True when these are another character's clips because the chosen one has no art yet. */
    val borrowed: Boolean,
) {
    val isEmpty: Boolean get() = clips.isEmpty()

    private fun find(kind: ClipKind, level: Int? = null): Clip? = clips.firstOrNull { it.kind == kind && it.level == level }

    fun idle(): Clip? = find(ClipKind.IDLE)
    fun hold(level: Int): Clip? = find(ClipKind.HOLD, level)
    fun eat(level: Int): Clip? = find(ClipKind.EAT, level)

    /** The loop shown at [level]: holding that level's food, else eating it (the v23 art eats in a loop), else the plain idle. */
    fun base(level: Int): Clip? = hold(level) ?: eat(level) ?: idle()

    /**
     * Level-up scene: the celebration, then eating the new food once when the level rests on holding it. When the level
     * rests on eating, the celebration hands straight over to that loop.
     */
    fun levelUp(level: Int): List<Clip> = listOfNotNull(find(ClipKind.LEVEL_UP), eat(level).takeIf { hold(level) != null })

    /** A tap on the character: eat the food once when the level rests on holding it, otherwise a happy hop. */
    fun tap(level: Int): Clip? = eat(level).takeIf { hold(level) != null } ?: find(ClipKind.HAPPY)

    /** After a settlement: happy when a saving was credited, sad when it was not. */
    fun reaction(happy: Boolean): Clip? = find(if (happy) ClipKind.HAPPY else ClipKind.SAD)

    /**
     * A picture for choosing the character: the plain idle, else the first food (level 0 rests on the empty wallet, with
     * a sad face).
     */
    fun portrait(): Clip? = idle() ?: eat(1) ?: base(0)

    companion object {
        val EMPTY = CharacterClips(emptyList(), borrowed = false)

        private val FOOD = Regex("""^.+_lv(\d{1,2})_[a-z0-9]+_(eat|hold)(?:_(bolt|halo)(\d{3}))?(?:_v(\d+))?\.webp$""")
        private val LEVEL = Regex("""^.+_lv(\d{1,2})_[a-z0-9]+_(bolt|halo)(\d{3})(?:_v(\d+))?\.webp$""")
        private val PLAIN = Regex("""^.+_(idle|levelup|happy|sad)\.webp$""")

        /** Fullest bolt first, then the newest version, then by name: duplicates resolve the same way every time. */
        private val PREFERRED = compareByDescending<Pair<Clip, Int>> { it.first.bolt ?: it.first.halo ?: -1 }
            .thenByDescending { it.second }
            .thenBy { it.first.name }

        /** Clips found in [dir] (an asset folder) among [names]. */
        fun parse(dir: String, names: Collection<String>): CharacterClips {
            val folder = dir.trimEnd('/')
            val found = names.mapNotNull { name ->
                val path = "$folder/$name"
                FOOD.matchEntire(name)?.let { m ->
                    val kind = if (m.groupValues[2] == "eat") ClipKind.EAT else ClipKind.HOLD
                    return@mapNotNull food(path, kind, m.groupValues[1], m.groupValues[3], m.groupValues[4]) to version(m.groupValues[5])
                }
                LEVEL.matchEntire(name)?.let { m ->
                    return@mapNotNull food(path, ClipKind.EAT, m.groupValues[1], m.groupValues[2], m.groupValues[3]) to version(m.groupValues[4])
                }
                PLAIN.matchEntire(name)?.let { m ->
                    val kind = when (m.groupValues[1]) {
                        "idle" -> ClipKind.IDLE
                        "levelup" -> ClipKind.LEVEL_UP
                        "happy" -> ClipKind.HAPPY
                        else -> ClipKind.SAD
                    }
                    Clip(path, kind) to 0
                }
            }
            return CharacterClips(found.sortedWith(PREFERRED).map { it.first }, borrowed = false)
        }

        private fun version(digits: String): Int = digits.toIntOrNull() ?: 0

        /** A food clip whose name may say how full its gauge is drawn: [gauge] is "bolt", "halo" or empty. */
        private fun food(path: String, kind: ClipKind, level: String, gauge: String, percent: String): Clip {
            val p = percent.toIntOrNull()
            return Clip(path, kind, level.toInt(), bolt = p.takeIf { gauge == "bolt" }, halo = p.takeIf { gauge == "halo" })
        }

        /**
         * The chosen character's clips. When it has none yet, [fallback]'s clips marked [borrowed]
         * (the two characters keep the same level and money, R-45; only the picture is borrowed).
         * Clips are never mixed between characters.
         */
        fun resolve(own: CharacterClips, fallback: CharacterClips): CharacterClips =
            if (!own.isEmpty || fallback.isEmpty) own else CharacterClips(fallback.clips, borrowed = true)
    }
}
