package com.zzandrive.core.motion

/**
 * What a clip is for. Looping clips are the resting state of a level; the others play once and
 * hand back to it (spec 7.1 레벨표 "캐릭터 모습", D-18).
 */
enum class ClipKind(val loops: Boolean) {
    IDLE(true),
    HOLD(true),
    SAD(true),
    EAT(false),
    LEVEL_UP(false),
    HAPPY(false),
}

/** One animation file. [level] is set for food clips (eat/hold) only. */
data class Clip(val path: String, val kind: ClipKind, val level: Int? = null) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * The character's clips and the order they play in. The team's art is one camera for every clip,
 * so a screen swaps files in one place. Roles come from the file names:
 *
 * - `<any>_idle.webp`, `<any>_levelup.webp`, `<any>_happy.webp`, `<any>_sad.webp`
 * - `<any>_lv<N>_<food>_eat.webp` (plays once), `<any>_lv<N>_<food>_hold.webp` (loops)
 *
 * Other names are ignored, so new levels and the second character drop in without code changes.
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

    /** The loop shown at [level]: holding that level's food, otherwise the plain idle. */
    fun base(level: Int): Clip? = hold(level) ?: idle()

    /** Level-up scene: the celebration, then eating the new level's food when that clip exists. */
    fun levelUp(level: Int): List<Clip> = listOfNotNull(find(ClipKind.LEVEL_UP), eat(level))

    /** A tap on the character: eat the current food, or a happy hop when the level has no food clip. */
    fun tap(level: Int): Clip? = eat(level) ?: find(ClipKind.HAPPY)

    /** After a settlement: happy when a saving was credited, sad when it was not. */
    fun reaction(happy: Boolean): Clip? = find(if (happy) ClipKind.HAPPY else ClipKind.SAD)

    companion object {
        val EMPTY = CharacterClips(emptyList(), borrowed = false)

        private val FOOD = Regex("""^.+_lv(\d{1,2})_[a-z0-9]+_(eat|hold)\.webp$""")
        private val PLAIN = Regex("""^.+_(idle|levelup|happy|sad)\.webp$""")

        /** Clips found in [dir] (an asset folder) among [names]. Sorted, so duplicates resolve the same way every time. */
        fun parse(dir: String, names: Collection<String>): CharacterClips {
            val folder = dir.trimEnd('/')
            val clips = names.sorted().mapNotNull { name ->
                val path = "$folder/$name"
                FOOD.matchEntire(name)?.let { m ->
                    val kind = if (m.groupValues[2] == "eat") ClipKind.EAT else ClipKind.HOLD
                    return@mapNotNull Clip(path, kind, m.groupValues[1].toInt())
                }
                PLAIN.matchEntire(name)?.let { m ->
                    val kind = when (m.groupValues[1]) {
                        "idle" -> ClipKind.IDLE
                        "levelup" -> ClipKind.LEVEL_UP
                        "happy" -> ClipKind.HAPPY
                        else -> ClipKind.SAD
                    }
                    Clip(path, kind)
                }
            }
            return CharacterClips(clips, borrowed = false)
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
