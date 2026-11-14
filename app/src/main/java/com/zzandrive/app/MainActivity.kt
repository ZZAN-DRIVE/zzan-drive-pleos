package com.zzandrive.app

import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import com.zzandrive.app.runtime.Approval
import com.zzandrive.app.runtime.AskStep
import com.zzandrive.app.runtime.AskUi
import com.zzandrive.app.runtime.TripUi
import com.zzandrive.app.runtime.UiState
import com.zzandrive.app.runtime.ZzanController
import com.zzandrive.app.ui.AmbientView
import com.zzandrive.app.ui.BtnSize
import com.zzandrive.app.ui.CharacterStageView
import com.zzandrive.app.ui.Depth
import com.zzandrive.app.ui.Glass
import com.zzandrive.app.ui.GlassDrawable
import com.zzandrive.app.ui.Icon
import com.zzandrive.app.ui.IconDrawable
import com.zzandrive.app.ui.Palette
import com.zzandrive.app.ui.Pleos
import com.zzandrive.app.ui.RingView
import com.zzandrive.app.ui.Type
import com.zzandrive.app.ui.VoiceBarsDrawable
import com.zzandrive.app.voice.SpeechClip
import com.zzandrive.core.domain.CharacterProgress
import com.zzandrive.core.domain.CompareMode
import com.zzandrive.core.domain.CreditRule
import com.zzandrive.core.domain.RoutePlanner
import com.zzandrive.core.domain.RoutePolicy
import com.zzandrive.core.domain.TriggerPolicy
import com.zzandrive.core.domain.toWon
import com.zzandrive.core.voice.Josa
import com.zzandrive.core.voice.Persona
import com.zzandrive.core.voice.WonFormat
import com.zzandrive.data.api.Cancellable
import com.zzandrive.data.api.DataOrigin
import com.zzandrive.data.api.EnergyType
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Parked-only product screen (no distractionOptimized: the system blocks it while driving, R-33).
 * It only draws [UiState] and forwards taps; all logic lives in [ZzanController].
 *
 * Made to be glanced at, not read: numbers, rings and one character; sentences are kept for decisions. A
 * page-navigation bar on top (what data the screen runs on, the car's state as icons, [시연], [설정]; no title and
 * no logo: the bar stays quiet) and two columns under it. Left, on the driver's side: the date, the amount saved so far, the character with its level,
 * and the one primary action. Right: what the character just said, then either the dashboard (a level-up when there
 * is one, three rings, and under them how it works or the last trip) or one decision card (a question, a comparison,
 * the routes).
 * The long explanations (formulas, sources) are one tap away behind (i).
 *
 * Glass on a soft backdrop of the CI colours (ui/Palette.kt, ui/Glass.kt, ui/Ambient.kt); sizes and type follow the
 * Pleos Connect design guide (ui/Pleos.kt) and day/night. In a narrow (single) window the same views stack.
 * Motion (the character, floating pieces, the amount counting up, rings sweeping in) plays only while parked, with no
 * popup on top and system animations on, and nothing redraws or animates while the screen is not visible.
 */
class MainActivity : Activity() {
    private lateinit var app: ZzanApp
    private var controller: ZzanController? = null
    private var subscription: Cancellable? = null
    private val progress = CharacterProgress()
    private lateinit var palette: Palette
    private var started = false
    private var wide = true
    private var lastState: UiState? = null

    private lateinit var scene: FrameLayout
    private lateinit var ambient: AmbientView
    private lateinit var body: LinearLayout
    private lateinit var marker: TextView
    private lateinit var markerDot: GradientDrawable
    private lateinit var stateChip: TextView
    private lateinit var micChip: TextView
    private lateinit var locChip: TextView
    private var renderedChips: Any? = null
    private lateinit var main: FrameLayout

    private lateinit var heroBlock: LinearLayout
    private lateinit var datePill: TextView
    private lateinit var totalAmount: TextView
    private var totalTarget = Long.MIN_VALUE
    private var totalShown = 0L
    private var totalAnimator: ValueAnimator? = null
    private lateinit var todayChip: TextView
    private var shownToday: Long? = null
    private lateinit var stage: CharacterStageView
    private lateinit var levelRow: LinearLayout
    private lateinit var levelPill: TextView
    private lateinit var artNote: TextView
    private lateinit var findButton: Button

    private lateinit var rightCol: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var voiceBars: VoiceBarsDrawable
    private var shownLine: Any? = null
    private var bubbleFaded = false
    private var lineSeen = false
    private val slide = LayoutTransition().apply { setAnimateParentHierarchy(false) }
    private var slides = false
    private lateinit var listenBadge: TextView
    private lateinit var resultText: TextView
    private lateinit var slot: FrameLayout
    private lateinit var askCard: Card
    private var renderedAsk: AskUi? = null
    private lateinit var recCard: Card
    private lateinit var recButtons: LinearLayout
    private lateinit var adviceButton: Button
    private var renderedRec: Any? = null
    private lateinit var routeCard: Card
    private var renderedRoutes: Any? = null
    private lateinit var idleStack: LinearLayout
    private lateinit var idleScroll: ScrollView
    private lateinit var celebration: LinearLayout
    private lateinit var celebrationTitle: TextView
    private lateinit var tripCard: Card
    private var renderedTrip: TripUi? = null
    private var tripDonut: RingView? = null
    private lateinit var guidePanel: LinearLayout
    private lateinit var guideAlert: TextView
    private lateinit var dashboard: LinearLayout
    private lateinit var batteryTitle: TextView
    private lateinit var batteryRing: RingView
    private lateinit var batteryCaption: TextView
    private lateinit var monthRing: RingView
    private lateinit var monthLegend: TextView
    private lateinit var levelRing: RingView
    private lateinit var levelCaption: TextView
    private var rings: List<RingView> = emptyList()

    private lateinit var personaPopup: Popup
    private lateinit var locationPopup: Popup
    private lateinit var noticePopup: Popup
    private lateinit var settingsPopup: Popup
    private lateinit var resetPopup: Popup
    private lateinit var demoPopup: Popup
    private lateinit var infoPopup: Popup
    private lateinit var infoBody: TextView
    private val popups = mutableListOf<Popup>()
    private var blurred = false
    private lateinit var thresholdBox: LinearLayout
    private var renderedThreshold = -1
    private lateinit var voiceRow: LinearLayout
    private var renderedVoice: Pair<Boolean, Boolean>? = null
    private lateinit var wageRow: LinearLayout
    private var renderedWage = -1
    private lateinit var demoRows: LinearLayout
    private var renderedDemo: String? = null

    private var shownCelebration = -1
    private var playedReaction: String? = null
    private var choosingPersona = false

    private val handler = Handler(Looper.getMainLooper())
    private val midnight = Runnable {
        controller?.refresh() // today's and this month's totals roll over
        scheduleMidnight()
    }

    /** The spoken line has been on screen long enough: it leaves, and the screen is quiet again. */
    private val fadeBubble = Runnable {
        bubbleFaded = true
        lastState?.let(::render)
    }

    private val debuggable: Boolean get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Screen-only settings, kept apart from the savings record ("처음부터" does not clear them). */
    private val uiPrefs by lazy { getSharedPreferences("zzan-ui", MODE_PRIVATE) }

    /**
     * Whether our own explanation of the location permission was shown. The system dialog alone is
     * unclear: on the Pleos 2.1.0 emulator it names the permission "알 수 없는 작업" (unknown action).
     */
    private var locationExplained: Boolean
        get() = uiPrefs.getBoolean("locationExplained", false)
        set(value) { uiPrefs.edit().putBoolean("locationExplained", value).apply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as ZzanApp
        palette = Palette.of(resources.configuration)
        setContentView(buildUi())
        applyLayout(resources.configuration.screenWidthDp)
        bind(app.demo?.controller ?: app.live.controller)
        handleIntent(intent)
        // The location permission is asked from the explanation popup (see render), after the character choice.
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        started = true
        subscribe()
        stage.resume()
        scheduleMidnight()
        if (shownLine != null && !bubbleFaded) { // a line that was on screen when the activity stopped still leaves
            handler.removeCallbacks(fadeBubble)
            handler.postDelayed(fadeBubble, BUBBLE_MS)
        }
        if (app.demo == null) app.live.vehicle.refresh() // catch up after being blocked or backgrounded
    }

    /** Not visible (background, another window, blocked while driving): stop drawing and animating. */
    override fun onStop() {
        started = false
        subscription?.cancel()
        subscription = null
        stage.pause()
        ambient.running = false
        settleTotal()
        handler.removeCallbacks(midnight)
        handler.removeCallbacks(fadeBubble)
        super.onStop()
    }

    override fun onDestroy() {
        subscription?.cancel()
        super.onDestroy()
    }

    /** Window resized or turned (declared in the manifest): same views, new arrangement. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout(newConfig.screenWidthDp)
        lastState?.let(::render)
    }

    /** Pleos: Back closes what is open on top; at the top level the app steps back instead of finishing. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val open = popups.lastOrNull { it.visible && it.dismissible }
        if (open != null) {
            open.onDismiss()
            return
        }
        if (popups.any { it.visible }) return // a choice that must be made first
        moveTaskToBack(true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) app.live.onLocationGranted()
    }

    private fun bind(c: ZzanController) {
        subscription?.cancel()
        subscription = null
        controller = c
        shownCelebration = -1
        playedReaction = null
        lineSeen = false
        handler.removeCallbacks(fadeBubble)
        if (started) subscribe()
    }

    /** observe() renders the current state at once, so a returning screen is up to date immediately. */
    private fun subscribe() {
        subscription?.cancel()
        subscription = controller?.observe(::render)
    }

    private fun scheduleMidnight() {
        handler.removeCallbacks(midnight)
        val now = ZonedDateTime.now(ZONE)
        val next = now.toLocalDate().plusDays(1).atStartOfDay(ZONE)
        handler.postDelayed(midnight, Duration.between(now, next).toMillis() + 1_000)
    }

    // ------------------------------------------------------------------ intents (demo switch, debug commands)

