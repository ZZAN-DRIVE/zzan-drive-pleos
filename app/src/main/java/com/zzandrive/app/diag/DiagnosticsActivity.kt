package com.zzandrive.app.diag

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import android.app.Activity
import com.zzandrive.app.R
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.stt.listener.ResultListener as SttResultListener
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * Diagnostics screen: no product logic. It proves that each public
 * Pleos Connect SDK answers on this vehicle/emulator. scripts/verify-app.sh launches it in debug
 * builds (exported only in debug, see app/build.gradle.kts manifestPlaceholders).
 * Every status line separates "call returned" from "callback/data received".
 */
class DiagnosticsActivity : Activity() {
    private companion object {
        const val TAG = "JjanShell"
        const val PERM_LOCATION = "pleos.car.permission.FUSED_LOCATION"
        const val REQ_LOCATION = 7
        /** `adb shell am start ... --ez zzan.selftest true`: speak once, then listen 5 s (debug builds only). */
        const val EXTRA_SELFTEST = "zzan.selftest"
    }

    private lateinit var statusBox: LinearLayout
    private val rows = linkedMapOf<String, TextView>()

    private lateinit var vehicle: Vehicle
    private lateinit var navi: NaviHelper
    private lateinit var tts: TextToSpeech
    private lateinit var stt: SpeechToText
    private lateinit var location: FusedLocation
    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null

