package com.zzandrive.core.verification

import com.zzandrive.data.api.GeoPoint

/**
 * spec D-28 기회비용: "추천 장소를 실제로 지나갔지만 주유·충전하지 않은 경우" (기획서 p.7·p.14). Passing means
 * coming within [enterM] of the recommended station and then leaving beyond [exitM] with no refuel/charge in between.
 * The gap between the two radii keeps GPS jitter at the edge from counting as a pass.
 */
class PassDetector(private val enterM: Double = StationMatcher.DEFAULT_RADIUS_M, private val exitM: Double = 500.0) {
    private var recId: String? = null
    private var station: GeoPoint? = null
    private var entered = false

    /** The recommendation being watched, if any. */
    val watching: String? get() = recId

    fun watch(recId: String, station: GeoPoint) {
        this.recId = recId
        this.station = station
        entered = false
    }

    fun clear() {
        recId = null
        station = null
        entered = false
    }

    /** Returns the recommendation id once the car has passed the station without refilling, then stops watching. */
    fun onLocation(at: GeoPoint, refilling: Boolean): String? {
        val id = recId ?: return null
        val s = station ?: return null
        if (refilling) return null
        val d = StationMatcher.distanceM(at, s)
        if (d <= enterM) {
            entered = true
        } else if (entered && d > exitM) {
            clear()
            return id
        }
        return null
    }
}