    private fun handleIntent(i: Intent?) {
        i ?: return
        i.getStringExtra(EXTRA_DEMO)?.let { startDemo(it) }
        val cmd = i.getStringExtra(EXTRA_CMD) ?: return
        if (!debuggable) return // debug commands exist only in debug builds
        Log.i(ZzanController.TAG, "CMD $cmd")
        val c = controller ?: return
        when (cmd) {
            "seedB" -> c.seedScenarioB()
            "seedA" -> c.seedScenarioA()
            "approve" -> c.approve()
            "decline" -> c.decline()
            "explain" -> c.explain()
            "compare" -> c.compareNow()
            "find" -> c.findStation()
            "ref" -> c.answerReference(i.getStringExtra(EXTRA_ARG) ?: "current")
            "fill" -> c.answerFill(i.getStringExtra(EXTRA_ARG) ?: "skip")
            "cancel" -> c.cancelAsk()
            "threshold" -> i.getStringExtra(EXTRA_ARG)?.toIntOrNull()?.let(c::setThreshold)
            "voice" -> c.setVoiceAnswers(i.getStringExtra(EXTRA_ARG) != "off")
            "hear" -> c.hear(i.getStringExtra(EXTRA_ARG) ?: "")
            "routes" -> c.compareRoutes()
            "routesok" -> c.closeRoutes()
            "wage" -> i.getStringExtra(EXTRA_ARG)?.toIntOrNull()?.let(c::setWage)
            // A spoken answer for the on-device recognizer: files/stt/NAME.wav that scripts/demo.sh makes on the host
            // and copies in with run-as. Nothing is bundled: the host's system voices may not be redistributed.
            "hearpcm" -> runCatching { SpeechClip.read(java.io.File(filesDir, "stt"), i.getStringExtra(EXTRA_ARG) ?: "") }
                .onSuccess(c::hearPcm).onFailure { Log.w(ZzanController.TAG, "no such recording: ${it.message}") }
            "reset" -> c.resetAll()
            "celebrated" -> c.celebrationShown(99)
            "level" -> c.previewLevel(
                i.getIntExtra(EXTRA_LEVEL, 0),
                i.getStringExtra(EXTRA_PERSONA)?.let { p -> Persona.entries.firstOrNull { it.name.equals(p, ignoreCase = true) } },
                i.getIntExtra(EXTRA_PERCENT, 0),
            )
            // Popups for checks and screenshots: popup --es zzan.arg settings|demos|notices|reset|persona|close.
            "popup" -> when (i.getStringExtra(EXTRA_ARG)) {
                "settings" -> settingsPopup.show()
                "demos" -> demoPopup.show()
                "notices" -> noticePopup.show()
                "reset" -> resetPopup.show()
                "persona" -> {
                    choosingPersona = true
                    personaPopup.show()
                }
                else -> {
                    choosingPersona = false
                    popups.forEach { if (it.dismissible) it.hide() }
                }
            }
        }
        lastState?.let(::render)
    }