    private var alive = true
    private var started = false
    private var ttsReady = false
    private var sttReady = false
    private var locationInitialized = false
    private var fuelListener: FuelPercentListener? = null
    private var socListener: EvBatteryStateOfChargeListener? = null
    private var listening = false
    private lateinit var listenButton: Button
    private var locationListener: FusedLocationListener? = null
    private val vehicleValues = linkedMapOf<String, String>()
    private val efficiencyValues = linkedMapOf<String, String>()
    private val main = Handler(Looper.getMainLooper())
    private var selfTest = false
    private var sttSelfTested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        report("App", "실행됨 · ${packageName} ${packageManager.getPackageInfo(packageName, 0).versionName}")
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        selfTest = debuggable && intent.getBooleanExtra(EXTRA_SELFTEST, false)
        if (selfTest) {
            report("SelfTest", "시작 · TTS 1회 발화 후 STT 5초 청취 (debug 전용)")
            main.postDelayed({ selfTestStt() }, 20_000) // fallback when TTS never finishes
        }
        initVehicle()
        initNavi()
        initTts()
        initStt()
        initLocation()
        initUxRestrictions()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(16, 22, 31))
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 16, 24)
        }
        left.addView(text(getString(R.string.shell_title), 24f, Color.WHITE))
        left.addView(
            text("SDK 연결 진단 화면 · 제품 화면이 아닙니다", 20f, Color.rgb(245, 197, 66)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 24, 32, 24)
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button("차량값 다시 조회") { readVehicle() })
        buttons.addView(button("말하기 (TTS)") { speak() })
        listenButton = button("듣기 (STT)") { listen() }
        buttons.addView(listenButton)
        buttons.addView(button("내비 화면") { openNavi() })
        right.addView(buttons)
        statusBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(ScrollView(this).apply { addView(statusBox) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        setContentView(root)
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, 8, 0, 8)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun report(key: String, message: String) {
        Log.i(TAG, "$key: $message")
        runOnUiThread {
            if (!alive) return@runOnUiThread
            val row = rows.getOrPut(key) {
                text("", 17f, Color.WHITE).also { statusBox.addView(it) }
            }
            row.text = "$key\n  $message"
            row.setTextColor(if (message.contains("ERROR") || message.contains("실패")) Color.rgb(255, 128, 128) else Color.WHITE)
        }
    }

    // ---------------------------------------------------------------- Vehicle SDK 2.3.2

    private fun initVehicle() {
        vehicle = Vehicle(applicationContext)
        runCatching { vehicle.initialize() }
            .onSuccess { report("Vehicle", "initialize() 반환 · 실제 값 대기") }
            .onFailure { report("Vehicle", "ERROR initialize: ${it.message}") }
    }

    private fun vehicleValue(name: String, value: String) {
        vehicleValues[name] = value
        report("Vehicle", vehicleValues.entries.joinToString(" · ") { "${it.key} ${it.value}" })
    }

    /** check<Capability> first; read only when the property is readable. */
    private fun <T> readWithCapability(
        name: String,
        check: (onComplete: (CapabilityConfig) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
        read: (onComplete: (T?) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
        sink: (String, String) -> Unit = ::vehicleValue,
        format: (T) -> String,
    ) {
        runCatching {
            check({ config ->
                if (config.accessType.name !in setOf("READ", "READ_WRITE")) {
                    sink(name, "미지원(${config.accessType})")
                } else {
                    runCatching {
                        read({ v -> sink(name, v?.let(format) ?: "null") },
                            { e -> sink(name, describe(e)) })
                    }.onFailure { sink(name, describe(it)) }
                }
            }, { e -> sink(name, describe(e)) })
        }.onFailure { sink(name, "ERROR ${it.message}") }
    }

    private fun efficiencyValue(name: String, value: String) {
        efficiencyValues[name] = value
        report("Vehicle 효율", efficiencyValues.entries.joinToString(" · ") { "${it.key} ${it.value}" })
    }

    /** 전비(km/kWh)와 연비(L/100km): 차가 주는 값이 있으면 추천 계산에 쓸 수 있는지 보려는 읽기. */
    private fun readEfficiency() {
        val ev = vehicle.getEvBattery()
        val fuel = vehicle.getFuel()
        fun kmPerKwh(v: Float) = "%.2fkm/kWh".format(v)
        fun lPer100(v: Float) = "%.2fL/100km".format(v)
        readWithCapability<Float>("전비 이번주행",
            { ok, err -> ev.checkCurrentEvEfficiencyCapabilities(ok, err) },
            { ok, err -> ev.getCurrentEvEfficiency(ok, err) }, ::efficiencyValue, ::kmPerKwh)
        readWithCapability<Float>("전비 누적",
            { ok, err -> ev.checkCumulativeEvEfficiencyCapabilities(ok, err) },
            { ok, err -> ev.getCumulativeEvEfficiency(ok, err) }, ::efficiencyValue, ::kmPerKwh)
        readWithCapability<Float>("전비 충전후",
            { ok, err -> ev.checkSinceChargeEvEfficiencyCapabilities(ok, err) },
            { ok, err -> ev.getSinceChargeEvEfficiency(ok, err) }, ::efficiencyValue, ::kmPerKwh)
        readWithCapability<Float>("전비 순간",
            { ok, err -> ev.checkInstantaneousEvEfficiencyCapabilities(ok, err) },
            { ok, err -> ev.getInstantaneousEvEfficiency(ok, err) }, ::efficiencyValue, ::kmPerKwh)
        readWithCapability<Float>("연비 이번주행",
            { ok, err -> fuel.checkCurrentFuelEconomyCapabilities(ok, err) },
            { ok, err -> fuel.getCurrentFuelEconomy(ok, err) }, ::efficiencyValue, ::lPer100)
        readWithCapability<Float>("연비 누적",
            { ok, err -> fuel.checkCumulativeFuelEconomyCapabilities(ok, err) },
            { ok, err -> fuel.getCumulativeFuelEconomy(ok, err) }, ::efficiencyValue, ::lPer100)
        readWithCapability<Float>("연비 주유후",
            { ok, err -> fuel.checkSinceChargeFuelEconomyCapabilities(ok, err) },
            { ok, err -> fuel.getSinceChargeFuelEconomy(ok, err) }, ::efficiencyValue, ::lPer100)
        readWithCapability<Float>("연비 순간",
            { ok, err -> fuel.checkInstantaneousFuelEconomyCapabilities(ok, err) },
            { ok, err -> fuel.getInstantaneousFuelEconomy(ok, err) }, ::efficiencyValue, ::lPer100)
    }

    private fun readVehicle() {
        readWithCapability<Int>("연료",
            { ok, err -> vehicle.getFuel().checkFuelPercentCapabilities(ok, err) },
            { ok, err -> vehicle.getFuel().getFuelPercent(ok, err) }) { "$it%" }
        readWithCapability<Int>("배터리",
            { ok, err -> vehicle.getEvBattery().checkEvBatteryStateOfChargeCapabilities(ok, err) },
            { ok, err -> vehicle.getEvBattery().getEvBatteryStateOfCharge(ok, err) }) { "$it%" }
        readWithCapability<Float>("주행거리",
            { ok, err -> vehicle.getOdometer().checkCurrentDistanceDrivenCapabilities(ok, err) },
            { ok, err -> vehicle.getOdometer().getCurrentDistanceDriven(ok, err) }) { "%.1fkm".format(it) }
        readWithCapability<String>("VIN",
            { ok, err -> vehicle.getCarInfo().checkVINInfoCapabilities(ok, err) },
            { ok, err -> vehicle.getCarInfo().getVINInfo(ok, err) }) { "…" + it.takeLast(4) } // masked
        // Deprecated in 2.3.2 (Google GEAR_SELECTION recommended) but still a valid check here.
        @Suppress("DEPRECATION")
        runCatching {
            vehicle.getGear().getCurrentGearSelectionState(
                { g -> vehicleValue("기어", g?.toString() ?: "null") },
                { e -> vehicleValue("기어", describe(e)) })
        }.onFailure { vehicleValue("기어", describe(it)) }
        runCatching { readEfficiency() }.onFailure { efficiencyValue("읽기", describe(it)) }
    }

    /** Fuel (ICE) and battery SoC (EV) subscriptions; the unsupported one reports its error. */
    private fun subscribeEnergy() {
        if (fuelListener == null) {
            val listener = object : FuelPercentListener {
                override fun onFuelPercentUpdated(value: Int?) {
                    if (started) report("Vehicle 연료 구독", "이벤트 수신: ${value ?: "null"}%")
                }
                override fun onFailed(e: Exception) = report("Vehicle 연료 구독", describe(e))
            }
            runCatching { vehicle.getFuel().registerFuelPercent(listener) }
                .onSuccess { fuelListener = listener; report("Vehicle 연료 구독", "등록 호출 반환 · 이벤트 대기") }
                .onFailure { report("Vehicle 연료 구독", describe(it)) }
        }
        if (socListener == null) {
            val listener = object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
                    if (started) report("Vehicle 배터리 구독", "이벤트 수신: ${value ?: "null"}%")
                }
                override fun onFailed(e: Exception) = report("Vehicle 배터리 구독", describe(e))
            }
            runCatching { vehicle.getEvBattery().registerEvBatteryStateOfCharge(listener) }
                .onSuccess { socListener = listener; report("Vehicle 배터리 구독", "등록 호출 반환 · 이벤트 대기") }
                .onFailure { report("Vehicle 배터리 구독", describe(it)) }
        }
    }

    private fun unsubscribeEnergy() {
        fuelListener?.let { l -> runCatching { vehicle.getFuel().unregisterFuelPercent(l) } }
        fuelListener = null
        socListener?.let { l -> runCatching { vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(l) } }
        socListener = null
    }

    /**
     * An unsupported property is a valid answer from the SDK (e.g. fuel on an EV), not a failure.
     * Drop the "ERROR_" prefix so report() does not paint the whole Vehicle row red.
     */
    private fun describe(e: Throwable): String =
        if (e.message?.contains("NOT_SUPPORTED") == true) "미지원(${e.message?.replace("ERROR_", "")})" else "ERROR ${e.message}"

    // ---------------------------------------------------------------- NaviHelper 3.0.5

    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            report("Navi 위치", "%.5f, %.5f · %s".format(
                currentLocationInfo.latitude, currentLocationInfo.longitude, currentLocationInfo.address))
        }
        override fun onNavigationStatus(status: NavigationStatus) {
            report("Navi 상태", status.name)
        }
    }

    private fun initNavi() {
        navi = NaviHelper(applicationContext)
        runCatching {
            navi.addListener(naviListener)
            navi.initialize(
                onConnected = {
                    report("Navi", "서비스 연결 콜백 수신")
                    runCatching { navi.getCurrentLocationInfo() }
                        .onFailure { report("Navi 위치", "ERROR ${it.message}") }
                },
                onFailed = { report("Navi", "ERROR 연결 실패: ${it.message}") })
        }.onSuccess { report("Navi", "initialize() 반환 · 연결 대기") }
            .onFailure { report("Navi", "ERROR ${it.message}") }
    }

    private fun openNavi() {
        runCatching { navi.requestNavigationScreen(NavigationScreenRequest.Foreground) }
            .onSuccess { report("Navi 화면", "requestNavigationScreen(Foreground) 호출 반환") }
            .onFailure { report("Navi 화면", "ERROR ${it.message}") }
    }

    // ---------------------------------------------------------------- TextToSpeech 3.3.1 (ON_DEVICE)

    private val ttsEvents = object : TtsEventListener {
        override fun onStart() = report("TTS 이벤트", "onStart")
        override fun onReady() = report("TTS 이벤트", "onReady")
        override fun onDone() {
            report("TTS 이벤트", "onDone (발화 완료)")
            if (selfTest) main.post { selfTestStt() }
        }
        override fun onError(errorType: TtsError) {
            report("TTS 이벤트", "ERROR $errorType")
            if (selfTest) main.post { selfTestStt() }
        }
        override fun onStop() = report("TTS 이벤트", "onStop")
        override fun onUpdatedRms(rms: Double) = Unit
    }

    private fun initTts() {
        tts = TextToSpeech(applicationContext, TtsMode.ON_DEVICE)
        runCatching {
            tts.initialize(object : TtsInit {
                override fun onSuccess() {
                    runCatching {
                        tts.addEventListener(ttsEvents)
                        tts.setLocale(Locale.KOREA)
                    }.onFailure { report("TTS", "ERROR setup: ${it.message}") }
                    ttsReady = true
                    report("TTS", "ON_DEVICE 초기화 성공 · [말하기] 가능")
                    if (selfTest) main.post { speak() }
                }
                override fun onFailure(errorType: TtsError) {
                    report("TTS", "ERROR 초기화 실패: $errorType")
                }
            })
        }.onSuccess { report("TTS", "initialize() 반환 · 콜백 대기") }
            .onFailure { report("TTS", "ERROR ${it.message}") }
    }

    private fun speak() {
        if (!ttsReady) { report("TTS", "아직 초기화 전"); return }
        runCatching { tts.speak(getString(R.string.tts_sample), true) }
            .onSuccess { report("TTS 요청", "speak() 호출 반환") }
            .onFailure { report("TTS 요청", "ERROR ${it.message}") }
    }

    // ---------------------------------------------------------------- SpeechToText 3.3.1 (ON_DEVICE)

    private val sttResults = object : SttResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) =
            report("STT 결과", "${if (isFinal) "최종" else "중간"}: $text")
        override fun onUpdatedEpdData(epdOn: Long, epdOff: Long) = Unit
        override fun onStartedRecognition() = report("STT 이벤트", "onStartedRecognition (말하세요)")
        override fun onEndedRecognition() {
            report("STT 이벤트", "onEndedRecognition")
            runOnUiThread { listening = false; listenButton.text = "듣기 (STT)" }
        }
        override fun onError(errorType: SttError) = report("STT 이벤트", "ERROR $errorType")
        override fun onReady() = report("STT 이벤트", "onReady")
        override fun onUpdatedRms(rms: Double) = Unit
    }

    private fun initStt() {
        stt = SpeechToText(applicationContext, SttMode.ON_DEVICE)
        runCatching {
            stt.initialize(object : SttInit {
                override fun onSuccess() {
                    runCatching {
                        stt.addListener(sttResults)
                        stt.setLocale(Locale.KOREA)
                    }.onFailure { report("STT", "ERROR setup: ${it.message}") }
                    sttReady = true
                    report("STT", "ON_DEVICE 초기화 성공 · [듣기] 가능")
                }
                override fun onFailure(errorType: SttError) {
                    report("STT", "ERROR 초기화 실패: $errorType")
                }
            })
        }.onSuccess { report("STT", "initialize() 반환 · 콜백 대기") }
            .onFailure { report("STT", "ERROR ${it.message}") }
    }

    /** Toggle: request() to start listening, stop() to end it (on-device STT waits for speech indefinitely). */
    private fun listen() {
        if (!sttReady) { report("STT", "아직 초기화 전"); return }
        if (listening) {
            runCatching { stt.stop() }
                .onSuccess { listening = false; listenButton.text = "듣기 (STT)"; report("STT 요청", "stop() 호출 반환") }
                .onFailure { report("STT 요청", "ERROR ${it.message}") }
            return
        }
        runCatching { stt.request() }
            .onSuccess { listening = true; listenButton.text = "듣기 중지"; report("STT 요청", "request() 호출 반환 · 말해 보세요") }
            .onFailure { report("STT 요청", "ERROR ${it.message}") }
    }

    /** Self-test only: one listen window so the verifier can see onReady without a tap. */
    private fun selfTestStt() {
        if (sttSelfTested || !alive) return
        if (!sttReady) { main.postDelayed({ selfTestStt() }, 1_000); return }
        sttSelfTested = true
        listen()
        main.postDelayed({ if (listening) listen() }, 5_000)
    }

    // ---------------------------------------------------------------- FusedLocation 1.1.3

    private fun initLocation() {
        location = FusedLocation(applicationContext)
        val supported = runCatching { location.isSupported() }
            .onFailure { report("Location", "ERROR isSupported: ${it.message}") }
            .getOrNull() ?: return
        if (!supported) { report("Location", "isSupported() = false · 이 환경 미지원"); return }
        runCatching { location.initialize() }
            .onSuccess { locationInitialized = true; report("Location", "isSupported=true · initialize() 반환") }
            .onFailure { report("Location", "ERROR initialize: ${it.message}"); return }
        if (checkSelfPermission(PERM_LOCATION) == PackageManager.PERMISSION_GRANTED) startLocation()
        else requestPermissions(arrayOf(PERM_LOCATION), REQ_LOCATION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOCATION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startLocation()
        else report("Location", "FUSED_LOCATION 권한 거부됨")
    }

    private fun formatLocation(l: LocationData?) =
        l?.let { "%.5f, %.5f · heading %.0f · conf %.2f".format(it.latitude, it.longitude, it.heading, it.confidence) } ?: "null"

    private fun startLocation() {
        if (!locationInitialized || locationListener != null) return
        val once = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) = report("Location 마지막 위치", formatLocation(location))
            override fun onError(exception: Exception) = report("Location 마지막 위치", "ERROR ${exception.message}")
        }
        val updates = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) = report("Location 구독", formatLocation(location))
            override fun onError(exception: Exception) = report("Location 구독", "ERROR ${exception.message}")
        }
        runCatching {
            location.getLastKnownLocation(once)
            location.registerFusedLocationCallback(updates)
        }.onSuccess { locationListener = updates; report("Location 구독", "등록 호출 반환 · 이벤트 대기") }
            .onFailure { report("Location", "ERROR ${it.message}") }
    }

    // ---------------------------------------------------------------- AAOS driving restrictions

    private fun initUxRestrictions() {
        car = Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
            if (!alive) return@createCar
            if (!ready) { report("UX 제한", "차량 서비스 연결 끊김"); return@createCar }
            runCatching {
                ux = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                ux?.registerListener { r ->
                    report("UX 제한", if (r.isRequiresDistractionOptimization) "주행 제한 중 (${r.activeRestrictions})" else "정차 · 제한 없음")
                }
                ux?.currentCarUxRestrictions?.let { r ->
                    report("UX 제한", if (r.isRequiresDistractionOptimization) "주행 제한 중 (${r.activeRestrictions})" else "정차 · 제한 없음")
                }
            }.onFailure { report("UX 제한", "ERROR ${it.message}") }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onStart() {
        super.onStart()
        started = true
        readVehicle()
        subscribeEnergy()
    }

    override fun onStop() {
        started = false
        unsubscribeEnergy()
        if (sttReady && listening) runCatching { stt.stop() }
        listening = false
        listenButton.text = "듣기 (STT)"
        if (ttsReady) runCatching { tts.stop() }
        super.onStop()
    }

    override fun onDestroy() {
        alive = false
        main.removeCallbacksAndMessages(null)
        locationListener?.let { l -> runCatching { location.unregisterFusedLocationCallback(l) } }
        locationListener = null
        if (locationInitialized) runCatching { location.release() }
        runCatching { stt.removeListener(sttResults); stt.release() }
        runCatching { tts.removeEventListener(ttsEvents); tts.release() }
        runCatching { navi.removeListener(naviListener); navi.release() }
        runCatching { vehicle.release() }
        runCatching { ux?.unregisterListener() }
        runCatching { car?.disconnect() }
        super.onDestroy()
    }
}
