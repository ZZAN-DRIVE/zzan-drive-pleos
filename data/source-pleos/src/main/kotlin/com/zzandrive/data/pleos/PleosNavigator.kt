package com.zzandrive.data.pleos

import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.zzandrive.data.api.GeoPoint

/**
 * Hands an approved recommendation to the Pleos navigation app (spec R-20: only after the driver's "응").
 * On the 2.1.0 emulator the navigation app then calculates and shows a route to that place (it draws no base map
 * there). This app does not read the route's distance or time back.
 */
class PleosNavigator(context: Context) {
    private val navi = NaviHelper(context.applicationContext)

    @Volatile var connected: Boolean = false
        private set

    @Volatile var guiding: Boolean = false
        private set

    private val listener = object : NaviHelperEventListener {
        override fun onNavigationStatus(status: NavigationStatus) {
            guiding = status.name.contains("ROUTE")
            Log.i(TAG, "navigation status $status")
        }

        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            Log.i(TAG, "waypoint ${waypointChangedInfo.cmdType} ${waypointChangedInfo.index}")
        }
    }

    fun start() {
        runCatching {
            navi.addListener(listener)
            navi.initialize(
                onConnected = {
                    connected = true
                    Log.i(TAG, "NaviHelper connected")
                },
                onFailed = { Log.w(TAG, "NaviHelper connect failed: ${it.message}") },
            )
        }.onFailure { Log.w(TAG, "NaviHelper init failed: ${it.message}") }
    }

    /**
     * While a route is being guided, adds [point] as the first waypoint so the trip's destination stays (기획서 p.9
     * "경유지로 추가"); otherwise sets it as the destination. Returns false when the request could not be sent.
     */
    fun navigateTo(id: String, name: String, point: GeoPoint, address: String = name): Boolean {
        if (!connected) {
            Log.w(TAG, "navigateTo before NaviHelper connected")
            return false
        }
        if (guiding) {
            return runCatching {
                navi.addWaypoint(
                    RequestWaypointInfo(
                        latitude = point.lat,
                        longitude = point.lon,
                        poiName = name,
                        waypointIndex = WaypointIndex.FIRST,
                        poiId = id,
                        address = address,
                        poiSubId = "0",
                    ),
                )
                Log.i(TAG, "addWaypoint $name (${point.lat}, ${point.lon})")
            }.onFailure { Log.w(TAG, "addWaypoint failed: ${it.message}") }.isSuccess
        }
        return runCatching {
            navi.requestRoute(
                RouteInfo(
                    latitude = point.lat,
                    longitude = point.lon,
                    displayLongitude = null,
                    displayLatitude = null,
                    poiName = name,
                    poiId = id,
                    poiSubId = "0",
                    address = address,
                ),
            )
            Log.i(TAG, "requestRoute $name (${point.lat}, ${point.lon})")
        }.onFailure { Log.w(TAG, "requestRoute failed: ${it.message}") }.isSuccess
    }

    fun release() {
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
        connected = false
    }

    private companion object {
        const val TAG = "ZzanNavi"
    }
}
