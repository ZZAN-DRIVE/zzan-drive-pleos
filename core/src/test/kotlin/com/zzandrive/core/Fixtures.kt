package com.zzandrive.core

import com.zzandrive.core.domain.bd
import com.zzandrive.data.api.DataOrigin
import com.zzandrive.data.api.EnergyType
import com.zzandrive.data.api.GeoPoint
import com.zzandrive.data.api.StationOffer
import java.math.BigDecimal
import java.math.RoundingMode

/** The plan's examples (기획서 3-1, 시연 가정) used across tests. */
object Fixtures {
    val A_POINT = GeoPoint(37.4140, 127.0990)
    val B_POINT = GeoPoint(37.4005, 127.1080)

    fun offer(id: String, type: EnergyType, price: String, detourKm: String, at: GeoPoint? = null) =
        StationOffer(id, "$id ${if (type.isElectric) "충전소" else "주유소"}", type, bd(price), bd(detourKm), location = at, origin = DataOrigin.PLAN_EXAMPLE)

    /** TV-01: A 2 km @1,700, B 12 km @1,650, 10 km/L, 50 L. */
    val fuelA = offer("A", EnergyType.GASOLINE, "1700", "2", A_POINT)
    val fuelB = offer("B", EnergyType.GASOLINE, "1650", "12", B_POINT)

    /** TV-02: A 2 km @350, B 4 km @290, 5 km/kWh, 40 kWh. */
    val evA = offer("A", EnergyType.ELECTRIC, "350", "2", A_POINT)
    val evB = offer("B", EnergyType.ELECTRIC, "290", "4", B_POINT)

    fun BigDecimal.cents(): BigDecimal = setScale(2, RoundingMode.HALF_UP)
}