    private fun startDemo(which: String) {
        when (which) {
            "B" -> bind(app.startDemo(DemoScenario.B_CHARGE).controller)
            "A" -> bind(app.startDemo(DemoScenario.A_FUEL).controller)
            "C" -> bind(app.startDemo(DemoScenario.HABIT_FUEL).controller)
            else -> {
                app.stopDemo()
                if (!app.locationGranted) locationExplained = false // back to live data: offer the permission again
                bind(app.live.controller)
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    private fun render(s: UiState) {
        lastState = s
        val tag = if (s.demo) ZzanController.DEMO_TAG else ZzanController.TAG
        val unit = s.energyType.unit

        // What the screen runs on. Prices, distances and routes are the plan's examples either way.
        marker.text = when {
            s.demo -> "시연 · 예시 데이터"
            s.vehicleOrigin != null -> "실차 · 가격·거리 예시"
            else -> "연결 중 · 가격·거리 예시"
        }
        markerDot.setColor(
            when {
                s.demo -> palette.accent
                s.vehicleOrigin != null -> palette.brand
                else -> palette.text3
            },
        )
        renderSettings(s)
        renderDemos()

        val persona = s.persona
        val who = persona ?: Persona.ZZANDORI
        personaPopup.setVisible(persona == null || choosingPersona)
        personaPopup.dismissible = persona != null
        val askLocation = !s.demo && persona != null && !choosingPersona && !app.locationGranted && !locationExplained
        locationPopup.setVisible(askLocation)
        val covered = popups.any { it.visible }

        // Motion plays only while parked and in view (R-46): driving, a popup on top, or system animations turned off
        // hold everything still, the character included, and end what was already moving.
        val animations = ValueAnimator.areAnimatorsEnabled()
        val motionOk = s.parked != false && !covered && animations
        val moving = started && motionOk
        ambient.running = moving
        ambient.electric = s.energyType.isElectric
        rings.forEach { it.motion = moving }
        tripDonut?.motion = moving
        if (!moving) voiceBars.rest()
        if (moving != slides) {
            slides = moving
            rightCol.layoutTransition = if (moving) slide else null
        }

        // Above the character: the date and the amount saved so far (never below the amount the current level was
        // reached at), then today's 손익 as it is, negative included (R-35). This month is the middle ring.
        datePill.text = dateText(s.today)
        showTotal(s.totalSavedWon, moving)
        val today = s.todayNetWon
        if (today != shownToday) {
            shownToday = today
            todayChip.text = "오늘 ${money(today)}"
            todayChip.setTextColor(if (today < 0) palette.negative else palette.text)
            todayChip.setIcon(
                when {
                    today > 0 -> Icon.UP
                    today < 0 -> Icon.DOWN
                    else -> null
                },
                if (today < 0) palette.negative else palette.accent, 20,
            )
        }

        // The character moves only while parked (R-46); the system blocks this screen while driving.
        stage.logTag = tag
        stage.motionAllowed = motionOk
        val charLevel = s.character.level
        val celebrate = s.pendingCelebrations.maxOrNull()
        if (celebrate != null && celebrate != shownCelebration) {
            shownCelebration = celebrate
            idleScroll.scrollTo(0, 0) // the level-up card is first in the idle scroll
            // Before setCharacter, so the new food does not show first. Without animations there is no scene to play:
            // the character goes straight to its new picture.
            if (animations) stage.playLevelUp(who, celebrate)
            celebrationTitle.text = "${progress.food(celebrate)} 레벨 달성!"
            celebration.visibility = View.VISIBLE
            ambient.burst()
            Log.i(tag, "SHOW level-up $celebrate")
        } else if (celebrate == null) {
            // Nothing pending (closed, driving, or the record was reset): the same level may be reached again.
            shownCelebration = -1
            celebration.visibility = View.GONE
        }
        stage.setCharacter(who, charLevel)
        stage.setGauge(boltFill(s))
        val reaction = s.reaction
        if (reaction != null && reaction.id != playedReaction && celebrate == null && motionOk) {
            playedReaction = reaction.id
            stage.playReaction(reaction.happy)
            if (reaction.happy) ambient.pulse()
        }

        // Under the character: the level. How far the next one is: the third ring.
        levelPill.text = "Lv.$charLevel ${progress.food(charLevel)}"
        artNote.visibility = if (stage.borrowed && wide) View.VISIBLE else View.GONE

        // One card at a time: a question, a decision, the routes; otherwise the dashboard with the level-up, the last
        // trip, or how the app works.
        val decision = renderAsk(s) || renderRecommendation(s, unit) || renderRoutes(s)
        val tripShown = renderTrip(s, decision)
        renderGuide(s, !decision && !tripShown && celebration.visibility != View.VISIBLE)
        idleStack.visibility = if (decision) View.GONE else View.VISIBLE
        renderDashboard(s)
        renderChips(s)

        // The primary action: one at a time. While a card asks for a decision it steps back (and leaves a narrow window).
        findButton.text = if (s.energyType.isElectric) "충전소 찾기" else "주유소 찾기"
        findButton.isEnabled = s.routes == null && !s.routesPending // the departure comparison first: [확인]
        style(findButton, if (decision) Kind.SECONDARY else Kind.PRIMARY)
        findButton.visibility = if (decision && !wide) View.GONE else View.VISIBLE

        // The trip card says the deduction in full, so the same result line is not repeated above it.
        val result = s.lastResult?.takeUnless { tripShown && s.resultIsLoss }
        resultText.setKorean(result ?: "")
        resultText.setTextColor(if (s.resultIsLoss) palette.negative else palette.text)
        resultText.visibility = if (result == null) View.GONE else View.VISIBLE
        renderBubble(s, moving, decision)
        listenBadge.visibility = if (s.listening) View.VISIBLE else View.GONE
    }

    /**
     * What the bolt above the character shows, 0..1 (spec D-40): how far the level is toward the next one, the same share
     * as the 다음 레벨 ring. The one place that decides it.
     */
    private fun boltFill(s: UiState): Float = s.levelShare

    /** "11월 14일 (토)": the day the amounts of today and this month are counted for (Asia/Seoul). */
    private fun dateText(d: LocalDate): String = "${d.monthValue}월 ${d.dayOfMonth}일 (${"월화수목금토일"[d.dayOfWeek.value - 1]})"

    /** The amount above the character counts up (or down) to a new value; the first one counts up from nothing. */
    private fun showTotal(won: Long, animate: Boolean) {
        if (won == totalTarget) {
            if (!animate) settleTotal() // motion was just switched off: a count in progress goes straight to its end
            return
        }
        val from = if (totalTarget == Long.MIN_VALUE) 0L else totalShown
        totalTarget = won
        totalAnimator?.cancel()
        totalAnimator = null
        totalAmount.contentDescription = "지금까지 아낀 돈 ${WonFormat.screen(won)}"
        if (!animate || from == won) {
            setTotal(won)
            return
        }
        totalAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = COUNT_MS
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                // Only the count in progress writes the amount: one that was replaced or settled stays silent.
                if (it !== totalAnimator) return@addUpdateListener
                if (it.animatedFraction >= 1f) {
                    totalAnimator = null
                    setTotal(won)
                } else {
                    setTotal(from + ((won - from) * it.animatedFraction.toDouble()).roundToLong())
                }
            }
            start()
        }
    }

    private fun setTotal(won: Long) {
        totalShown = won
        totalAmount.text = WonFormat.screen(won)
    }

    /** Ends a count in progress at its target (the screen stops, or motion is switched off). */
    private fun settleTotal() {
        val running = totalAnimator ?: return
        totalAnimator = null
        running.cancel()
        setTotal(totalTarget)
    }

    /**
     * What the character just said, as a caption for its voice: it comes in with moving voice bars and leaves after a
     * while, so the screen at rest stays free of sentences.
     */
    private fun renderBubble(s: UiState, moving: Boolean, decision: Boolean) {
        val line = s.lastLine
        val key = line?.let { s.lineSeq to it }
        if (!lineSeen) {
            // The first state of a runtime (a new screen, day/night, another runtime bound): whatever it said before
            // was not said to this screen, so it is not shown as if it were being said now.
            lineSeen = true
            shownLine = key
            bubbleFaded = true
        } else if (key != shownLine) {
            shownLine = key
            handler.removeCallbacks(fadeBubble)
            bubbleFaded = false
            if (line != null) {
                bubble.setKorean("“$line”")
                voiceBars.kick(if (moving) VOICE_MS else 0L)
                handler.postDelayed(fadeBubble, BUBBLE_MS)
            }
        }
        // In a narrow window an open card needs the room: the line waits.
        bubble.visibility = if (line == null || bubbleFaded || (!wide && decision)) View.GONE else View.VISIBLE
    }

    /** The car's state as icons in the top bar: parked or driving (or refilling), voice answers, and location off. */
    private fun renderChips(s: UiState) {
        val electric = s.energyType.isElectric
        val key = listOf(s.refilling, s.parked, electric, s.voiceAvailable, s.voiceAnswers, s.listening, s.demo, app.locationGranted, wide)
        if (key == renderedChips) return
        renderedChips = key
        val (icon, word) = when {
            s.refilling -> (if (electric) Icon.BOLT else Icon.DROP) to (if (electric) "충전 중" else "주유 중")
            s.parked == true -> Icon.PARK to "정차"
            s.parked == false -> Icon.DRIVE to "주행"
            else -> null to "연결 중"
        }
        // In the narrow window the bar has room for the picture only (the word stays for screen readers).
        stateChip.text = if (wide || icon == null) word else ""
        stateChip.compoundDrawablePadding = if (wide) dp(8) else 0
        stateChip.contentDescription = word
        stateChip.setIcon(icon, palette.text2)
        val voiceOn = s.voiceAvailable && s.voiceAnswers
        micChip.setIcon(
            if (voiceOn) Icon.MIC else Icon.MIC_OFF,
            when {
                s.listening -> palette.accent
                voiceOn -> palette.text2
                else -> palette.text3
            },
        )
        micChip.contentDescription = "음성 답 " + when {
            !s.voiceAvailable -> "화면으로만"
            s.listening -> "듣는 중"
            s.voiceAnswers -> "켜짐"
            else -> "꺼짐"
        }
        // Without the location permission the app cannot confirm a stop at the recommended place.
        locChip.visibility = if (!s.demo && !app.locationGranted) View.VISIBLE else View.GONE
        locChip.text = if (wide) "위치 꺼짐" else ""
        locChip.compoundDrawablePadding = if (wide) dp(8) else 0
    }

    /** "+2,323원", "−377원", "0원": the sign as typography (U+2212), losses in the loss colour, the number strong. */
    private fun SpannableStringBuilder.amount(won: Long, signed: Boolean = true) {
        val start = length
        append(money(won, signed))
        setSpan(TypefaceSpan(Pleos.typeface(600)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (won < 0) setSpan(ForegroundColorSpan(palette.negative), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun money(won: Long, signed: Boolean = true): String = when {
        won < 0 -> "−" + WonFormat.screen(-won)
        won > 0 && signed -> "+" + WonFormat.screen(won)
        else -> WonFormat.screen(won)
    }

    /** An amount inside smaller text: at the size amounts have everywhere (28) and strong, in the text colour. */
    private fun SpannableStringBuilder.figure(text: String) {
        val start = length
        append(text)
        setSpan(AbsoluteSizeSpan(Type.BODY2_STRONG.size, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(TypefaceSpan(Pleos.typeface(Type.BODY2_STRONG.weight)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(palette.text), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * A legend: one part a line, each a swatch in the mark's colour, its name, and its amount in the text colour (text
     * never takes a series colour).
     */
    private fun legend(vararg parts: Triple<Int, String, String>): CharSequence = SpannableStringBuilder().apply {
        parts.forEachIndexed { i, (color, name, value) ->
            if (i > 0) append("\n")
            val start = length
            append("●\u00A0$name\u00A0")
            setSpan(ForegroundColorSpan(color), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            figure(value)
        }
    }

    /**
     * The dashboard: three rings on one glass panel. The battery or fuel with the alert level as a tick, this month as
     * a donut of what was saved against what was taken off, and how far the next level is. Each ring says its value in
     * the middle; one short line under it says the rest.
     */
    private fun renderDashboard(s: UiState) {
        val electric = s.energyType.isElectric
        batteryTitle.text = if (electric) "배터리" else "연료"
        val soc = s.socPercent
        batteryRing.fraction = ((soc ?: 0.0) / 100.0).toFloat()
        batteryRing.threshold = s.threshold / 100f
        batteryRing.value = soc?.let { "%.0f%%".format(it) } ?: "–"
        batteryRing.caption = s.energy?.let { "%.1f%s".format(it, s.energyType.unit) } ?: ""
        // At or below the alert level (the same rule the alert uses) the arc and the number turn to the warning colour.
        val low = soc != null && TriggerPolicy.fires(soc, s.threshold)
        batteryRing.arcColor = if (low) palette.chartRed else null
        batteryRing.valueColor = if (low) palette.negative else palette.text
        batteryRing.contentDescription = listOfNotNull("${batteryTitle.text} ${batteryRing.value}", batteryRing.caption.ifEmpty { null }, "알림 기준 ${s.threshold}%").joinToString(", ")
        batteryCaption.text = "알림 ${s.threshold}%"

        val saved = s.monthSavedWon
        val taken = s.monthDeductedWon
        val total = saved + taken
        monthRing.segments = if (total > 0) {
            listOf(saved.toFloat() / total to palette.chartBlue, taken.toFloat() / total to palette.chartRed)
        } else {
            emptyList()
        }
        monthRing.value = money(s.monthNetWon).removeSuffix("원")
        monthRing.valueColor = if (s.monthNetWon < 0) palette.negative else palette.text
        monthRing.contentDescription = "이번 달 손익 ${money(s.monthNetWon)}, 아낀 ${WonFormat.screen(saved)}, 빠진 ${WonFormat.screen(taken)}"
        monthLegend.text = legend(
            Triple(palette.chartBlue, "아낀", WonFormat.screen(saved)),
            Triple(palette.chartRed, "빠진", WonFormat.screen(taken)),
        )

        val level = s.character.level
        val remaining = s.remainingWon
        val share = s.levelShare
        levelRing.fraction = share
        levelRing.value = if (remaining == null) "최고" else "${(share * 100).toInt()}%"
        val next = remaining?.let { "${progress.nextFood(level)}까지 ${WonFormat.screen(it)}" } ?: "최고 레벨이야"
        levelRing.contentDescription = "다음 레벨 ${levelRing.value}, $next"
        levelCaption.text = if (remaining == null) next else SpannableStringBuilder("${progress.nextFood(level)}까지\u00A0").apply { figure(WonFormat.screen(remaining)) }
    }

    /** One question at a time (기획서 p.5): the question and its answers as large buttons, two to a row. */
    private fun renderAsk(s: UiState): Boolean {
        val ask = s.ask
        askCard.root.visibility = if (ask != null) View.VISIBLE else View.GONE
        if (ask == null) {
            renderedAsk = null
            return false
        }
        recCard.root.visibility = View.GONE
        routeCard.root.visibility = View.GONE
        if (ask != renderedAsk) {
            renderedAsk = ask
            askCard.title.setKorean(ask.title)
            askCard.sub(ask.hint)
            askCard.body.removeAllViews()
            val cells: List<Button> = ask.choices.map { c ->
                button(c.label, if (c.emphasized) Kind.PRIMARY else Kind.SECONDARY) {
                    when (ask.step) {
                        AskStep.REFERENCE -> controller?.answerReference(c.id)
                        AskStep.FILL -> controller?.answerFill(c.id)
                    }
                }
            } + button("취소", Kind.TERTIARY) { controller?.cancelAsk() }
            cells.chunked(2).forEachIndexed { i, pair ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEachIndexed { j, b ->
                    row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (j > 0) marginStart = dp(Pleos.GAP) })
                }
                if (pair.size == 1) row.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(Pleos.GAP) })
                askCard.body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) topMargin = dp(Pleos.GAP) })
            }
        }
        return true
    }

    /**
     * The comparison card: the conclusion first, at most three rows, one short line on when it counts. Open while a
     * decision or a trip is open; afterwards it folds into the result line. "No stop needed" (spec D-34) uses the same
     * card with a single [확인]. Where the numbers come from and the formula are behind (i).
     */
    private fun renderRecommendation(s: UiState, unit: String): Boolean {
        val decision = s.recommendation?.takeIf { s.approval == Approval.WAITING || s.approval == Approval.APPROVED }
        val rec = decision ?: s.advice
        recCard.root.visibility = if (rec != null) View.VISIBLE else View.GONE
        if (rec == null) {
            renderedRec = null
            return false
        }
        routeCard.root.visibility = View.GONE
        recButtons.visibility = if (decision != null && s.approval == Approval.WAITING) View.VISIBLE else View.GONE
        adviceButton.visibility = if (decision == null) View.VISIBLE else View.GONE
        recCard.footer.visibility = if (recButtons.visibility == View.VISIBLE || adviceButton.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        val key = Triple(rec.id, wide, palette.night)
        if (key == renderedRec) return true
        renderedRec = key

        val saving = rec.expectedSaving.toWon()
        val best = rec.best.offer
        val whole = rec.mode == CompareMode.ROUTE_TOTAL
        val electric = rec.energyType.isElectric
        recCard.title.setKorean(
            when (rec.mode) {
                CompareMode.COST_INCLUDED -> when {
                    rec.recommendsBaseline -> "${Josa.ga(if (best.name.startsWith("가까운")) best.name else "가까운 ${best.name}")} 가장 싸요"
                    rec.creditRule == CreditRule.VS_WORST -> "${Josa.ga(best.name)} ${WonFormat.screen(saving)} 덜 들어요"
                    else -> "${Josa.ga(best.name)} ${WonFormat.screen(saving)} 이득"
                }
                CompareMode.ROUTE_TOTAL -> when {
                    rec.noStop -> "지금은 안 들러도 돼요"
                    rec.creditRule == CreditRule.VS_NO_STOP -> "${best.name}에 들르면 ${WonFormat.screen(saving)} 덜 들어요"
                    rec.comparison == null -> "갈 수 있는 곳은 ${best.name}뿐이에요"
                    else -> "${Josa.ga(best.name)} ${WonFormat.screen(saving)} 덜 들어요"
                }
                CompareMode.ROUTE_ONLY -> "가까운 순으로 봤어요"
                CompareMode.NEAREST_ONLY -> "5km 안에는 없어요"
                CompareMode.ON_ROUTE -> "가는 길에 가장 싼 곳"
            },
        )
        // What the comparison assumes, as a few words: an estimate, the time counted, where from, how much, the wage.
        recCard.sub(
            listOfNotNull(
                "예상".takeIf { rec.creditRule != CreditRule.NONE && !rec.recommendsBaseline }, // the title stays one phrase
                "시간 포함".takeIf { whole },
                rec.referenceName?.takeIf { !whole }?.let { "$it 기준" },
                rec.fillLabel ?: "${rec.plannedQuantity.stripTrailingZeros().toPlainString()}$unit",
                rec.wagePerHour?.let { "시급 ${WonFormat.screen(it.toWon())}" },
            ).joinToString(" · "),
        )
        recCard.body.removeAllViews()
        val withPrice = rec.mode == CompareMode.COST_INCLUDED
        when {
            whole -> recCard.body.addView(rowHeader("총 지출 · 최저 대비"))
            withPrice -> recCard.body.addView(rowHeader("합계"))
        }
        val rows = rec.ranked.take(RoutePolicy.SHOWN)
        // The longest bar is the row that loses the most against the lowest.
        val worst = rows.maxOfOrNull { it.total.subtract(rec.best.total).toWon() }?.coerceAtLeast(1) ?: 1
        rows.forEach { c ->
            val isBest = c.offer.id == best.id
            val row = if (whole) {
                // 기획 계산식: each route's whole spend, the lowest as 0원 and the others as what they lose against it.
                val stop = c.offer.id != RoutePolicy.NO_STOP_ID
                val leg = c.offer.route
                val meta = listOfNotNull(
                    "${WonFormat.screen(c.offer.unitPrice.toWon())}/$unit".takeIf { stop },
                    leg?.let { "${it.km.stripTrailingZeros().toPlainString()}km" },
                    leg?.let { duration(it.minutes.toInt()) },
                    leg?.takeIf { it.tollWon.signum() > 0 }?.let { "통행료 ${WonFormat.screen(it.tollWon.toWon())}" },
                ).joinToString(" · ")
                val diff = rec.best.total.subtract(c.total).toWon()
                listRow(
                    title = c.offer.name, badge = if (isBest) badge("추천", Badge.BRAND) else null, meta = meta,
                    amount = WonFormat.screen(c.total.toWon()), sub = if (diff == 0L) "0원" else money(diff),
                    subColor = if (diff == 0L) palette.text2 else palette.negative, highlight = isBest,
                    bar = if (diff == 0L) null else -diff.toFloat() / worst,
                )
            } else {
                val tagged = when {
                    isBest -> badge("추천", Badge.BRAND)
                    c.offer.id == rec.baseline.offer.id -> badge("가장 가까움", Badge.NEUTRAL)
                    else -> null
                }
                val km = c.offer.oneWayKm.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                listRow(
                    title = c.offer.name, badge = tagged, meta = "${km}km · ${WonFormat.screen(c.offer.unitPrice.toWon())}/$unit",
                    amount = if (withPrice) WonFormat.screen(c.total.toWon()) else null, highlight = isBest,
                )
            }
            recCard.body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        val fuel = if (electric) "배터리" else "연료"
        recCard.notes(
            when {
                rec.noStop -> "남은 ${fuel}로 도착할 수 있어요"
                rec.creditRule == CreditRule.NONE -> "가격 비교가 아니라 쌓이지 않아요"
                else -> "차가 넣은 양을 확인하면 쌓여요"
            },
        )
        // In "nearest one only" the shown place is itself past 5 km, so the excluded count would mislead.
        val excluded = if (rec.mode == CompareMode.NEAREST_ONLY) 0 else rec.excludedIds.size
        val efficiency = "${if (electric) "전비" else "연비"} " +
            "${rec.efficiency.kmPerUnit.stripTrailingZeros().toPlainString()}km/$unit: ${rec.efficiency.origin.label}"
        recCard.info(
            listOfNotNull(
                "도로로 5km 넘는 ${excluded}곳은 뺐어요.".takeIf { excluded > 0 },
                when {
                    rec.noStop -> "남은 ${fuel}로 목적지까지 갈 수 있고, 들르지 않는 편이 덜 들어요."
                    rec.creditRule == CreditRule.NONE -> "가격을 비교하지 않은 안내라 절약액은 쌓이지 않아요."
                    else -> "예상치예요. 차가 넣은 양을 확인해야 쌓여요."
                },
                if (whole) {
                    "총 지출 = 전체 주행 연료비 + 넣을 양 × (이곳 단가 − 기준 단가) + 통행료 + 주행 시간 × 시급\n" +
                        "기준 단가 ${rec.travelPrice?.let { WonFormat.screen(it, 2) }}/$unit: ${DataOrigin.SURVEY.label}\n$efficiency\n가격·경로: ${best.origin.label}"
                } else {
                    "거리는 도로 거리예요.\n가격·도로 거리: ${best.origin.label}\n$efficiency"
                },
            ).joinToString("\n"),
        )
        return true
    }

    /**
     * 기획서 3-2: the routes before departure with the best first in the title, each route's value with the time
     * counted at the wage over its 운행비 (toll included on expressways). "Not saved" is said on the card; the formula
     * and the sources are behind (i).
     */
    private fun renderRoutes(s: UiState): Boolean {
        val c = s.routes
        routeCard.root.visibility = if (c != null) View.VISIBLE else View.GONE
        if (c == null) {
            renderedRoutes = null
            return false
        }
        val key = listOf(c, s.threshold, wide, palette.night)
        if (key == renderedRoutes) return true
        renderedRoutes = key
        val unit = s.energyType.unit
        val electric = s.energyType.isElectric
        val best = c.best
        val allStop = c.results.all { it.needsStop }
        routeCard.title.setKorean(
            if (allStop) "${c.destinationName}까지 어느 길이든 알림 기준 아래로 내려가요" else "${c.destinationName}까지 ${Josa.ga(best.option.name)} 나아요",
        )
        routeCard.sub("출발 전 · 시간 포함 · 시급 ${WonFormat.screen(c.wagePerHour.toWon())}")
        routeCard.body.removeAllViews()
        routeCard.body.addView(rowHeader("시간 포함 · 운행비"))
        c.results.forEach { r ->
            val isBest = !allStop && r.option.id == best.option.id
            val use = r.energy.setScale(0, RoundingMode.HALF_UP).toPlainString() + unit
            val meta = listOfNotNull(
                "${r.option.distanceKm.stripTrailingZeros().toPlainString()}km",
                use,
                duration(r.option.minutes),
                if (r.toll.signum() > 0) "통행료 ${WonFormat.screen(r.toll.toWon())}" else null,
            ).joinToString(" · ")
            val tagged = when {
                isBest -> badge("추천", Badge.BRAND)
                r.needsStop -> badge(if (electric) "도중 충전" else "도중 주유", Badge.NEUTRAL)
                else -> null
            }
            routeCard.body.addView(
                listRow(
                    title = r.option.name, badge = tagged, meta = meta, amount = WonFormat.screen(r.value.toWon()),
                    sub = "운행비 ${WonFormat.screen(r.operatingCost.toWon())}", subColor = palette.text3, highlight = isBest,
                ),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
            )
        }
        routeCard.notes("비교만 해요 · 절약액으로 쌓지 않아요")
        val energyName = when (s.energyType) {
            EnergyType.GASOLINE -> "휘발유"
            EnergyType.DIESEL -> "경유"
            EnergyType.ELECTRIC -> "전기"
        }
        val place = if (electric) "충전소" else "주유소"
        routeCard.info(
            "시간 포함 = 운행비 + 걸리는 시간 × 시급. 이 비교는 절약액으로 쌓지 않아요.\n" +
                when {
                    allStop -> "어느 길로 가도 도착 전에 알림 기준(${s.threshold}%) 아래로 내려가요. 알림이 뜨면 가는 길 ${place}를 들르는 경로를 비교해 드려요.\n"
                    c.results.any { it.needsStop } -> "알림 기준 아래로 내려가는 길은 가는 길 ${place}를 함께 봐야 해서 추천에서 뺐어요.\n"
                    else -> ""
                } +
                "${if (electric) "전비" else "연비"} ${c.efficiency.kmPerUnit.stripTrailingZeros().toPlainString()}km/$unit: ${c.efficiency.origin.label}\n" +
                "$energyName ${WonFormat.screen(c.unitPrice, 2)}/$unit: ${DataOrigin.SURVEY.label}\n" +
                "경로·통행료: ${c.origin.label}",
        )
        return true
    }

    /**
     * The last trip's driving deductions (spec D-38), parked, when no decision card is open: the amount, a donut of
     * what it is made of, and one row for each part. The rules behind the amounts are behind (i).
     */
    private fun renderTrip(s: UiState, decision: Boolean): Boolean {
        val trip = s.trip
        val show = trip != null && !decision && trip.penalty.fullWon > 0
        tripCard.root.visibility = if (show) View.VISIBLE else View.GONE
        if (trip == null || !show) {
            if (trip == null) renderedTrip = null
            return false
        }
        if (trip == renderedTrip) return true
        renderedTrip = trip
        idleScroll.scrollTo(0, 0) // a new trip is the news: the card starts on screen even if the rings were scrolled
        val p = trip.penalty
        val floored = trip.appliedWon < p.fullWon
        tripCard.title.text = SpannableStringBuilder("이번 주행 ").apply { amount(-p.fullWon) }
        // The level's accrual ran out: the amount above the character stays at the amount the level was reached at.
        tripCard.sub(if (floored) "누적 ${WonFormat.screen(trip.cumulativeWon)}은 그대로예요" else "")
        tripCard.body.removeAllViews()
        val parts = listOfNotNull(
            Triple("급가속 ${p.accelEvents}건", p.accelCost.toWon(), palette.chartRed).takeIf { p.accelEvents > 0 },
            Triple("공회전 ${p.idleSeconds / 60}분", p.idleCost.toWon(), palette.chartAlt).takeIf { p.idleEvalSeconds > 0 },
            Triple("과속 ${p.speedingKm.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()}km", p.speedingCost.toWon(), palette.chartBlue)
                .takeIf { p.speedingCost.signum() > 0 },
        )
        val sum = parts.sumOf { it.second }.coerceAtLeast(1)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val donut = RingView(this, palette).apply {
            motion = rings.firstOrNull()?.motion ?: true
            tripDonut = this
            segments = parts.map { it.second.toFloat() / sum to it.third }
            value = money(-p.fullWon).removeSuffix("원")
            valueColor = palette.negative
            valueSp = 36f
            contentDescription = "이번 주행 ${money(-p.fullWon)}"
        }
        row.addView(donut, LinearLayout.LayoutParams(dp(if (wide) 220 else 196), dp(if (wide) 220 else 196)))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parts.forEachIndexed { i, (name, won, color) ->
            if (i > 0) list.addView(View(this).apply { setBackgroundColor(palette.divider) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(68)
            }
            line.addView(View(this).apply { background = rounded(color, Pleos.RADIUS_PILL) }, LinearLayout.LayoutParams(dp(16), dp(16)))
            line.addView(label(Type.BODY2).apply { text = name }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            line.addView(label(Type.BODY2_STRONG, palette.negative).apply { text = money(-won); fontFeatureSettings = "tnum" })
            list.addView(line)
        }
        row.addView(list, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(Pleos.GAP) })
        tripCard.body.addView(row)
        tripCard.notes(
            listOfNotNull(
                "급제동 ${p.decelEvents}건은 기록만".takeIf { p.decelEvents > 0 },
                "제한속도는 예시".takeIf { p.speedingKm.signum() > 0 },
            ).joinToString(" · "),
        )
        tripCard.info(
            listOfNotNull(
                if (floored) {
                    "레벨 적립액 ${WonFormat.screen(trip.appliedWon)}까지만 줄었어요. 누적 ${WonFormat.screen(trip.cumulativeWon)}은 그대로예요(레벨 달성 금액)."
                } else {
                    "레벨 적립액에서 뺐어요. 레벨은 내려가지 않아요."
                },
                "공회전 ${p.idleSeconds / 60}분 가운데 3분을 넘긴 ${p.idleEvalSeconds / 60}분만 계산해요.".takeIf { p.idleEvalSeconds > 0 },
                "급제동 ${p.decelEvents}건은 기록만 해요.".takeIf { p.decelEvents > 0 },
                "제한속도: ${DataOrigin.PLAN_EXAMPLE.label}".takeIf { p.speedingKm.signum() > 0 },
                "단가: ${DataOrigin.SURVEY.label}",
            ).joinToString("\n"),
        )
        return true
    }

    /** With nothing else to show: how the app saves money, as three pictures with a word each. */
    private fun renderGuide(s: UiState, show: Boolean) {
        guidePanel.visibility = if (show) View.VISIBLE else View.GONE
        guideAlert.text = "${s.threshold}% 아래"
    }

    private fun duration(minutes: Int): String =
        if (minutes >= 60) "${minutes / 60}시간" + (if (minutes % 60 > 0) " ${minutes % 60}분" else "") else "${minutes}분"

    /** Chips in the settings popup: the threshold (spec D-22), voice answers, the wage. The chosen one is filled. */
    private fun renderSettings(s: UiState) {
        val wageKey = s.wage * 2 + if (wide) 1 else 0
        if (wageKey != renderedWage) {
            renderedWage = wageKey
            wageRow.removeAllViews()
            RoutePlanner.WAGE_CHOICES.forEachIndexed { i, w ->
                wageRow.addView(
                    button(WonFormat.screen(w.toLong()), if (w == s.wage) Kind.FILLED else Kind.SECONDARY) { controller?.setWage(w) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(Pleos.GAP) },
                )
            }
        }
        val voiceKey = s.voiceAnswers to s.voiceAvailable
        if (voiceKey != renderedVoice) {
            renderedVoice = voiceKey
            voiceRow.removeAllViews()
            if (!s.voiceAvailable) { // no speech recognition here: say so instead of a switch that does nothing
                voiceRow.addView(label(Type.LABEL1, palette.text3, maxLines = 2).apply { setKorean("이 기기에는 음성 인식이 없어 화면으로 답해요.") })
            } else {
                listOf(true to "켜기", false to "끄기").forEachIndexed { i, (on, name) ->
                    voiceRow.addView(
                        button(name, if (s.voiceAnswers == on) Kind.FILLED else Kind.SECONDARY) { controller?.setVoiceAnswers(on) },
                        LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(Pleos.GAP) },
                    )
                }
            }
        }
        val thresholdKey = s.threshold * 2 + if (wide) 1 else 0
        if (thresholdKey == renderedThreshold) return
        renderedThreshold = thresholdKey
        thresholdBox.removeAllViews()
        // Seven chips: one row in the wide window, 4 + 3 in the narrow one.
        val values = (TriggerPolicy.MIN..TriggerPolicy.MAX step TriggerPolicy.STEP).toList()
        values.chunked(if (wide) values.size else 4).forEachIndexed { r, chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEachIndexed { i, p ->
                row.addView(
                    button("$p%", if (p == s.threshold) Kind.FILLED else Kind.SECONDARY) { controller?.setThreshold(p) }.apply { setPadding(0, 0, 0, 0) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(if (wide) Pleos.GAP else 16) },
                )
            }
            repeat((if (wide) values.size else 4) - chunk.size) {
                row.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(if (wide) Pleos.GAP else 16) })
            }
            thresholdBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (r > 0) topMargin = dp(16) })
        }
    }

    /** The [시연] popup: three demos on example data, the live mode, and the reset. The one running is marked. */
    private fun renderDemos() {
        val running = when (app.demo?.scenario) {
            DemoScenario.B_CHARGE -> "B"
            DemoScenario.A_FUEL -> "A"
            DemoScenario.HABIT_FUEL -> "C"
            null -> "off"
        }
        val key = "$running/${palette.night}"
        if (key == renderedDemo) return
        renderedDemo = key
        demoRows.removeAllViews()
        fun row(id: String, title: String, sub: String, onClick: () -> Unit) {
            demoRows.addView(
                listRow(
                    title = title, badge = if (id == running) badge("실행 중", Badge.NEUTRAL) else null, meta = sub,
                    metaColor = palette.text3, highlight = true, onClick = onClick,
                ),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        fun start(which: String) {
            demoPopup.hide()
            startDemo(which)
        }
        row("B", "시연 B · 충전", "충전소 비교 · 정산") { start("B") }
        row("A", "시연 A · 주유", "경로 비교 · 주유소 비교") { start("A") }
        row("C", "시연 C · 운전 습관", "급가속 · 공회전 · 과속") { start("C") }
        row("off", "실차 모드", "차량 데이터") { start("off") }
        row("reset", "처음부터", "기록 · 레벨 지우기") {
            demoPopup.hide()
            resetPopup.show() // one tap must not wipe the real record
            lastState?.let(::render)
        }
    }

    // ------------------------------------------------------------------ layout

    /**
     * Wide window (the app across the app area): two columns, the left one centred on the character. Narrow window
     * (one of two): a band with the date, the amount and the level beside a small character, the dashboard and cards
     * under it, the primary action at the bottom. The same views in both; the left column's blocks have fixed
     * heights, so opening a card never resizes the character.
     */
    private fun applyLayout(widthDp: Int) {
        wide = widthDp >= WIDE_DP
        // The last trip is the news: in the narrow window it comes before the rings, so it starts on screen.
        idleStack.removeView(tripCard.root)
        idleStack.addView(tripCard.root, idleStack.indexOfChild(dashboard) + if (wide) 1 else 0)
        val movable = listOf(heroBlock, todayChip, stage, levelRow, findButton, rightCol)
        movable.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        main.removeAllViews()
        val side = dp(if (wide) 32 else 24)
        body.setPadding(side, dp(16), side, side)
        totalAmount.applyType(if (wide) Type.HERO else Type.HEADLINE1)
        heroBlock.gravity = if (wide) Gravity.CENTER_HORIZONTAL else Gravity.START
        // The note on borrowed pictures sits under the level in the wide window, so the level stays centred.
        levelRow.orientation = if (wide) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        // Floating surfaces are drawn inside their views, so their padding adds the room the shadows need.
        val large = glassPad(Depth.LARGE)
        val cardPad = dp(if (wide) 32 else 24) + large
        listOf(askCard, recCard, routeCard, tripCard).forEach { it.root.setPadding(cardPad, cardPad, cardPad, cardPad) }
        listOf(celebration, guidePanel).forEach { it.setPadding(cardPad, cardPad, cardPad, cardPad) }
        val dashPad = dp(if (wide) 28 else 20) + large
        dashboard.setPadding(dashPad, dashPad, dashPad, dashPad)
        // Three rings side by side: as large as the design asks, never wider than a third of the panel.
        val leftWidth = (widthDp * 0.38f).roundToInt().coerceIn(520, 640)
        val panel = if (wide) widthDp - 2 * 32 - leftWidth - 32 else widthDp - 2 * 24 + 8
        val column = (panel - 2 * (if (wide) 28 else 20)) / 3
        val ring = dp(minOf(if (wide) 232 else 196, column - 8).coerceAtLeast(120))
        rings.forEach { it.layoutParams = LinearLayout.LayoutParams(ring, ring).apply { setMargins(0, dp(6), 0, dp(6)) } }
        val chip = dp(if (wide) 52 else 44)
        listOf(stateChip, micChip, locChip).forEach { it.layoutParams = (it.layoutParams as LinearLayout.LayoutParams).apply { height = chip } }
        // In the wide window whatever is on the right sits in the middle of its column; in the narrow one it starts at the top.
        val inSlot = if (wide) Gravity.CENTER_VERTICAL else Gravity.TOP
        listOf(askCard.root, recCard.root, routeCard.root, idleScroll).forEach {
            it.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, inSlot)
        }

        if (wide) {
            val left = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            left.addView(heroBlock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            left.addView(todayChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { topMargin = dp(4) })
            left.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
            left.addView(levelRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            left.addView(findButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(BtnSize.L.height) + 2 * glassPad(Depth.SMALL)).apply { topMargin = dp(8) })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false // the right column's shadow room lies in the page margin
            }
            row.addView(left, LinearLayout.LayoutParams(dp(leftWidth), ViewGroup.LayoutParams.MATCH_PARENT))
            // The panels' visible edges: 32 dp from the left column, and in line with [설정] on the right.
            row.addView(
                rightCol,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = dp(32) - large
                    marginEnd = -large
                },
            )
            main.addView(row)
        } else {
            val bandText = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            bandText.addView(heroBlock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            bandText.addView(todayChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { topMargin = dp(4) })
            bandText.addView(levelRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            val band = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            band.addView(bandText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            band.addView(stage, LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = dp(16) })
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
            }
            col.addView(band, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))
            // The panels' visible edges line up with the page margin.
            col.addView(
                rightCol,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    topMargin = dp(8)
                    marginStart = -large + dp(4)
                    marginEnd = -large + dp(4)
                },
            )
            col.addView(findButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(BtnSize.L.height) + 2 * glassPad(Depth.SMALL)).apply { topMargin = dp(4) })
            main.addView(col)
        }

