package com.zzandrive.core

import com.zzandrive.core.motion.CharacterClips
import com.zzandrive.core.motion.ClipKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class CharacterClipsTest {
    /** The clips as packaged in app/src/main/assets/character/zzandori: the team's v23 eating loops (bolt full) and four earlier clips. */
    private val delivered = listOf(
        "jjandol_happy.webp", "jjandol_idle.webp", "jjandol_levelup.webp", "jjandol_sad.webp",
        "jjandol_lv00_wallet_bolt100_v23.webp", "jjandol_lv01_samgak_eat_bolt100_v23.webp",
        "jjandol_lv02_americano_eat_bolt100_v23.webp", "jjandol_lv03_jjajang_eat_bolt100_v23.webp",
        "jjandol_lv04_pasta_eat_bolt100_v23.webp", "jjandol_lv05_chicken_eat_bolt100_v23.webp",
        "jjandol_lv06_jokbal_eat_bolt100_v23.webp", "jjandol_lv07_steak_eat_bolt100_v23.webp",
        "jjandol_lv08_hanwoo_eat_bolt100_v23.webp", "jjandol_lv09_lobster_eat_bolt100_v23.webp",
        "jjandol_lv10_kingcrab_eat_bolt100_v23.webp",
    )
    private val clips = CharacterClips.parse("character/zzandori", delivered)

    /** The first delivery: each food eaten once, then held in a loop (levels 1 and 3). Art made that way keeps that order. */
    private val legacy = CharacterClips.parse(
        "character/zzandori",
        listOf(
            "jjandol_happy.webp", "jjandol_idle.webp", "jjandol_levelup.webp",
            "jjandol_lv1_samgak_eat.webp", "jjandol_lv1_samgak_hold.webp",
            "jjandol_lv3_jjajang_eat.webp", "jjandol_lv3_jjajang_hold.webp", "jjandol_sad.webp",
        ),
    )

    @Test
    fun `every delivered file gets a role`() {
        assertEquals(15, clips.clips.size)
        assertEquals("character/zzandori/jjandol_idle.webp", clips.idle()?.path)
        assertEquals("character/zzandori/jjandol_lv00_wallet_bolt100_v23.webp", clips.eat(0)?.path)
        assertFalse(clips.borrowed)
        for (level in 0..10) {
            assertEquals(100, clips.eat(level)?.bolt, "level $level")
            assertNull(clips.hold(level), "level $level")
        }
    }

    @Test
    fun `each level rests on eating its own food, level 0 on the empty wallet`() {
        assertEquals(
            listOf(
                "jjandol_lv00_wallet_bolt100_v23.webp", "jjandol_lv01_samgak_eat_bolt100_v23.webp",
                "jjandol_lv03_jjajang_eat_bolt100_v23.webp", "jjandol_lv08_hanwoo_eat_bolt100_v23.webp",
                "jjandol_lv10_kingcrab_eat_bolt100_v23.webp",
            ),
            listOf(0, 1, 3, 8, 10).map { clips.base(it)?.name },
        )
    }

    @Test
    fun `level-up celebrates, then the new level's eating loop takes over`() {
        assertEquals(listOf("jjandol_levelup.webp"), clips.levelUp(1).map { it.name }) // 시나리오 A, then base(1)
        assertEquals(listOf("jjandol_levelup.webp"), clips.levelUp(3).map { it.name }) // 시나리오 B, then base(3)
    }

    @Test
    fun `tap hops while the level already eats in a loop`() {
        assertEquals("jjandol_happy.webp", clips.tap(0)?.name)
        assertEquals("jjandol_happy.webp", clips.tap(1)?.name)
        assertEquals("jjandol_happy.webp", clips.reaction(happy = true)?.name)
        assertEquals("jjandol_sad.webp", clips.reaction(happy = false)?.name)
    }

    @Test
    fun `the character chooser shows the plain idle, not the sad empty wallet`() {
        assertEquals("jjandol_idle.webp", clips.portrait()?.name)
        val noIdle = CharacterClips.parse("character/x", listOf("x_lv00_wallet_bolt100_v23.webp", "x_lv01_samgak_eat_bolt100_v23.webp"))
        assertEquals("x_lv01_samgak_eat_bolt100_v23.webp", noIdle.portrait()?.name) // no idle: the first food
        val walletOnly = CharacterClips.parse("character/x", listOf("x_lv00_wallet_bolt100_v23.webp"))
        assertEquals("x_lv00_wallet_bolt100_v23.webp", walletOnly.portrait()?.name)
    }

    @Test
    fun `art that holds the food keeps eating once, then holding`() {
        assertEquals(
            listOf(
                "jjandol_idle.webp", "jjandol_lv1_samgak_hold.webp", "jjandol_idle.webp",
                "jjandol_lv3_jjajang_hold.webp", "jjandol_idle.webp",
            ),
            listOf(0, 1, 2, 3, 10).map { legacy.base(it)?.name },
        )
        assertEquals(listOf("jjandol_levelup.webp", "jjandol_lv1_samgak_eat.webp"), legacy.levelUp(1).map { it.name })
        assertEquals(listOf("jjandol_levelup.webp", "jjandol_lv3_jjajang_eat.webp"), legacy.levelUp(3).map { it.name })
        assertEquals(listOf("jjandol_levelup.webp"), legacy.levelUp(2).map { it.name }) // no 아메리카노 clip
        assertEquals("jjandol_lv1_samgak_eat.webp", legacy.tap(1)?.name)
        assertEquals("jjandol_happy.webp", legacy.tap(0)?.name)
        assertNull(legacy.eat(1)?.bolt)
    }

    @Test
    fun `roles, levels and the bolt come from the names`() {
        val byName = clips.clips.associateBy { it.name }
        val wallet = byName.getValue("jjandol_lv00_wallet_bolt100_v23.webp")
        assertEquals(ClipKind.EAT, wallet.kind)
        assertEquals(0, wallet.level)
        assertEquals(100, wallet.bolt)
        val kingcrab = byName.getValue("jjandol_lv10_kingcrab_eat_bolt100_v23.webp")
        assertEquals(ClipKind.EAT, kingcrab.kind)
        assertEquals(10, kingcrab.level)
        assertEquals(ClipKind.IDLE, byName.getValue("jjandol_idle.webp").kind)
        assertEquals(ClipKind.LEVEL_UP, byName.getValue("jjandol_levelup.webp").kind)
        assertEquals(ClipKind.HAPPY, byName.getValue("jjandol_happy.webp").kind)
        assertEquals(ClipKind.SAD, byName.getValue("jjandol_sad.webp").kind)
        assertNull(byName.getValue("jjandol_idle.webp").level)
        assertNull(byName.getValue("jjandol_idle.webp").bolt)
    }

    @Test
    fun `the fullest bolt, then the newest version wins, in any order`() {
        val names = listOf(
            "jjandol_lv02_americano_eat_bolt050_v23.webp", "jjandol_lv02_americano_eat_bolt100_v22.webp",
            "jjandol_lv02_americano_eat_bolt000_v23.webp", "jjandol_lv02_americano_eat_bolt100_v23.webp",
            "jjandol_lv2_americano_eat.webp",
        )
        for (order in listOf(names, names.reversed(), names.shuffled(Random(7)))) {
            assertEquals("jjandol_lv02_americano_eat_bolt100_v23.webp", CharacterClips.parse("d", order).eat(2)?.name)
        }
    }

    @Test
    fun `other names are ignored`() {
        val other = CharacterClips.parse(
            "character/x",
            listOf(
                "README.txt", "jjandol_idle.png", "idle.webp", "jjandol_lv1_eat.webp", "jjandol_lv00_wallet.webp",
                "jjandol_lv01_samgak_eat_bolt1_v23.webp", "jjandol_lv01_samgak_eat_v23_bolt100.webp",
            ),
        )
        assertTrue(other.isEmpty)
        assertNull(other.base(0))
        assertTrue(other.levelUp(1).isEmpty())
    }

    @Test
    fun `the second character is recognised whatever its prefix`() {
        val sun = CharacterClips.parse("character/zzansuni/", listOf("jjansun_idle.webp", "jjansun_lv2_americano_hold.webp", "jjansun_lv10_kingcrab_eat.webp"))
        assertEquals("character/zzansuni/jjansun_lv2_americano_hold.webp", sun.base(2)?.path)
        assertEquals("jjansun_idle.webp", sun.base(1)?.name)
        assertEquals("jjansun_lv10_kingcrab_eat.webp", sun.base(10)?.name) // no hold clip: rests on eating
        assertTrue(sun.levelUp(10).isEmpty()) // no level-up clip either: straight to that loop
    }

    @Test
    fun `the second character's halo clips are its eating loops`() {
        val sun = CharacterClips.parse(
            "character/zzansuni",
            listOf(
                "jjansoon_lv00_wallet_halo100_v05.webp", "jjansoon_lv01_samgak_eat_halo100_v05.webp",
                "jjansoon_lv03_jjajang_eat_halo000_v05.webp", "jjansoon_lv03_jjajang_eat_halo100_v05.webp",
            ),
        )
        assertEquals("jjansoon_lv00_wallet_halo100_v05.webp", sun.base(0)?.name)
        assertEquals("jjansoon_lv03_jjajang_eat_halo100_v05.webp", sun.base(3)?.name)
        assertEquals(100, sun.base(3)?.halo)
        assertNull(sun.base(3)?.bolt)
        assertEquals("jjansoon_lv01_samgak_eat_halo100_v05.webp", sun.portrait()?.name) // no idle: the first food, not the empty wallet
        assertTrue(sun.levelUp(3).isEmpty())
        assertFalse(CharacterClips.resolve(sun, clips).borrowed)
    }

    @Test
    fun `a character without art borrows the other one's clips, never a mix`() {
        val empty = CharacterClips.parse("character/zzansuni", emptyList())
        val borrowed = CharacterClips.resolve(empty, clips)
        assertTrue(borrowed.borrowed)
        assertEquals("jjandol_lv01_samgak_eat_bolt100_v23.webp", borrowed.base(1)?.name)

        val partial = CharacterClips.parse("character/zzansuni", listOf("jjansun_idle.webp"))
        val own = CharacterClips.resolve(partial, clips)
        assertFalse(own.borrowed)
        assertEquals("jjansun_idle.webp", own.base(1)?.name) // its own idle, not the other character's samgak

        assertTrue(CharacterClips.resolve(empty, CharacterClips.EMPTY).isEmpty)
    }
}
