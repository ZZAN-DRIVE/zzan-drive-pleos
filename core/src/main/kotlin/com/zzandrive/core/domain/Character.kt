package com.zzandrive.core.domain

/** One row of the level table (spec 7.1). Amounts are in won. */
data class LevelEntry(val level: Int, val food: String, val pose: String, val achievedWon: Long)

/**
 * The level table is configuration data, not logic (spec 7.1). The next target of level n is
 * achieved(n+1) − achieved(n); the top level has no target.
 */
class LevelTable(val entries: List<LevelEntry>) {
    init {
        require(entries.isNotEmpty()) { "empty level table" }
        entries.forEachIndexed { i, e -> require(e.level == i) { "levels must be 0..n in order" } }
        require(entries.first().achievedWon == 0L) { "level 0 must start at 0 won" }
        entries.zipWithNext().forEach { (a, b) -> require(b.achievedWon > a.achievedWon) { "achieved amounts must increase" } }
    }

    val maxLevel: Int get() = entries.last().level
    fun entry(level: Int): LevelEntry = entries[level]
    fun food(level: Int): String = entries[level].food

    /** Amount to collect within [level] to reach the next one; null at the top level. */
    fun target(level: Int): Long? = if (level >= maxLevel) null else entries[level + 1].achievedWon - entries[level].achievedWon

    companion object {
        /** 기획서 3-4 레벨표 (금액은 시연·사용자 테스트 후 조정 예정). */
        val DEFAULT = LevelTable(
            listOf(
                LevelEntry(0, "빈 지갑", "빈 지갑을 거꾸로 털고 있음", 0),
                LevelEntry(1, "삼각김밥", "삼각김밥 포장을 뜯어 먹음", 1_500),
                LevelEntry(2, "아메리카노", "테이크아웃 컵을 들고 마심", 4_000),
                LevelEntry(3, "짜장면", "그릇째 들고 면을 후루룩", 8_000),
                LevelEntry(4, "파스타", "포크로 면을 돌돌 말아 먹음", 15_000),
                LevelEntry(5, "치킨", "닭다리를 양손에 들고 뜯음", 26_000),
                LevelEntry(6, "족발", "족발 한 접시를 앞에 두고 뜯음", 42_000),
                LevelEntry(7, "스테이크", "나이프와 포크로 썰어 먹음", 66_000),
                LevelEntry(8, "한우", "불판에 소고기를 구워 먹음", 100_000),
                LevelEntry(9, "랍스터", "빨간 랍스터 한 마리를 통째로 들고 먹음", 150_000),
                LevelEntry(10, "킹크랩", "자기 몸보다 큰 킹크랩 다리를 뜯음", 250_000),
            ),
        )
    }
}

/** spec 7.2: only level and level accrual are stored; everything else is derived. */
data class CharacterState(val level: Int, val accrualWon: Long) {
    init {
        require(level >= 0) { "level < 0" }
        require(accrualWon >= 0) { "accrual < 0" }
    }

    companion object {
        val START = CharacterState(0, 0)
    }
}

data class SavingResult(val state: CharacterState, val levelsReached: List<Int>)

data class PenaltyResult(val state: CharacterState, val fullWon: Long, val appliedWon: Long)

class CharacterProgress(private val table: LevelTable = LevelTable.DEFAULT) {

    /** R-40, R-41: add, then level up repeatedly; the overflow carries into the next level. */
    fun applySaving(state: CharacterState, amountWon: Long): SavingResult {
        require(amountWon >= 0) { "saving must be >= 0" }
        var level = state.level
        var accrual = state.accrualWon + amountWon
        val reached = mutableListOf<Int>()
        while (true) {
            val target = table.target(level) ?: break // level 10 keeps accumulating (D-07)
            if (accrual < target) break
            accrual -= target
            level += 1
            reached += level
        }
        return SavingResult(CharacterState(level, accrual), reached)
    }

    /** R-42..R-44: penalties reduce the level accrual only, never below 0; the level never drops. */
    fun applyPenalty(state: CharacterState, amountWon: Long): PenaltyResult {
        require(amountWon >= 0) { "penalty must be >= 0" }
        val applied = minOf(amountWon, state.accrualWon)
        return PenaltyResult(CharacterState(state.level, state.accrualWon - applied), amountWon, applied)
    }

    fun totalSaved(state: CharacterState): Long = table.entry(state.level).achievedWon + state.accrualWon

    /** 레벨업까지 남은 금액; null at the top level. */
    fun remainingToNext(state: CharacterState): Long? = table.target(state.level)?.let { it - state.accrualWon }

    /** 레벨 적립액 ÷ 레벨업 목표액, 0..1; 1 at the top level. The 다음 레벨 ring and the bolt above the character (D-40). */
    fun levelShare(state: CharacterState): Float =
        table.target(state.level)?.let { (state.accrualWon.toFloat() / it).coerceIn(0f, 1f) } ?: 1f

    val maxLevel: Int get() = table.maxLevel
    fun food(level: Int): String = table.food(level)
    fun nextFood(level: Int): String? = if (level >= table.maxLevel) null else table.food(level + 1)
}

/**
 * Level-up scenes play only while parked (R-33, R-46). Level-ups reached while driving wait in a queue:
 * everything above the last celebrated level up to the current one.
 */
object CelebrationPolicy {
    fun pending(lastCelebratedLevel: Int, currentLevel: Int, parked: Boolean): List<Int> =
        if (!parked || currentLevel <= lastCelebratedLevel) emptyList() else ((lastCelebratedLevel + 1)..currentLevel).toList()
}
