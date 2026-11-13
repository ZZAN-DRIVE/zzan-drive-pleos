package com.zzandrive.app

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import com.zzandrive.app.runtime.FileLedgerStore
import com.zzandrive.app.runtime.HandlerScheduler
import com.zzandrive.app.runtime.Listener
import com.zzandrive.app.runtime.MemoryLedgerStore
import com.zzandrive.app.runtime.Navigator
import com.zzandrive.app.runtime.Prefs
import com.zzandrive.app.runtime.Speaker
import com.zzandrive.app.runtime.ZzanController
import com.zzandrive.app.voice.PleosSttListener
import com.zzandrive.app.voice.PleosTtsSpeaker
import com.zzandrive.core.voice.Persona
import com.zzandrive.data.api.EnergyType
import com.zzandrive.data.api.RouteSource
import com.zzandrive.data.mock.FixturePlaceSource
import com.zzandrive.data.mock.FixtureRouteSource
import com.zzandrive.data.mock.FixtureStationSource
import com.zzandrive.data.mock.ReplayVehicleSource
import com.zzandrive.data.mock.Scenarios
import com.zzandrive.data.pleos.PleosNavigator
import com.zzandrive.data.pleos.PleosVehicleSource

/**
 * Wires the runtime at Application scope: the activity is blocked by the system while driving,
 * but detection, settlement and speech must keep running (spec R-33).
 */
class ZzanApp : Application() {
    /** One TTS engine for the whole app. */
    val speaker: Speaker by lazy { PleosTtsSpeaker(this) }

    /** One speech recognizer for the whole app (ON_DEVICE). */
    val listener: Listener by lazy { PleosSttListener(this) }

    /** Live graph: real vehicle data (Pleos SDK) + plan-example prices. Started on first use. */
    val live: LiveGraph by lazy { LiveGraph(this).also { it.start() } }

    /** Demo graph (시연 모드 · Mock 데이터), created per demo run. */
    var demo: DemoGraph? = null
        private set

    fun startDemo(scenario: DemoScenario): DemoGraph {
        live.controller.endQuestion() // the live runtime stops listening: an answer meant for the demo must not reach it
        demo?.stop()
        return DemoGraph(this, scenario).also {
            demo = it
            it.start()
        }
    }

    fun stopDemo() {
        demo?.stop()
        demo = null
    }

    val locationGranted: Boolean
        get() = checkSelfPermission(FUSED_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
    }
}

class LiveGraph(private val app: ZzanApp) {
    private val scheduler = HandlerScheduler()

    /** The live runtime keeps watching the car during a demo but stays quiet, so the two never talk over each other. */
    private val speaker = object : Speaker {
        override fun speak(text: String) {
            if (app.demo == null) app.speaker.speak(text) else Log.i(ZzanController.TAG, "MUTED (demo running) $text")
        }

        override fun voice(persona: Persona) {
            if (app.demo == null) app.speaker.voice(persona)
        }

        override fun speakThen(text: String, onDone: (played: Boolean) -> Unit) {
            if (app.demo == null) app.speaker.speakThen(text, onDone) else { speak(text); onDone(false) }
        }

        override fun speakWhile(text: String, isCurrent: () -> Boolean, onDone: ((played: Boolean) -> Unit)?) {
            if (app.demo == null) {
                // A live line still waiting when a demo starts is dropped by the demo's refresh.
                app.speaker.speakWhile(text, { app.demo == null && isCurrent() }, onDone)
            } else {
                Log.i(ZzanController.TAG, "MUTED (demo running) $text")
                onDone?.invoke(false)
            }
        }

        override fun refresh() = app.speaker.refresh()
    }

    /** The live runtime does not listen while a demo runs. */
    private val listener = object : Listener {
        override val available: Boolean get() = app.demo == null && app.listener.available
        override fun listen(windowMs: Long, onHeard: (String?) -> Unit) = app.listener.listen(windowMs, onHeard)
        override fun recognize(pcm: ByteArray, onHeard: (String?) -> Unit) = app.listener.recognize(pcm, onHeard)
        override fun stop() = app.listener.stop()
    }

