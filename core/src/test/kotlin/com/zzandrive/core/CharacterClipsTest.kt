package com.zzandrive.core

import com.zzandrive.core.motion.CharacterClips
import com.zzandrive.core.motion.ClipKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CharacterClipsTest {
    /** The clips as packaged in app/src/main/assets/character/zzandori. */
    private val delivered = listOf(
        "jjandol_happy.webp", "jjandol_idle.webp", "jjandol_levelup.webp",
        "jjandol_lv1_samgak_eat.webp", "jjandol_lv1_samgak_hold.webp",
        "jjandol_lv3_jjajang_eat.webp", "jjandol_lv3_jjajang_hold.webp", "jjandol_sad.webp",
    )
    private val clips = CharacterClips.parse("character/zzandori", delivered)

    @Test
    fun `every delivered file gets a role`() {
        assertEquals(8, clips.clips.size)
        assertEquals("character/zzandori/jjandol_idle.webp", clips.idle()?.path)
        assertFalse(clips.borrowed)
    }

    @Test
    fun `resting clip per level is holding that level's food, else idle`() {
        assertEquals(
            listOf(
                "jjandol_idle.webp", "jjandol_lv1_samgak_hold.webp", "jjandol_idle.webp",
                "jjandol_lv3_jjajang_hold.webp", "jjandol_idle.webp",
            ),
            listOf(0, 1, 2, 3, 10).map { clips.base(it)?.name },
        )
    }

    @Test
    fun `level-up plays the celebration, then eats the new food when that clip exists`() {
        assertEquals(listOf("jjandol_levelup.webp", "jjandol_lv1_samgak_eat.webp"), clips.levelUp(1).map { it.name }) // 시나리오 A
        assertEquals(listOf("jjandol_levelup.webp", "jjandol_lv3_jjajang_eat.webp"), clips.levelUp(3).map { it.name }) // 시나리오 B
        assertEquals(listOf("jjandol_levelup.webp"), clips.levelUp(2).map { it.name }) // no 아메리카노 clip yet
    }

    @Test
    fun `tap eats the current food, or hops when the level has no food clip`() {
        assertEquals("jjandol_lv1_samgak_eat.webp", clips.tap(1)?.name)
        assertEquals("jjandol_happy.webp", clips.tap(0)?.name)
        assertEquals("jjandol_happy.webp", clips.reaction(happy = true)?.name)
        assertEquals("jjandol_sad.webp", clips.reaction(happy = false)?.name)
    }

    @Test
    fun `loops follow the clip role, as delivered`() {
        val kinds = clips.clips.associate { it.name to it.kind }
        assertTrue(kinds.getValue("jjandol_idle.webp").loops)
        assertTrue(kinds.getValue("jjandol_lv3_jjajang_hold.webp").loops)
        assertTrue(kinds.getValue("jjandol_sad.webp").loops)
        assertFalse(kinds.getValue("jjandol_lv1_samgak_eat.webp").loops)
        assertFalse(kinds.getValue("jjandol_levelup.webp").loops)
        assertFalse(kinds.getValue("jjandol_happy.webp").loops)
        assertEquals(ClipKind.EAT, kinds.getValue("jjandol_lv3_jjajang_eat.webp"))
    }

    @Test
    fun `other names are ignored`() {
        val other = CharacterClips.parse("character/x", listOf("README.txt", "jjandol_idle.png", "idle.webp", "jjandol_lv1_eat.webp"))
        assertTrue(other.isEmpty)
        assertNull(other.base(0))
        assertTrue(other.levelUp(1).isEmpty())
    }

    @Test
    fun `the second character is recognised whatever its prefix`() {
        val sun = CharacterClips.parse("character/zzansuni/", listOf("jjansun_idle.webp", "jjansun_lv2_americano_hold.webp", "jjansun_lv10_kingcrab_eat.webp"))
        assertEquals("character/zzansuni/jjansun_lv2_americano_hold.webp", sun.base(2)?.path)
        assertEquals("jjansun_idle.webp", sun.base(1)?.name)
        assertEquals(listOf("jjansun_lv10_kingcrab_eat.webp"), sun.levelUp(10).map { it.name }) // no level-up clip: just eat
    }

    @Test
    fun `a character without art borrows the other one's clips, never a mix`() {
        val empty = CharacterClips.parse("character/zzansuni", emptyList())
        val borrowed = CharacterClips.resolve(empty, clips)
        assertTrue(borrowed.borrowed)
        assertEquals("jjandol_lv1_samgak_hold.webp", borrowed.base(1)?.name)

        val partial = CharacterClips.parse("character/zzansuni", listOf("jjansun_idle.webp"))
        val own = CharacterClips.resolve(partial, clips)
        assertFalse(own.borrowed)
        assertEquals("jjansun_idle.webp", own.base(1)?.name) // its own idle, not the other character's samgak

        assertTrue(CharacterClips.resolve(empty, CharacterClips.EMPTY).isEmpty)
    }
}