        // Popups follow the window: the guide's widths, never wider than the window.
        val windowPx = (widthDp * resources.displayMetrics.density).roundToInt()
        popups.forEach { p ->
            p.panel.layoutParams = FrameLayout.LayoutParams(minOf(dp(p.widthDp) + 2 * large, windowPx), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        // Cards and chips are drawn for the window they are in.
        renderedAsk = null
        renderedRec = null
        renderedRoutes = null
        renderedTrip = null
        renderedChips = null
    }

    /** Tells the backdrop where the character stands, so its pieces float around it in either layout. */
    private fun syncHero() {
        if (stage.width == 0 || !stage.isAttachedToWindow) return
        val at = IntArray(2)
        val origin = IntArray(2)
        stage.getLocationInWindow(at)
        ambient.getLocationInWindow(origin)
        val x = (at[0] - origin[0]).toFloat()
        val y = (at[1] - origin[1]).toFloat()
        ambient.setHero(x, y, x + stage.width, y + stage.height)
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(palette.page) }
        // Insets are 0 in today's Pleos window (it sits between the bars); this keeps content clear if a
        // future platform draws the app edge to edge (targetSdk 35 on Android 15).
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        // Everything under the popups: the backdrop with its floating pieces, and the page on top of it. A popup blurs
        // this whole layer.
        scene = FrameLayout(this)
        ambient = AmbientView(this, palette)
        scene.addView(ambient, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }

        // ---- Page navigation bar. The top-centre 320×60 of the window belongs to the window handle: nothing to tap there.
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // What the screen runs on: a dot (demo, live, connecting) and a few words.
        markerDot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setBounds(0, 0, dp(12), dp(12))
        }
        marker = chip().apply { setCompoundDrawablesRelative(markerDot, null, null, null) }
        topBar.addView(marker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        topBar.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        // The car's state, shown and not tapped: flat pills, icons before words.
        stateChip = chip()
        micChip = chip().apply { compoundDrawablePadding = 0 }
        locChip = chip().apply {
            setIcon(Icon.PIN_OFF, palette.text2)
            contentDescription = "위치 권한 꺼짐: 들른 곳을 확인할 수 없어요"
            visibility = View.GONE
        }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(locChip, stateChip, micChip).forEachIndexed { i, c ->
            chipRow.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply { if (i > 0) marginStart = dp(8) })
        }
        topBar.addView(chipRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        topBar.addView(
            button("시연", Kind.SECONDARY, BtnSize.S) {
                demoPopup.show()
                lastState?.let(::render)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16) - glassPad(Depth.SMALL) },
        )
        topBar.addView(
            button("설정", Kind.SECONDARY, BtnSize.S) {
                settingsPopup.show()
                lastState?.let(::render)
            },
            // The two buttons share their shadow room: 20 dp between the visible shapes.
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(20) - 2 * glassPad(Depth.SMALL) },
        )
        // [설정]'s shadow room lies in the body's right padding, so its visible edge lines up with the content edge and,
        // in the 840 dp window, [시연] stays clear of the window handle (top-centre 320×60). body draws into its padding.
        body.clipToPadding = false
        body.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90)).apply { marginEnd = -glassPad(Depth.SMALL) })
        main = FrameLayout(this).apply { clipChildren = false }
        body.addView(main, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) })
        scene.addView(body)
        root.addView(scene)
        scene.viewTreeObserver.addOnGlobalLayoutListener { syncHero() }

        // ---- Left: the date, the amount saved so far, the character with its level, the primary action.
        heroBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        datePill = chip()
        heroBlock.addView(datePill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        heroBlock.addView(
            label(Type.BODY3, palette.text2).apply { text = "지금까지 아낀 돈" },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) },
        )
        totalAmount = label(Type.HERO)
        heroBlock.addView(totalAmount, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        todayChip = chip().apply { applyType(Type.BODY2_STRONG) }

        stage = CharacterStageView(this).apply {
            contentDescription = "캐릭터"
            isClickable = true
            setOnClickListener { if (ValueAnimator.areAnimatorsEnabled()) playTap() } // a one-off clip needs motion to finish
        }

        levelRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        levelPill = label(Type.BODY2_STRONG, Palette.ON_LEVEL).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Palette.LEVEL, Pleos.RADIUS_PILL)
            setPadding(dp(20), 0, dp(20), 0)
        }
        levelRow.addView(levelPill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        // The chosen character has no pictures of its own yet: the other one's are shown.
        artNote = label(Type.LABEL1, palette.text3).apply {
            text = "그림 준비 중"
            visibility = View.GONE
        }
        levelRow.addView(artNote, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        findButton = button("충전소 찾기", Kind.PRIMARY, BtnSize.L) { controller?.findStation() }

        // ---- Right: what the character just said, the result, then the dashboard or one card.
        // The spoken line and the result line come and go: the rest makes room smoothly (while motion is allowed: render).
        rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val medium = glassPad(Depth.MEDIUM)
        val inset = glassPad(Depth.LARGE) - medium // lines the pill's visible edge up with the panels'
        voiceBars = VoiceBarsDrawable(palette.accent, dp(28)).apply { setBounds(0, 0, dp(28), dp(28)) }
        bubble = label(Type.BODY3, maxLines = 2).apply {
            background = glass(Depth.MEDIUM, corner = 28f)
            setPadding(medium + dp(24), medium + dp(16), medium + dp(28), medium + dp(16))
            setCompoundDrawablesRelative(voiceBars, null, null, null)
            compoundDrawablePadding = dp(16)
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        rightCol.addView(
            bubble,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = inset
                marginEnd = inset
            },
        )
        listenBadge = label(Type.LABEL1_STRONG, palette.onFilled).apply {
            text = "듣는 중"
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(palette.filled, Pleos.RADIUS_PILL)
            setPadding(dp(18), 0, dp(22), 0)
            setIcon(Icon.MIC, palette.onFilled)
            visibility = View.GONE
        }
        rightCol.addView(listenBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
            bottomMargin = dp(8)
            marginStart = glassPad(Depth.LARGE)
        })
        resultText = label(Type.BODY3_STRONG, maxLines = 3).apply {
            background = glass(depth = null, corner = 24f, fill = palette.glassTint)
            setPadding(dp(28), dp(16), dp(28), dp(16))
            visibility = View.GONE
        }
        rightCol.addView(resultText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            val pad = glassPad(Depth.LARGE)
            setMargins(pad, dp(4), pad, dp(4))
        })

        slot = FrameLayout(this)
        askCard = card()
        recCard = card()
        recButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recButtons.addView(button("응, 들를게", Kind.PRIMARY) { controller?.approve() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        recButtons.addView(
            button("아니", Kind.SECONDARY) { controller?.decline() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(Pleos.GAP) },
        )
        recButtons.addView(
            button("왜 거기야?", Kind.TERTIARY) { controller?.explain() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(Pleos.GAP) },
        )
        adviceButton = button("확인", Kind.PRIMARY) { controller?.closeAdvice() }.apply { visibility = View.GONE }
        recCard.footer.orientation = LinearLayout.VERTICAL
        recCard.footer.addView(recButtons)
        recCard.footer.addView(adviceButton, LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT))
        routeCard = card()
        routeCard.footer.addView(button("확인", Kind.PRIMARY) { controller?.closeRoutes() }, LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT))
        askCard.footer.visibility = View.GONE

        // With no decision open: the level-up, the dashboard, then the last trip or how the app works.
        idleStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        celebration = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = glass(Depth.LARGE)
            visibility = View.GONE
        }
        celebrationTitle = label(Type.HEADLINE3, maxLines = 2, center = true)
        celebration.addView(celebrationTitle)
        celebration.addView(
            button("좋아!", Kind.PRIMARY, BtnSize.L) {
                controller?.celebrationShown(shownCelebration)
                celebration.visibility = View.GONE
                lastState?.let(::render)
            },
            LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) },
        )
        idleStack.addView(celebration, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // The dashboard: three rings on one panel.
        dashboard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = glass(Depth.LARGE)
        }
        fun ringColumn(title: TextView, ring: RingView, caption: TextView) {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(title)
                addView(ring)
                addView(caption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            dashboard.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        batteryTitle = label(Type.LABEL1_STRONG, palette.text2, center = true)
        batteryRing = RingView(this, palette)
        // The tick's own legend: a swatch of the tick and the level it marks.
        batteryCaption = label(Type.LABEL1, palette.text2, center = true).apply {
            val swatch = GradientDrawable().apply {
                setColor(palette.chartGold)
                cornerRadius = dp(2).toFloat()
                setBounds(0, 0, dp(5), dp(22))
            }
            setCompoundDrawablesRelative(swatch, null, null, null)
            compoundDrawablePadding = dp(8)
        }
        monthRing = RingView(this, palette).apply { valueSp = 38f }
        monthLegend = label(Type.LABEL1, palette.text2, maxLines = 2, center = true)
        levelRing = RingView(this, palette)
        levelCaption = label(Type.LABEL1, palette.text2, maxLines = 2, center = true)
        rings = listOf(batteryRing, monthRing, levelRing)
        // Each ring's description already says what the line under it shows: a screen reader hears it once.
        listOf(batteryCaption, monthLegend, levelCaption).forEach { it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        ringColumn(batteryTitle, batteryRing, batteryCaption)
        ringColumn(label(Type.LABEL1_STRONG, palette.text2, center = true).apply { text = "이번 달" }, monthRing, monthLegend)
        ringColumn(label(Type.LABEL1_STRONG, palette.text2, center = true).apply { text = "다음 레벨" }, levelRing, levelCaption)
        idleStack.addView(dashboard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        tripCard = card()
        tripCard.footer.visibility = View.GONE
        idleStack.addView(tripCard.root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // How the app works: three pictures, a word and a few more under each.
        guidePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(Depth.LARGE)
            visibility = View.GONE
        }
        guidePanel.addView(label(Type.LABEL1_STRONG, palette.text2).apply { text = "이렇게 아껴요" })
        val steps = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun step(icon: Icon, word: String, more: String): TextView {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            column.addView(
                ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER
                    setImageDrawable(IconDrawable(icon, palette.accent, dp(40)))
                    background = glass(depth = null, corner = 999f)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(88), dp(88)),
            )
            column.addView(
                label(Type.BODY2_STRONG, center = true).apply { text = word },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) },
            )
            val line = label(Type.LABEL1, palette.text2, center = true).apply { text = more }
            column.addView(line)
            steps.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return line
        }
        fun arrow() {
            steps.addView(
                ImageView(this).apply {
                    setImageDrawable(IconDrawable(Icon.CHEVRON, palette.text3, dp(32)))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(32), dp(32)),
            )
        }
        guideAlert = step(Icon.BELL, "알림", "")
        arrow()
        step(Icon.BARS, "비교", "가는 비용까지")
        arrow()
        step(Icon.CHECK, "적립", "차가 확인")
        guidePanel.addView(steps, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        idleStack.addView(guidePanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        listOf(askCard, recCard, routeCard).forEach { slot.addView(it.root) }
        // When the cards are taller than the room (a narrow window), the bottom edge fades to say there is more below.
        idleScroll = ScrollView(this).apply {
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(40))
            addView(idleStack)
        }
        slot.addView(idleScroll)
        rightCol.addView(slot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        buildPopups(root)
        return root
    }

    private fun buildPopups(root: FrameLayout) {
        // Character choice: nothing moves behind a choice, so the pictures are still.
        personaPopup = popup(Pleos.POPUP_LARGE, "함께할 캐릭터", "바꿔도 레벨과 금액은 그대로예요.") {
            choosingPersona = false
        }
        val choices = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        Persona.entries.forEachIndexed { i, p ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val still = CharacterStageView(this).apply {
                motionAllowed = false
                setPortrait(p)
            }
            col.addView(still, LinearLayout.LayoutParams(dp(300), dp(300)))
            col.addView(label(Type.LABEL1, palette.text3, center = true).apply { text = if (still.borrowed) "그림 준비 중" else " " })
            col.addView(
                button(p.displayName, Kind.PRIMARY, BtnSize.L) {
                    choosingPersona = false
                    controller?.choosePersona(p)
                    personaPopup.hide()
                    lastState?.let(::render)
                },
                LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) },
            )
            choices.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(Pleos.GAP) })
        }
        personaPopup.content.addView(choices)
        personaPopup.actions.visibility = View.GONE

        locationPopup = popup(Pleos.POPUP_MEDIUM, "위치 권한을 물어볼게요", dismissible = false)
        listOf(
            "추천한 곳에 들렀는지 확인하고 근처를 찾을 때만 써요.",
            "위치는 기기 안에만 두고, 이동 경로는 남기지 않아요.",
            "에뮬레이터에서는 다음 창에 '알 수 없는 작업'이라고 나와요.",
        ).forEachIndexed { i, line ->
            locationPopup.content.addView(
                label(Type.BODY3, palette.text2, maxLines = 3).apply { setKorean(line) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) topMargin = dp(12) },
            )
        }
        locationPopup.action("계속", Kind.PRIMARY) {
            locationExplained = true
            locationPopup.hide()
            lastState?.let(::render)
            requestPermissions(arrayOf(ZzanApp.FUSED_LOCATION), REQ_LOCATION)
        }
        locationPopup.action("나중에", Kind.SECONDARY) {
            locationExplained = true
            locationPopup.hide()
            lastState?.let(::render)
        }

        noticePopup = popup(Pleos.POPUP_LARGE, "오픈소스 고지")
        val notices = runCatching { assets.open("open_source_notices.txt").bufferedReader(Charsets.UTF_8).use { it.readText() } }
            .getOrDefault("오픈소스 고지 파일을 읽지 못했어요.")
        noticePopup.content.addView(label(Type.LABEL1, palette.text2, maxLines = 400).apply { text = notices })
        noticePopup.action("닫기", Kind.SECONDARY) {
            noticePopup.hide()
            lastState?.let(::render)
        }

        // Settings (parked only, like the whole screen): the threshold (spec D-22), voice answers, the wage.
        settingsPopup = popup(Pleos.POPUP_LARGE, "설정")
        fun section(title: String, help: String, first: Boolean = false) {
            settingsPopup.content.addView(
                label(Type.BODY2_STRONG).apply { text = title },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (!first) topMargin = dp(Pleos.GAP) },
            )
            settingsPopup.content.addView(
                label(Type.LABEL1, palette.text3, maxLines = 2).apply { setKorean(help) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(12)
                },
            )
        }
        section("알림 기준", "남은 양이 이 아래로 내려가면 먼저 알려요.", first = true)
        thresholdBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsPopup.content.addView(thresholdBox)
        section("음성으로 답하기", "물은 뒤 5초 동안 들어요. \"응\", \"아니\", \"가득\"처럼 짧게 답하면 돼요.")
        voiceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        settingsPopup.content.addView(voiceRow)
        section("시간의 값 (시급)", "경로를 비교할 때 1시간을 이 금액으로 쳐요.")
        wageRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        settingsPopup.content.addView(wageRow)
        settingsPopup.action("닫기", Kind.PRIMARY) {
            settingsPopup.hide()
            lastState?.let(::render)
        }
        settingsPopup.action("캐릭터 바꾸기", Kind.SECONDARY) {
            settingsPopup.hide()
            choosingPersona = true
            personaPopup.show()
            lastState?.let(::render)
        }
        settingsPopup.action("오픈소스 고지", Kind.SECONDARY) {
            settingsPopup.hide()
            noticePopup.show()
            lastState?.let(::render)
        }

        demoPopup = popup(Pleos.POPUP_MEDIUM, "시연", "시연은 예시 데이터, 실차 모드는 차량 데이터로 돌아가요.")
        demoRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        demoPopup.content.addView(demoRows)
        demoPopup.action("닫기", Kind.SECONDARY) {
            demoPopup.hide()
            lastState?.let(::render)
        }

        // "처음부터" asks first; it wipes the real savings record.
        resetPopup = popup(Pleos.POPUP_MEDIUM, "기록을 모두 지울까요?", "레벨과 절약 기록이 처음으로 돌아가요. 되돌릴 수 없어요.")
        resetPopup.content.visibility = View.GONE
        resetPopup.action("지우기", Kind.FILLED) {
            resetPopup.hide()
            controller?.resetAll()
        }
        resetPopup.action("취소", Kind.SECONDARY) {
            resetPopup.hide()
            lastState?.let(::render)
        }

        // What a card leaves out: its formula, its sources, its caveats (opened with the card's (i)).
        infoPopup = popup(Pleos.POPUP_MEDIUM, "계산 근거")
        infoBody = label(Type.BODY3, palette.text2, maxLines = 40)
        infoPopup.content.addView(infoBody)
        infoPopup.action("닫기", Kind.PRIMARY) {
            infoPopup.hide()
            lastState?.let(::render)
        }
        popups.forEach { root.addView(it.overlay) }
    }

    // ------------------------------------------------------------------ parts

    /**
     * A card: a headline (with (i) beside it when there is more to explain), one line under it, a body that scrolls
     * only when the card runs out of room, one short note, and buttons that stay.
     */
    private inner class Card(
        val root: LinearLayout,
        val title: TextView,
        private val infoButton: ImageView,
        val sub: TextView,
        val body: LinearLayout,
        val notes: TextView,
        val footer: LinearLayout,
    ) {
        private var explanation = ""

        /** The line under the headline; no room is kept for an empty one. */
        fun sub(text: String) {
            sub.setKorean(text)
            sub.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }

        fun notes(text: String) {
            notes.setKorean(text)
            notes.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }

        /** The long explanation (formula, sources, caveats): off the card, one tap away behind (i). */
        fun info(text: String) {
            explanation = text
            infoButton.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }

        fun openInfo() {
            infoBody.setKorean(explanation)
            infoPopup.show()
            lastState?.let(::render)
        }
    }

    private fun card(): Card {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(Depth.LARGE)
            visibility = View.GONE
        }
        val title = label(Type.TITLE1, maxLines = 2)
        val info = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(IconDrawable(Icon.INFO, palette.text2, dp(36)))
            background = glass(depth = null, corner = 999f, style = GlassDrawable.Style.CLEAR, pressable = true)
            contentDescription = "계산 근거 보기"
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(info, LinearLayout.LayoutParams(dp(76), dp(76)).apply { marginStart = dp(8) })
        val sub = label(Type.LABEL1, palette.text3, maxLines = 2).apply { visibility = View.GONE }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val notes = label(Type.LABEL1, palette.text3, maxLines = 2).apply { visibility = View.GONE }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(body)
        inner.addView(notes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(head)
        root.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        root.addView(ScrollView(this).apply { addView(inner) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) })
        root.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        val card = Card(root, title, info, sub, body, notes, footer)
        info.setOnClickListener { card.openInfo() }
        return card
    }

    /** What the amounts on the right of the rows under it mean. */
    private fun rowHeader(right: String): View = label(Type.LABEL1, palette.text3).apply {
        text = right
        gravity = Gravity.END
        setPadding(dp(if (wide) 24 else 16), 0, dp(if (wide) 24 else 16), 0)
    }

    /**
     * A list row (Pleos list item, radius 24): a name with an optional badge over a detail line on the left, an amount
     * over a second amount on the right. [bar] (0..1) draws that second amount as a thin bar too (how much a place
     * loses against the best one). [highlight] gives it tinted glass (the recommended one); with [onClick] it is a
     * glass button.
     */
    private fun listRow(
        title: String,
        badge: View? = null,
        meta: String? = null,
        amount: String? = null,
        sub: String? = null,
        subColor: Int = palette.text2,
        highlight: Boolean = false,
        metaColor: Int = palette.text2,
        bar: Float? = null,
        onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(104)
            setPadding(dp(if (wide) 24 else 16), dp(16), dp(if (wide) 24 else 16), dp(16))
            if (highlight) {
                background = if (onClick != null) {
                    glass(Depth.SMALL, corner = Pleos.RADIUS_ROW.toFloat(), pressable = true).also {
                        val pad = glassPad(Depth.SMALL)
                        setPadding(paddingLeft + pad, paddingTop + pad, paddingRight + pad, paddingBottom + pad)
                    }
                } else {
                    glass(depth = null, corner = Pleos.RADIUS_ROW.toFloat(), fill = palette.glassTint)
                }
            }
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(label(Type.BODY2_STRONG).apply { text = title }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f))
        if (badge != null) head.addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(12) })
        left.addView(head)
        if (meta != null) {
            left.addView(
                label(Type.LABEL1, metaColor, maxLines = 2).apply { setKorean(meta) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) },
            )
        }
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (amount != null || sub != null) {
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }
            if (amount != null) {
                right.addView(
                    label(Type.BODY2_STRONG).apply { text = amount; fontFeatureSettings = "tnum" },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
            if (sub != null) {
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                if (bar != null) {
                    // A thin bar from the amount towards the left: the longest is the place that loses the most.
                    line.addView(
                        View(this).apply { background = rounded(subColor, 4) },
                        LinearLayout.LayoutParams((dp(BAR_DP) * bar.coerceIn(0f, 1f)).roundToInt().coerceAtLeast(dp(8)), dp(8)).apply { marginEnd = dp(12) },
                    )
                }
                line.addView(label(Type.BODY2_STRONG, subColor).apply { text = sub; fontFeatureSettings = "tnum" })
                right.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            }
            row.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16) })
        }
        if (onClick != null) {
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { onClick() }
        }
        return row
    }

    private enum class Badge { BRAND, NEUTRAL }

    private fun badge(text: String, kind: Badge): TextView =
        label(Type.LABEL1_STRONG, if (kind == Badge.BRAND) palette.onBrand else palette.text).apply {
            this.text = text
            gravity = Gravity.CENTER_VERTICAL
            background = if (kind == Badge.BRAND) rounded(palette.brand, Pleos.RADIUS_PILL) else glass(depth = null, corner = 999f, fill = palette.glassTint)
            setPadding(dp(14), 0, dp(14), 0)
        }

    /** A small flat glass pill that shows something (it is not a button): an optional icon and a word or two. */
    private fun chip(): TextView = label(Type.LABEL1_STRONG, palette.text2).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = glass(depth = null, corner = 999f)
        setPadding(dp(18), 0, dp(18), 0)
        compoundDrawablePadding = dp(8)
    }

    private fun TextView.setIcon(icon: Icon?, color: Int, sizeDp: Int = 28) {
        val drawable = icon?.let { IconDrawable(it, color, dp(sizeDp)).apply { setBounds(0, 0, dp(sizeDp), dp(sizeDp)) } }
        setCompoundDrawablesRelative(drawable, null, null, null)
    }

    /**
     * A Pleos system popup as dense glass over the blurred screen: scrim, a panel with radius 48 and padding 48, a
     * title, an optional description, content that scrolls when it is taller than the window, and large buttons.
     * [dismissible]: Back and a tap outside close it.
     */
    private inner class Popup(val overlay: FrameLayout, val panel: LinearLayout, val content: LinearLayout, val actions: LinearLayout, val widthDp: Int) {
        var dismissible = true
        var onDismiss: () -> Unit = {
            hide()
            lastState?.let(::render)
        }
        val visible: Boolean get() = overlay.visibility == View.VISIBLE

        fun show() = setVisible(true)
        fun hide() = setVisible(false)
        fun setVisible(on: Boolean) {
            if (on == visible) return
            overlay.visibility = if (on) View.VISIBLE else View.GONE
            if (on) {
                // It fades in and settles; with system animations off (or after an interrupted one) it is simply there.
                val animate = ValueAnimator.areAnimatorsEnabled()
                overlay.animate().cancel()
                panel.animate().cancel()
                overlay.alpha = if (animate) 0f else 1f
                panel.scaleX = if (animate) 0.96f else 1f
                panel.scaleY = panel.scaleX
                if (animate) {
                    overlay.animate().alpha(1f).setDuration(160).start()
                    panel.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
                }
            }
            syncBlur()
        }

        /** Buttons are added right to left in the order given: the first is the main one. */
        fun action(text: String, kind: Kind, onClick: () -> Unit) {
            actions.addView(
                button(text, kind, BtnSize.L, onClick = onClick),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (actions.childCount > 0) marginStart = dp(Pleos.GAP) },
            )
        }
    }

    /** While a popup is open the screen behind it is out of focus (API 31 and later; before that the scrim alone). */
    private fun syncBlur() {
        val on = popups.any { it.visible }
        if (on == blurred) return
        blurred = on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            scene.setRenderEffect(if (on) RenderEffect.createBlurEffect(dp(18).toFloat(), dp(18).toFloat(), Shader.TileMode.CLAMP) else null)
        }
    }

    private fun popup(widthDp: Int, title: String, description: String? = null, dismissible: Boolean = true, onDismissed: (() -> Unit)? = null): Popup {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(palette.scrim)
            visibility = View.GONE
            isClickable = true // nothing behind it can be tapped
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val pad = dp(48) + glassPad(Depth.LARGE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(Depth.LARGE, corner = Pleos.RADIUS_POPUP.toFloat(), fill = palette.popup)
            setPadding(pad, pad, pad, pad)
            isClickable = true // a tap on the panel is not a tap outside
        }
        panel.addView(label(Type.TITLE2, maxLines = 2).apply { setKorean(title) })
        if (description != null) {
            panel.addView(
                label(Type.BODY3, palette.text2, maxLines = 3).apply { setKorean(description) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
            )
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(Pleos.GAP) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        panel.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(32) })
        overlay.addView(panel)
        val p = Popup(overlay, panel, content, actions, widthDp)
        p.dismissible = dismissible
        if (onDismissed != null) {
            p.onDismiss = {
                onDismissed()
                p.hide()
                lastState?.let(::render)
            }
        }
        overlay.setOnClickListener { if (p.dismissible) p.onDismiss() }
        popups += p
        return p
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun label(style: Type, color: Int = palette.text, maxLines: Int = 1, center: Boolean = false) = TextView(this).apply {
        applyType(style)
        setTextColor(color)
        includeFontPadding = false
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
        if (center) gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun TextView.applyType(t: Type) {
        textSize = t.size.toFloat()
        typeface = Pleos.typeface(t.weight)
        lineHeight = (t.line * resources.displayMetrics.scaledDensity).roundToInt()
    }

    /** Text that may wrap: whole words only (Korean otherwise breaks inside a word on this platform). */
    private fun TextView.setKorean(value: String) {
        text = if (maxLines > 1) Pleos.keepAll(value) else value
    }

    /** PRIMARY: Hyundai Blue (Active Blue at night), one at a time. SECONDARY: glass. TERTIARY: text only (glass while pressed). FILLED: a chosen chip. */
    private enum class Kind { PRIMARY, SECONDARY, TERTIARY, FILLED }

    /**
     * A Pleos button: [size] gives the height, the minimum width, the padding, the radius and the label style. Pressed:
     * the pressed surface, a rim in the accent colour and 95 % size. [hitHeight]: a taller touch area than the visible
     * button.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun button(text: String, kind: Kind, size: BtnSize = BtnSize.M, hitHeight: Int? = null, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        stateListAnimator = null
        applyType(size.type)
        includeFontPadding = false
        maxLines = 2
        gravity = Gravity.CENTER
        minHeight = 0
        minWidth = 0
        val pad = glassPad(Depth.SMALL)
        minimumHeight = dp(hitHeight ?: size.height) + 2 * pad
        minimumWidth = dp(size.minWidth) + 2 * pad
        setPadding(dp(size.padH) + pad, pad, dp(size.padH) + pad, pad)
        tag = ButtonSpec(size, hitHeight)
        style(this, kind)
        setOnClickListener { onClick() }
        setOnTouchListener { v, e ->
            if (v.isEnabled) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                }
            }
            false
        }
    }

    private class ButtonSpec(val size: BtnSize, val hitHeight: Int?) {
        var kind: Kind? = null
        var night: Boolean? = null
    }

    /** Colours for [kind]; also how a button changes role (the find button steps back, a chip is chosen). */
    private fun style(b: Button, kind: Kind) {
        val spec = b.tag as ButtonSpec
        if (spec.kind == kind && spec.night == palette.night) return
        spec.kind = kind
        spec.night = palette.night
        val r = spec.size.radius.toFloat()
        val onFill = when (kind) {
            Kind.PRIMARY -> palette.onBrand
            Kind.SECONDARY -> palette.text
            Kind.TERTIARY -> palette.text2
            Kind.FILLED -> palette.onFilled
        }
        // A background that reports padding would replace what button() set (View.setBackground): keep it.
        val keep = intArrayOf(b.paddingLeft, b.paddingTop, b.paddingRight, b.paddingBottom)
        b.background = when (kind) {
            Kind.PRIMARY -> glass(Depth.SMALL, corner = r, style = GlassDrawable.Style.SOLID, fill = palette.brand, pressable = true)
            Kind.SECONDARY -> glass(Depth.SMALL, corner = r, pressable = true)
            Kind.FILLED -> glass(Depth.SMALL, corner = r, style = GlassDrawable.Style.SOLID, fill = palette.filled, pressable = true)
            // Text only: nothing until it is touched, then glass with a rim like the others.
            Kind.TERTIARY -> glass(Depth.SMALL, corner = r, style = GlassDrawable.Style.CLEAR, pressable = true)
        }
        b.setPadding(keep[0], keep[1], keep[2], keep[3])
        b.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(palette.textDisabled, onFill)))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    /** Room around a floating surface for its shadow (see [Glass.pad]). */
    private fun glassPad(depth: Depth): Int = Glass.pad(depth, resources.displayMetrics.density).roundToInt()

    private fun glass(
        depth: Depth?,
        corner: Float = Pleos.RADIUS_CARD.toFloat(),
        style: GlassDrawable.Style = GlassDrawable.Style.GLASS,
        fill: Int = palette.glass,
        pressable: Boolean = false,
    ) = GlassDrawable(palette, resources.displayMetrics.density, corner, depth, style, fill, pressable)

    private companion object {
        const val REQ_LOCATION = 11
        const val EXTRA_CMD = "zzan.cmd"
        const val EXTRA_DEMO = "zzan.demo"
        const val EXTRA_LEVEL = "zzan.level"
        const val EXTRA_PERSONA = "zzan.persona"
        const val EXTRA_PERCENT = "zzan.percent"
        const val EXTRA_ARG = "zzan.arg"

        /** At or above this width the two columns sit side by side (Pleos: one app across the app area). */
        const val WIDE_DP = 1200

        /** The amount above the character counts to a new value in this long. */
        const val COUNT_MS = 900L

        /** A spoken line: its voice bars move this long, and the line leaves the screen after this long. */
        const val VOICE_MS = 2_400L
        const val BUBBLE_MS = 12_000L

        /** The longest "how much more" bar in a comparison row. */
        const val BAR_DP = 96
        val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