    val vehicle = PleosVehicleSource(app)
    private val navi = PleosNavigator(app)
    val controller = ZzanController(
        demo = false,
        vehicle = vehicle,
        stations = FixtureStationSource(), // plan-example prices for now (labelled); real price APIs next
        navigator = Navigator { id, name, point -> navi.navigateTo(id, name, point) },
        speaker = speaker,
        store = FileLedgerStore(app),
        prefs = Prefs(app),
        clock = scheduler,
        scheduler = scheduler,
        modeReason = { vehicle.modeReason },
        places = FixturePlaceSource(), // saved places: demo data until the Pleos bookmark API is checked on the emulator
        routeActive = { navi.guiding },
        listener = listener,
    )

    fun start() {
        vehicle.start()
        navi.start()
        if (app.locationGranted) vehicle.startLocation()
        controller.start()
    }

    fun onLocationGranted() = vehicle.startLocation()
}

enum class DemoScenario(val label: String, val energyType: EnergyType) {
    B_CHARGE("시나리오 B · 전기차 충전", EnergyType.ELECTRIC),
    A_FUEL("시나리오 A · 내연기관 주유", EnergyType.GASOLINE),
}

/** Same controller, Mock vehicle replay, in-memory ledger, separate prefs. Nothing touches the live ledger. */
class DemoGraph(app: ZzanApp, val scenario: DemoScenario) {
    private val scheduler = HandlerScheduler()

    /** The demo's own settings, back to the defaults at every start (a threshold changed in an earlier demo would change it). */
    private val prefs = Prefs(app, "zzan-demo").apply { resetSettings() }
    private val replay = ReplayVehicleSource(scenario.energyType, scheduler, scheduler)
    val controller = ZzanController(
        demo = true,
        vehicle = replay,
        stations = FixtureStationSource(),
        navigator = Navigator { _, name, _ -> Log.i(ZzanController.DEMO_TAG, "NAVIGATE (demo) to $name"); true },
        speaker = app.speaker,
        store = MemoryLedgerStore(),
        prefs = prefs,
        clock = scheduler,
        scheduler = scheduler,
        modeReason = { scenario.label },
        afterApprove = {
            replay.play(if (scenario == DemoScenario.B_CHARGE) Scenarios.evChargeAtB(startKwh = EV_PARKED_KWH) else Scenarios.fuelRefillAtB())
        },
        places = FixturePlaceSource(),
        routes = if (scenario == DemoScenario.A_FUEL) FixtureRouteSource() else RouteSource { null },
        listener = app.listener,
        // A: after the departure comparison the demo moves to another day, at a rest stop with 6 L left (scenario A ③).
        afterRoutes = {
            if (scenario == DemoScenario.A_FUEL) {
                controllerNote("다른 날 · 강릉 가는 길 휴게소, 연료 10%")
                replay.play(Scenarios.fuelLow())
            }
        },
    )

    /**
     * B (기획서 p.17): parked at 45 %, the driver taps [충전소 찾기] and answers the place and the amount.
     * A (p.16~17): before departure to 강릉 with 40 L, the routes are compared (step 1). After [확인], at a rest stop
     * with 6 L: the level is low, the character asks the amount, the destination is out of range (③), so it compares
     * the stations the car can still reach.
     */
    fun start() {
        controller.start()
        if (scenario == DemoScenario.B_CHARGE) {
            controller.seedScenarioB()
            replay.play(Scenarios.evParked())
        } else {
            controller.seedScenarioA()
            replay.play(Scenarios.fuelDeparture())
            controller.prepareRoutes()
            routesTimer = scheduler.schedule(600) { controller.compareRoutes() } // after the departure level arrives (reachability)
        }
    }

    private companion object {
        /** 45 % of 77.4 kWh, where [Scenarios.evParked] leaves the battery. */
        const val EV_PARKED_KWH = 34.83
    }

    private var routesTimer: com.zzandrive.data.api.Cancellable? = null

    private fun controllerNote(text: String) {
        controller.note(text)
    }

    fun stop() {
        routesTimer?.cancel()
        replay.stop()
        controller.stop()
    }
}
