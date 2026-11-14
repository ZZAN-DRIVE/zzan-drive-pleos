package com.zzandrive.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import com.zzandrive.app.runtime.Approval
import com.zzandrive.app.runtime.AskStep
import com.zzandrive.app.runtime.AskUi
import com.zzandrive.app.runtime.TripUi
import com.zzandrive.app.runtime.UiState
import com.zzandrive.app.runtime.ZzanController
import com.zzandrive.app.ui.BtnSize
import com.zzandrive.app.ui.CharacterStageView
import com.zzandrive.app.ui.Depth
import com.zzandrive.app.ui.Neu
import com.zzandrive.app.ui.NeuDrawable
import com.zzandrive.app.ui.RingView
import com.zzandrive.app.ui.Palette
import com.zzandrive.app.ui.Pleos
import com.zzandrive.app.ui.Type
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
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * Parked-only product screen (no distractionOptimized: the system blocks it while driving, R-33).
 * It only draws [UiState] and forwards taps; all logic lives in [ZzanController].
 *
 * Laid out after the Pleos Connect design guide: a page-navigation bar on top (app name, what data the screen
 * runs on, [시연], [설정]) and two columns under it. Left, on the driver's side: the cumulative saving, the
 * character, its level, and the one primary action. Right: what the character says, one card at a time
 * (a question, a comparison, the routes, or the last trip), and the car's state. In a narrow (single) window the
 * same views stack. Sizes, colours and type come from ui/Palette.kt and ui/Pleos.kt and follow day/night.
 * Nothing redraws or animates while the screen is not visible.
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

    private lateinit var body: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var markerSlot: FrameLayout
    private lateinit var marker: TextView
    private lateinit var main: FrameLayout

    private lateinit var heroBlock: LinearLayout
    private lateinit var totalAmount: TextView
    private lateinit var netText: TextView
    private lateinit var stage: CharacterStageView
    private lateinit var levelBlock: LinearLayout
    private lateinit var levelPill: TextView
    private lateinit var personaText: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var remainingText: TextView
    private lateinit var accrualText: TextView
    private lateinit var findButton: Button

    private lateinit var rightCol: LinearLayout
    private lateinit var bubble: TextView
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
    private lateinit var celebrationSub: TextView
    private lateinit var tripCard: Card
    private var renderedTrip: TripUi? = null
    private lateinit var guideCard: Card
    private var renderedGuide: Any? = null
    private lateinit var locationNote: TextView
    private lateinit var dashboard: LinearLayout
    private lateinit var batteryTitle: TextView
    private lateinit var batteryRing: RingView
    private lateinit var batteryCaption: TextView
    private lateinit var thresholdRing: RingView
    private lateinit var thresholdCaption: TextView
    private lateinit var monthRing: RingView
    private lateinit var monthCaption: TextView
    private lateinit var statusLine: TextView
    private val ringColumns = mutableListOf<LinearLayout>()

    private lateinit var personaPopup: Popup
    private lateinit var locationPopup: Popup
    private lateinit var noticePopup: Popup
    private lateinit var settingsPopup: Popup
    private lateinit var resetPopup: Popup
    private lateinit var demoPopup: Popup
    private val popups = mutableListOf<Popup>()
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
        if (app.demo == null) app.live.vehicle.refresh() // catch up after being blocked or backgrounded
    }

    /** Not visible (background, another window, blocked while driving): stop drawing and animating. */
    override fun onStop() {
        started = false
        subscription?.cancel()
        subscription = null
        stage.pause()
        handler.removeCallbacks(midnight)
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
            s.demo -> "시연 데이터 · 가격·거리 예시"
            s.vehicleOrigin != null -> "실차 데이터 · 가격·거리 예시"
            else -> "차량 연결 중 · 가격·거리 예시"
        }
        marker.setTextColor(if (s.demo) palette.brand else palette.text2)
        renderSettings(s)
        renderDemos()

        val persona = s.persona
        val who = persona ?: Persona.ZZANDORI
        personaPopup.setVisible(persona == null || choosingPersona)
        personaPopup.dismissible = persona != null
        val askLocation = !s.demo && persona != null && !choosingPersona && !app.locationGranted && !locationExplained
        locationPopup.setVisible(askLocation)
        val covered = popups.any { it.visible }

        // Above the character: the cumulative saving (never below the amount the current level was reached at),
        // then this month's and today's 손익 as they are, negative included (R-35).
        totalAmount.text = WonFormat.screen(s.totalSavedWon)
        netText.text = SpannableStringBuilder().apply {
            append("이번 달 ")
            amount(s.monthNetWon)
            append(" · 오늘 ")
            amount(s.todayNetWon)
        }

        // The character moves only while parked (R-46); the system blocks this screen while driving.
        stage.logTag = tag
        stage.motionAllowed = s.parked != false && !covered
        val charLevel = s.character.level
        val celebrate = s.pendingCelebrations.maxOrNull()
        if (celebrate != null && celebrate != shownCelebration) {
            shownCelebration = celebrate
            idleScroll.scrollTo(0, 0) // the level-up card is first in the idle scroll
            stage.playLevelUp(who, celebrate) // before setCharacter, so the new food does not show first
            celebrationTitle.text = "${progress.food(celebrate)} 레벨 달성!"
            celebrationSub.text = if (celebrate == charLevel) {
                s.remainingWon?.let { "${progress.nextFood(celebrate)}까지 ${WonFormat.screen(it)} 남았어" } ?: "최고 레벨이야"
            } else {
                ""
            }
            celebrationSub.visibility = if (celebrationSub.text.isEmpty()) View.GONE else View.VISIBLE
            celebration.visibility = View.VISIBLE
            Log.i(tag, "SHOW level-up $celebrate")
        } else if (celebrate == null) {
            // Nothing pending (closed, driving, or the record was reset): the same level may be reached again.
            shownCelebration = -1
            celebration.visibility = View.GONE
        }
        stage.setCharacter(who, charLevel)
        val reaction = s.reaction
        if (reaction != null && reaction.id != playedReaction && celebrate == null && s.parked != false && !covered) {
            playedReaction = reaction.id
            stage.playReaction(reaction.happy)
        }

        // Under the character: the level, progress to the next food, what this level has collected.
        levelPill.text = "Lv.$charLevel ${progress.food(charLevel)}"
        personaText.text = who.displayName + if (stage.borrowed) " · 그림 준비 중" else ""
        val target = (s.remainingWon ?: 0) + s.character.accrualWon
        levelBar.max = if (s.remainingWon == null) 1 else target.toInt().coerceAtLeast(1)
        levelBar.progress = if (s.remainingWon == null) 1 else s.character.accrualWon.toInt()
        remainingText.text = s.remainingWon?.let { "${progress.nextFood(charLevel)}까지 ${WonFormat.screen(it)}" } ?: "최고 레벨이야"
        accrualText.text = if (s.remainingWon != null) {
            "${String.format(java.util.Locale.ROOT, "%,d", s.character.accrualWon)} / ${WonFormat.screen(target)}"
        } else {
            WonFormat.screen(s.character.accrualWon)
        }
        accrualText.visibility = if (wide) View.VISIBLE else View.GONE

        // One card at a time: a question, a decision, the routes; otherwise the level-up and the last trip.
        val decision = renderAsk(s) || renderRecommendation(s, unit) || renderRoutes(s)
        val tripShown = renderTrip(s, decision)
        // With nothing else to show, three lines on what the app does.
        renderGuide(s, !decision && !tripShown && celebration.visibility != View.VISIBLE)
        idleStack.visibility = if (decision) View.GONE else View.VISIBLE
        renderDashboard(s)

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
        bubble.setKorean(s.lastLine?.let { "“$it”" } ?: "")
        // In a narrow window an open card needs the room: the last line waits. The listening badge always shows.
        bubble.visibility = if (s.lastLine == null || (!wide && decision)) View.GONE else View.VISIBLE
        listenBadge.visibility = if (s.listening) View.VISIBLE else View.GONE
        locationNote.visibility = if (!s.demo && !app.locationGranted && (wide || !decision)) View.VISIBLE else View.GONE
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

    /**
     * The idle dashboard (Stitch "Circular Telemetry Gauges"): the battery or fuel with the alert level as a tick, the
     * alert level itself, and this month as a donut of what was saved against what was taken off. Each ring says its
     * value in the middle; the word under it says what the value means.
     */
    private fun renderDashboard(s: UiState) {
        val electric = s.energyType.isElectric
        batteryTitle.text = if (electric) "배터리" else "연료"
        val soc = s.socPercent
        batteryRing.fraction = ((soc ?: 0.0) / 100.0).toFloat()
        batteryRing.threshold = s.threshold / 100f
        batteryRing.value = soc?.let { "%.0f%%".format(it) } ?: "–"
        // At or below the alert level (the same rule the alert uses) the arc and the number turn to the warning colour.
        val low = soc != null && TriggerPolicy.fires(soc, s.threshold)
        batteryRing.arcColor = if (low) palette.chartRed else null
        batteryRing.valueColor = if (low) palette.negative else palette.text
        batteryRing.contentDescription = "${batteryTitle.text} ${batteryRing.value}, 알림 기준 ${s.threshold}%"
        batteryCaption.text = s.energy?.let { "%.1f%s".format(it, s.energyType.unit) } ?: "확인 중"
        thresholdRing.fraction = s.threshold / 100f
        thresholdRing.value = "${s.threshold}%"
        thresholdRing.contentDescription = "알림 기준 ${s.threshold}%"
        thresholdCaption.text = "이 아래면 먼저 알려요"
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
        monthRing.contentDescription = "이번 달 손익 ${money(s.monthNetWon)}"
        // No-break spaces keep each word with its amount: a narrow column breaks at the dot, never cuts the deduction.
        monthCaption.setKorean("아낀\u00A0${WonFormat.screen(saved)} · 빠진\u00A0${WonFormat.screen(taken)}")
        statusLine.text = listOf(
            when {
                s.refilling -> if (electric) "충전 확인 중" else "주유 확인 중"
                s.parked == true -> "정차 중"
                s.parked == false -> "주행 중"
                else -> "차량 연결 중"
            },
            "음성 답 " + when {
                !s.voiceAvailable -> "화면으로만"
                s.listening -> "듣는 중…"
                s.voiceAnswers -> "켜짐"
                else -> "꺼짐"
            },
        ).joinToString(" · ")
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
     * The comparison card: the conclusion first, at most three rows, and where the numbers come from. Open while a
     * decision or a trip is open; afterwards it folds into the result line. "No stop needed" (spec D-34) uses the same
     * card with a single [확인].
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
        recCard.sub(
            listOfNotNull(
                "예상".takeIf { rec.creditRule != CreditRule.NONE && !rec.recommendsBaseline }, // the title stays one phrase
                "시간비용 포함".takeIf { whole },
                rec.referenceName?.takeIf { !whole }?.let { "기준 위치: $it" },
                rec.fillLabel?.let { "넣을 양: $it" } ?: "넣을 양: ${rec.plannedQuantity.stripTrailingZeros().toPlainString()}$unit",
                rec.wagePerHour?.let { "시급 ${WonFormat.screen(it.toWon())}" },
            ).joinToString(" · "),
        )
        recCard.body.removeAllViews()
        val withPrice = rec.mode == CompareMode.COST_INCLUDED
        recCard.body.addView(
            when {
                whole -> rowHeader("장소 · 단가 · 거리 · 시간 · 통행료", "총 지출 · 최저 대비")
                withPrice -> rowHeader("장소 · 도로 거리 · 단가", "합계")
                else -> rowHeader("장소 · 도로 거리 · 단가", "")
            },
        )
        rec.ranked.take(RoutePolicy.SHOWN).forEach { c ->
            val isBest = c.offer.id == best.id
            val row = if (whole) {
                // 기획 계산식: each route's whole spend, the lowest as 0원 and the others as what they lose against it.
                val stop = c.offer.id != RoutePolicy.NO_STOP_ID
                val leg = c.offer.route
                val meta = listOfNotNull(
                    "${WonFormat.screen(c.offer.unitPrice.toWon())}/$unit".takeIf { stop },
                    leg?.let { "${it.km.stripTrailingZeros().toPlainString()}km" },
                    leg?.let { duration(it.minutes.toInt()) },
                    leg?.let { if (it.tollWon.signum() > 0) "통행료 ${WonFormat.screen(it.tollWon.toWon())}" else "통행료 없음" },
                ).joinToString(" · ")
                val diff = rec.best.total.subtract(c.total).toWon()
                listRow(
                    title = c.offer.name, badge = if (isBest) badge("추천", Badge.BRAND) else null, meta = meta,
                    amount = WonFormat.screen(c.total.toWon()), sub = if (diff == 0L) "0원" else money(diff),
                    subColor = if (diff == 0L) palette.text2 else palette.negative, highlight = isBest,
                )
            } else {
                val tagged = when {
                    isBest -> badge("추천", Badge.BRAND)
                    c.offer.id == rec.baseline.offer.id -> badge("가장 가까움", Badge.NEUTRAL)
                    else -> null
                }
                val km = c.offer.oneWayKm.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                listRow(
                    title = c.offer.name, badge = tagged, meta = "도로 ${km}km · ${WonFormat.screen(c.offer.unitPrice.toWon())}/$unit",
                    amount = if (withPrice) WonFormat.screen(c.total.toWon()) else null, highlight = isBest,
                )
            }
            recCard.body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        // In "nearest one only" the shown place is itself past 5 km, so the excluded count would mislead.
        val excluded = if (rec.mode == CompareMode.NEAREST_ONLY) 0 else rec.excludedIds.size
        val efficiency = "${if (rec.energyType.isElectric) "전비" else "연비"} " +
            "${rec.efficiency.kmPerUnit.stripTrailingZeros().toPlainString()}km/$unit: ${rec.efficiency.origin.label}"
        recCard.notes(
            listOfNotNull(
                "도로로 5km 넘는 ${excluded}곳은 뺐어요".takeIf { excluded > 0 },
                when {
                    rec.noStop -> "남은 ${if (rec.energyType.isElectric) "배터리" else "연료"}로 목적지까지 갈 수 있고, 들르지 않는 편이 덜 들어요."
                    rec.creditRule == CreditRule.NONE -> "가격을 비교하지 않은 안내라 절약액은 쌓이지 않아요."
                    else -> "예상치예요. 차가 넣은 양을 확인해야 쌓여요."
                },
                if (whole) {
                    "총 지출 = 전체 주행 연료비 + 넣을 양 × (이곳 단가 − 기준 단가) + 통행료 + 주행 시간 × 시급\n" +
                        "기준 단가 ${rec.travelPrice?.let { WonFormat.screen(it, 2) }}/$unit: ${DataOrigin.SURVEY.label} · $efficiency · 가격·경로: ${best.origin.label}"
                } else {
                    "가격·도로 거리: ${best.origin.label} · $efficiency"
                },
            ).joinToString("\n"),
        )
        return true
    }

    /**
     * 기획서 3-2: the routes before departure with the best first in the title, each route's value with the time
     * counted at the wage over its 운행비 (toll included on expressways). Example data and "not saved" are said on the card.
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
        val best = c.best
        val allStop = c.results.all { it.needsStop }
        routeCard.title.setKorean(
            if (allStop) "${c.destinationName}까지 어느 길이든 알림 기준 아래로 내려가요" else "${c.destinationName}까지 ${Josa.ga(best.option.name)} 나아요",
        )
        val energyName = when (s.energyType) {
            EnergyType.GASOLINE -> "휘발유"
            EnergyType.DIESEL -> "경유"
            EnergyType.ELECTRIC -> "전기"
        }
        routeCard.sub(
            "출발 전 · 시급 ${WonFormat.screen(c.wagePerHour.toWon())} · " +
                "${if (s.energyType.isElectric) "전비" else "연비"} ${c.efficiency.kmPerUnit.stripTrailingZeros().toPlainString()}km/$unit · " +
                "$energyName ${WonFormat.screen(c.unitPrice, 2)}/$unit",
        )
        routeCard.body.removeAllViews()
        routeCard.body.addView(rowHeader("경로 · 거리 · 시간 · 통행료", "시간 포함 · 운행비"))
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
                r.needsStop -> badge(if (s.energyType.isElectric) "도중 충전 필요" else "도중 주유 필요", Badge.NEUTRAL)
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
        routeCard.notes(
            "시간 포함 = 운행비 + 걸리는 시간 × 시급. 이 비교는 절약액으로 쌓지 않아요.\n" +
                when {
                    allStop -> "어느 길로 가도 도착 전에 알림 기준(${s.threshold}%) 아래로 내려가요. 알림이 뜨면 가는 길 " +
                        "${if (s.energyType.isElectric) "충전소" else "주유소"}를 들르는 경로를 비교해 드려요.\n"
                    c.results.any { it.needsStop } -> "알림 기준 아래로 내려가는 길은 가는 길 주유소를 함께 봐야 해서 추천에서 뺐어요.\n"
                    else -> ""
                } +
                "경로·통행료: ${c.origin.label} · ${if (s.energyType.isElectric) "전비" else "연비"}: ${c.efficiency.origin.label} · 단가: ${DataOrigin.SURVEY.label}",
        )
        return true
    }

    /** The last trip's driving deductions (spec D-38), parked, when no decision card is open. */
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
        tripCard.title.text = SpannableStringBuilder("이번 주행 분석 ").apply {
            val start = length
            append(money(-p.fullWon))
            setSpan(ForegroundColorSpan(palette.negative), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tripCard.sub(
            if (trip.appliedWon < p.fullWon) {
                "레벨 적립액 ${WonFormat.screen(trip.appliedWon)}까지만 줄었어요 · 누적 ${WonFormat.screen(trip.cumulativeWon)}은 그대로예요"
            } else {
                "레벨 적립액에서 뺐어요 · 레벨은 내려가지 않아요"
            },
        )
        tripCard.body.removeAllViews()
        val parts = listOfNotNull(
            Triple("급가속 ${p.accelEvents}건", p.accelCost.toWon(), palette.chartRed).takeIf { p.accelEvents > 0 },
            Triple("공회전 ${p.idleSeconds / 60}분 (3분 초과 ${p.idleEvalSeconds / 60}분)", p.idleCost.toWon(), palette.chartGold).takeIf { p.idleEvalSeconds > 0 },
            Triple("과속 ${p.speedingKm.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()}km", p.speedingCost.toWon(), palette.chartBlue)
                .takeIf { p.speedingCost.signum() > 0 },
        )
        val sum = parts.sumOf { it.second }.coerceAtLeast(1)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val donut = RingView(this, palette).apply {
            segments = parts.map { it.second.toFloat() / sum to it.third }
            value = money(-p.fullWon).removeSuffix("원")
            valueColor = palette.negative
            valueSp = 36f
            caption = "${parts.size}가지"
            contentDescription = "이번 주행 ${money(-p.fullWon)}"
        }
        row.addView(donut, LinearLayout.LayoutParams(dp(if (wide) 244 else 208), dp(if (wide) 244 else 208)))
        val legend = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parts.forEachIndexed { i, (name, won, color) ->
            if (i > 0) legend.addView(View(this).apply { setBackgroundColor(palette.divider) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(72)
            }
            line.addView(View(this).apply { background = rounded(color, Pleos.RADIUS_PILL) }, LinearLayout.LayoutParams(dp(16), dp(16)))
            line.addView(label(Type.BODY2, maxLines = 2).apply { setKorean(name) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            line.addView(label(Type.BODY2_STRONG, palette.negative).apply { text = money(-won); fontFeatureSettings = "tnum" })
            legend.addView(line)
        }
        row.addView(legend, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(Pleos.GAP) })
        tripCard.body.addView(row)
        tripCard.notes(
            listOfNotNull(
                "급제동 ${p.decelEvents}건은 기록만 해요".takeIf { p.decelEvents > 0 },
                "제한속도: ${DataOrigin.PLAN_EXAMPLE.label}".takeIf { p.speedingKm.signum() > 0 },
                "단가: ${DataOrigin.SURVEY.label}",
            ).joinToString(" · "),
        )
        return true
    }

    /** The idle right column: how the app saves money, in three short lines. */
    private fun renderGuide(s: UiState, show: Boolean) {
        guideCard.root.visibility = if (show) View.VISIBLE else View.GONE
        val key = listOf(s.energyType.isElectric, s.threshold, palette.night, wide)
        if (!show || key == renderedGuide) return
        renderedGuide = key
        val electric = s.energyType.isElectric
        guideCard.title.text = "이렇게 아껴요"
        guideCard.body.removeAllViews()
        listOf(
            "${if (electric) "배터리" else "연료"}가 ${s.threshold}% 아래로 내려가면 먼저 알려요",
            "가까운 곳과 더 싼 곳을 가는 비용까지 넣어 비교해요",
            "차가 확인한 만큼만 절약액으로 쌓아요",
        ).forEachIndexed { i, line ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(72)
            }
            row.addView(
                label(Type.LABEL1_STRONG, palette.text, center = true).apply {
                    text = "${i + 1}"
                    gravity = Gravity.CENTER
                    background = neu(NeuDrawable.Style.INSET, Depth.SMALL, shape = NeuDrawable.Shape.CIRCLE)
                },
                LinearLayout.LayoutParams(dp(48), dp(48)),
            )
            row.addView(
                label(if (wide) Type.BODY2 else Type.BODY3, maxLines = 2).apply { setKorean(line) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) },
            )
            guideCard.body.addView(row)
        }
        // The narrow window leaves out the source line (the card sits under the rings there and scrolls).
        guideCard.notes(if (wide) "가격·거리: ${DataOrigin.PLAN_EXAMPLE.label} · 연비·전비: ${DataOrigin.SURVEY.label}" else "")
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
                voiceRow.addView(label(Type.LABEL1, palette.text3, maxLines = 2).apply { setKorean("이 기기에서는 음성 인식을 쓸 수 없어 화면으로 답해요.") })
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
                    title = title, badge = if (id == running) badge("실행 중", Badge.NEUTRAL) else null, meta = sub, metaStyle = Type.LABEL1,
                    metaColor = palette.text3, highlight = true, onClick = onClick,
                ),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (demoRows.childCount > 0) topMargin = dp(8) },
            )
        }
        fun start(which: String) {
            demoPopup.hide()
            startDemo(which)
        }
        row("B", "시연 B · 충전", "전기차 · 충전소 비교와 정산") { start("B") }
        row("A", "시연 A · 주유", "출발 전 경로 비교와 주유소 3곳 비교") { start("A") }
        row("C", "시연 C · 운전 습관", "급가속·공회전·과속 차감") { start("C") }
        row("off", "실차 모드", "차량 데이터로 동작") { start("off") }
        row("reset", "처음부터", "기록과 레벨을 지워요") {
            demoPopup.hide()
            resetPopup.show() // one tap must not wipe the real record
            lastState?.let(::render)
        }
    }

    // ------------------------------------------------------------------ layout

    /**
     * Wide window (the app across the app area): two columns. Narrow window (one of two): a band with the amount and
     * the level beside a small character, the cards under it, the primary action at the bottom. The same views in
     * both; the left column's blocks have fixed heights, so opening a card never resizes the character.
     */
    private fun applyLayout(widthDp: Int) {
        wide = widthDp >= WIDE_DP
        // The last trip is the news: in the narrow window it comes before the rings, so it starts on screen.
        idleStack.removeView(tripCard.root)
        idleStack.addView(tripCard.root, idleStack.indexOfChild(dashboard) + if (wide) 1 else 0)
        val movable = listOf(marker, heroBlock, stage, levelBlock, findButton, rightCol)
        movable.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        main.removeAllViews()
        val side = dp(if (wide) 32 else 24)
        body.setPadding(side, dp(16), side, side)
        titleText.applyType(if (wide) Type.TITLE2 else Type.TITLE3)
        // Raised surfaces are drawn inside their views, so their padding adds the room the shadows need.
        val cardPad = dp(if (wide) 32 else 24) + neuPad(Depth.LARGE)
        listOf(askCard, recCard, routeCard, tripCard, guideCard).forEach { it.root.setPadding(cardPad, cardPad, cardPad, cardPad) }
        celebration.setPadding(cardPad, cardPad, cardPad, cardPad)
        val ring = dp(if (wide) 264 else 228)
        listOf(batteryRing, thresholdRing, monthRing).forEach { it.layoutParams = LinearLayout.LayoutParams(ring, ring) }

        if (wide) {
            markerSlot.addView(marker, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(heroBlock)
            left.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
            left.addView(levelBlock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            left.addView(findButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(BtnSize.L.height) + 2 * neuPad(Depth.SMALL)).apply { topMargin = dp(8) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val leftWidth = (widthDp * 0.38f).roundToInt().coerceIn(520, 640)
            row.addView(left, LinearLayout.LayoutParams(dp(leftWidth), ViewGroup.LayoutParams.MATCH_PARENT))
            row.addView(rightCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(32) })
            main.addView(row)
        } else {
            val bandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            bandText.addView(marker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
            bandText.addView(heroBlock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            bandText.addView(levelBlock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            val band = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            band.addView(bandText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            band.addView(stage, LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = dp(16) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(band, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(334)))
            col.addView(rightCol, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) })
            col.addView(findButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(BtnSize.L.height) + 2 * neuPad(Depth.SMALL)).apply { topMargin = dp(4) })
            main.addView(col)
        }

        // Popups follow the window: the guide's widths, never wider than the window.
        val windowPx = (widthDp * resources.displayMetrics.density).roundToInt()
        popups.forEach { p ->
            p.panel.layoutParams = FrameLayout.LayoutParams(minOf(dp(p.widthDp), windowPx - dp(48)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                .apply { setMargins(0, dp(16), 0, dp(16)) }
        }
        // Cards and chips are drawn for the window they are in.
        renderedAsk = null
        renderedRec = null
        renderedRoutes = null
        renderedTrip = null
        renderedGuide = null
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
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- Page navigation bar. The top-centre 320×60 of the window belongs to the window handle: nothing to tap there.
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val appMark = label(Type.TITLE2, palette.onBrand, center = true).apply {
            text = "짠"
            gravity = Gravity.CENTER
            background = rounded(palette.brand, 20)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        topBar.addView(appMark, LinearLayout.LayoutParams(dp(80), dp(80)))
        titleText = label(Type.TITLE2).apply { text = "짠드라이브" }
        topBar.addView(titleText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16) })
        marker = label(Type.LABEL1_STRONG, palette.text2).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = neu(NeuDrawable.Style.INSET, Depth.SMALL, corner = 999f)
            setPadding(dp(24), 0, dp(24), 0)
        }
        markerSlot = FrameLayout(this)
        topBar.addView(markerSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply { marginStart = dp(24) })
        topBar.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        topBar.addView(button("시연", Kind.SECONDARY, BtnSize.S) {
            demoPopup.show()
            lastState?.let(::render)
        })
        topBar.addView(
            button("설정", Kind.SECONDARY, BtnSize.S) {
                settingsPopup.show()
                lastState?.let(::render)
            },
            // The two buttons share their shadow room: 20 dp between the visible shapes.
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(20) - 2 * neuPad(Depth.SMALL) },
        )
        // [설정]'s shadow room lies in the body's right padding, so its visible edge lines up with the content edge and,
        // in the 840 dp window, [시연] stays clear of the window handle (top-centre 320×60). body draws into its padding.
        body.clipToPadding = false
        body.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90)).apply { marginEnd = -neuPad(Depth.SMALL) })
        main = FrameLayout(this)
        body.addView(main, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })
        root.addView(body)

        // ---- Left: the cumulative saving, the character, its level, the primary action.
        heroBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroBlock.addView(label(Type.BODY3, palette.text2).apply { text = "누적 절약액" })
        totalAmount = label(Type.HEADLINE1)
        heroBlock.addView(totalAmount)
        netText = label(Type.BODY2, palette.text2)
        heroBlock.addView(netText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        stage = CharacterStageView(this).apply {
            contentDescription = "캐릭터"
            isClickable = true
            setOnClickListener { playTap() }
        }

        levelBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val levelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        levelPill = label(Type.BODY2_STRONG, Palette.ON_LEVEL).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Palette.LEVEL, Pleos.RADIUS_PILL)
            setPadding(dp(20), 0, dp(20), 0)
        }
        levelRow.addView(levelPill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        personaText = label(Type.LABEL1, palette.text3)
        levelRow.addView(personaText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16) })
        levelBlock.addView(levelRow)
        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressDrawable = LayerDrawable(
                arrayOf<Drawable>(rounded(palette.track, 6), ClipDrawable(rounded(palette.brand, 6), Gravity.START, ClipDrawable.HORIZONTAL)),
            ).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
        }
        levelBlock.addView(levelBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)).apply { topMargin = dp(12) })
        val caption = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        remainingText = label(Type.BODY2)
        caption.addView(remainingText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        accrualText = label(Type.BODY2, palette.text3)
        caption.addView(accrualText)
        levelBlock.addView(caption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        findButton = button("충전소 찾기", Kind.PRIMARY, BtnSize.L) { controller?.findStation() }

        // ---- Right: what the character says, the result, one card, the car's state.
        rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bubble = label(Type.BODY3, maxLines = 2).apply {
            background = neu(NeuDrawable.Style.RAISED, Depth.MEDIUM, corner = 28f)
            val pad = neuPad(Depth.MEDIUM)
            setPadding(pad + dp(28), pad + dp(18), pad + dp(28), pad + dp(18))
            visibility = View.GONE
        }
        rightCol.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        listenBadge = label(Type.LABEL1_STRONG, palette.onFilled).apply {
            text = "듣는 중… 말로 답해도 돼요"
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(palette.filled, Pleos.RADIUS_PILL)
            setPadding(dp(20), 0, dp(20), 0)
            visibility = View.GONE
        }
        rightCol.addView(listenBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
            bottomMargin = dp(8)
            marginStart = neuPad(Depth.MEDIUM)
        })
        resultText = label(Type.BODY3_STRONG, maxLines = 3).apply {
            background = neu(NeuDrawable.Style.INSET, Depth.MEDIUM, corner = 24f)
            setPadding(dp(28), dp(18), dp(28), dp(18))
            visibility = View.GONE
        }
        rightCol.addView(resultText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            val pad = neuPad(Depth.MEDIUM)
            setMargins(pad, dp(4), pad, dp(8))
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

        // With no decision open: the level-up, then the last trip.
        idleStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        celebration = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = neu(NeuDrawable.Style.RAISED, Depth.LARGE, corner = Pleos.RADIUS_CARD.toFloat())
            visibility = View.GONE
        }
        celebrationTitle = label(Type.HEADLINE3, maxLines = 2)
        celebrationSub = label(Type.BODY2, palette.text2, maxLines = 2)
        celebration.addView(celebrationTitle)
        celebration.addView(celebrationSub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        celebration.addView(
            button("좋아!", Kind.PRIMARY, BtnSize.L) {
                controller?.celebrationShown(shownCelebration)
                celebration.visibility = View.GONE
                lastState?.let(::render)
            },
            LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(Pleos.GAP) },
        )
        idleStack.addView(celebration, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // The dashboard: three rings and one status line, no boxes.
        dashboard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ringsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        fun ringColumn(title: TextView, ring: RingView, caption: TextView): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(title)
            addView(ring)
            addView(caption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(-4) })
        }
        batteryTitle = label(Type.BODY2_STRONG, palette.text2, center = true)
        batteryRing = RingView(this, palette)
        batteryCaption = label(Type.BODY3, palette.text3, center = true)
        thresholdRing = RingView(this, palette).apply { arcColor = palette.chartGold }
        thresholdCaption = label(Type.BODY3, palette.text3, center = true)
        monthRing = RingView(this, palette).apply { valueSp = 36f }
        monthCaption = label(Type.BODY3, palette.text3, maxLines = 2, center = true)
        listOf(
            ringColumn(batteryTitle, batteryRing, batteryCaption),
            ringColumn(label(Type.BODY2_STRONG, palette.text2, center = true).apply { text = "알림 기준" }, thresholdRing, thresholdCaption),
            ringColumn(label(Type.BODY2_STRONG, palette.text2, center = true).apply { text = "이번 달 손익" }, monthRing, monthCaption),
        ).forEach { c ->
            ringColumns += c
            ringsRow.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        dashboard.addView(ringsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        // The car's state as one sunken pill under the rings (shown, not tapped), apart from the ring captions.
        statusLine = label(Type.LABEL1, palette.text2, center = true).apply {
            gravity = Gravity.CENTER
            background = neu(NeuDrawable.Style.INSET, Depth.SMALL, corner = 999f)
            setPadding(dp(28), 0, dp(28), 0)
        }
        dashboard.addView(
            statusLine,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(20)
            },
        )
        idleStack.addView(dashboard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
        tripCard = card()
        tripCard.footer.visibility = View.GONE
        idleStack.addView(tripCard.root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        guideCard = card()
        guideCard.footer.visibility = View.GONE
        idleStack.addView(guideCard.root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        listOf(askCard, recCard, routeCard).forEach {
            slot.addView(it.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // When the cards are taller than the room (a narrow window), the bottom edge fades to say there is more below.
        idleScroll = ScrollView(this).apply {
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(40))
            addView(idleStack)
        }
        slot.addView(idleScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rightCol.addView(slot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        locationNote = label(Type.LABEL1, palette.text3).apply {
            text = "위치 권한 꺼짐 · 들른 곳 확인 불가"
            visibility = View.GONE
        }
        rightCol.addView(locationNote, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        buildPopups(root)
        return root
    }

    private fun buildPopups(root: FrameLayout) {
        // Character choice: nothing moves behind a choice, so the pictures are still.
        personaPopup = popup(Pleos.POPUP_LARGE, "함께할 캐릭터를 골라 주세요", "목소리와 말투가 달라요. 바꿔도 레벨과 금액은 그대로예요.") {
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
                setCharacter(p, 0)
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
            "추천한 충전소·주유소에 들렀는지 확인하고, 현재 위치 근처를 찾을 때만 위치를 써요.",
            "위치는 기기 안에만 저장하고 밖으로 보내지 않아요. 이동 경로도 남기지 않아요.",
            "에뮬레이터에서는 다음 창에 '알 수 없는 작업'이라고 나와요. Pleos 위치 권한을 묻는 창이에요.",
            "허용하지 않아도 시연은 그대로 볼 수 있어요.",
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
                label(Type.LABEL1, palette.text3, maxLines = 3).apply { setKorean(help) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(12)
                },
            )
        }
        section("알림 시작 기준", "남은 양이 이 아래로 내려가면 캐릭터가 먼저 알려요. 차량 경고등 신호는 앱이 읽을 수 없어 이 값을 써요.", first = true)
        thresholdBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsPopup.content.addView(thresholdBox)
        section("음성으로 답하기", "캐릭터가 물은 뒤 5초 동안 들어요. \"응\", \"아니\", \"가득\"처럼 짧게 답하면 돼요. 답이 없으면 아무것도 실행하지 않아요.")
        voiceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        settingsPopup.content.addView(voiceRow)
        section("시간의 값 (시급)", "경로와 가는 길 주유소를 비교할 때 1시간을 이 금액으로 쳐요. 출발 전 경로 비교는 비교만 하고 쌓지 않아요.")
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

        demoPopup = popup(Pleos.POPUP_MEDIUM, "시연", "시연은 예시 데이터로 돌아가요. 실차 모드는 차량에서 읽은 값으로 동작해요.")
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
        popups.forEach { root.addView(it.overlay) }
    }

    // ------------------------------------------------------------------ parts

    /** A card: headline, one line under it, a body that scrolls only when the card runs out of room, buttons that stay. */
    private inner class Card(val root: LinearLayout, val title: TextView, val sub: TextView, val body: LinearLayout, val notes: TextView, val footer: LinearLayout) {
        /** The line under the headline; no room is kept for an empty one. */
        fun sub(text: String) {
            sub.setKorean(text)
            sub.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }

        fun notes(text: String) {
            notes.setKorean(text)
            notes.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun card(): Card {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = neu(NeuDrawable.Style.RAISED, Depth.LARGE, corner = Pleos.RADIUS_CARD.toFloat())
            visibility = View.GONE
        }
        val title = label(Type.TITLE1, maxLines = 2)
        val sub = label(Type.LABEL1, palette.text3, maxLines = 2).apply { visibility = View.GONE }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val notes = label(Type.LABEL1, palette.text3, maxLines = 8).apply { visibility = View.GONE }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(body)
        inner.addView(notes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(title)
        root.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        root.addView(ScrollView(this).apply { addView(inner) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) })
        root.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        return Card(root, title, sub, body, notes, footer)
    }

    /** What the two sides of the rows under it mean. */
    private fun rowHeader(left: String, right: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(if (wide) 24 else 16), 0, dp(if (wide) 24 else 16), 0)
        addView(label(Type.LABEL1, palette.text3).apply { text = left }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(label(Type.LABEL1, palette.text3).apply { text = right })
    }

    /**
     * A list row (Pleos list item: at least 112 high, radius 24): a name with an optional badge over a detail line on
     * the left, an amount over a second amount on the right. [highlight] gives it the tint (the recommended one, or a
     * row that can be tapped).
     */
    private fun listRow(
        title: String,
        badge: View? = null,
        meta: String? = null,
        amount: String? = null,
        sub: String? = null,
        subColor: Int = palette.text2,
        highlight: Boolean = false,
        metaStyle: Type = if (wide) Type.BODY2 else Type.BODY3,
        metaColor: Int = palette.text2,
        onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(112)
            setPadding(dp(if (wide) 24 else 16), dp(20), dp(if (wide) 24 else 16), dp(20))
            if (highlight) {
                background = if (onClick != null) {
                    neu(NeuDrawable.Style.RAISED, Depth.SMALL, corner = Pleos.RADIUS_ROW.toFloat(), pressable = true).also {
                        val pad = neuPad(Depth.SMALL)
                        setPadding(paddingLeft + pad, paddingTop + pad, paddingRight + pad, paddingBottom + pad)
                    }
                } else {
                    neu(NeuDrawable.Style.INSET, Depth.MEDIUM, corner = Pleos.RADIUS_ROW.toFloat())
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
                label(metaStyle, metaColor, maxLines = 2).apply { setKorean(meta) },
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
                right.addView(
                    label(Type.BODY2_STRONG, subColor).apply { text = sub; fontFeatureSettings = "tnum" },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) },
                )
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
            background = if (kind == Badge.BRAND) rounded(palette.brand, Pleos.RADIUS_PILL) else neu(NeuDrawable.Style.INSET, Depth.SMALL, corner = 999f)
            setPadding(dp(14), 0, dp(14), 0)
        }

    /**
     * A Pleos system popup: scrim, a panel with radius 48 and padding 48, a title, an optional description, content that
     * scrolls when it is taller than the window, and large buttons. [dismissible]: Back and a tap outside close it.
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
            overlay.visibility = if (on) View.VISIBLE else View.GONE
        }

        /** Buttons are added right to left in the order given: the first is the main one. */
        fun action(text: String, kind: Kind, onClick: () -> Unit) {
            actions.addView(
                button(text, kind, BtnSize.L, onClick = onClick),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (actions.childCount > 0) marginStart = dp(Pleos.GAP) },
            )
        }
    }

    private fun popup(widthDp: Int, title: String, description: String? = null, dismissible: Boolean = true, onDismissed: (() -> Unit)? = null): Popup {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(palette.scrim)
            visibility = View.GONE
            isClickable = true // nothing behind it can be tapped
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = if (palette.night) outlined(palette.popup, Pleos.RADIUS_POPUP) else rounded(palette.popup, Pleos.RADIUS_POPUP)
            elevation = dp(24).toFloat()
            setPadding(dp(48), dp(48), dp(48), dp(48))
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

    /** Cards sit on a page of almost the same colour, so a 1 px outline is what separates them (both themes). */
    private fun outlined(color: Int, radiusDp: Int) = rounded(color, radiusDp).apply { setStroke(dp(1).coerceAtLeast(1), palette.divider) }

    private fun pressable(color: Int, pressed: Int, radiusDp: Int): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, radiusDp))
        addState(intArrayOf(), rounded(color, radiusDp))
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

    /** PRIMARY: Hyundai Blue (Active Blue at night), one at a time. SECONDARY: raised, page-coloured. TERTIARY: text only (sinks while pressed). FILLED: a chosen chip. */
    private enum class Kind { PRIMARY, SECONDARY, TERTIARY, FILLED }

    /**
     * A Pleos button: [size] gives the height, the minimum width, the padding, the radius and the label style. Pressed:
     * the pressed colour and 95 % size. [hitHeight]: a taller touch area than the visible button (the top bar).
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
        val pad = neuPad(Depth.SMALL)
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
            // Raised soft-UI surfaces that sink into a well (with a rim in the accent colour) while pressed.
            Kind.PRIMARY -> neu(NeuDrawable.Style.RAISED, Depth.SMALL, corner = r, fill = palette.brand, pressable = true)
            Kind.SECONDARY -> neu(NeuDrawable.Style.RAISED, Depth.SMALL, corner = r, pressable = true)
            Kind.FILLED -> neu(NeuDrawable.Style.RAISED, Depth.SMALL, corner = r, fill = palette.filled, pressable = true)
            // Text only: flat and clear, it sinks into a well like the others while pressed.
            Kind.TERTIARY -> neu(NeuDrawable.Style.FLAT, Depth.SMALL, corner = r, fill = NeuDrawable.NONE, pressable = true)
        }
        b.setPadding(keep[0], keep[1], keep[2], keep[3])
        b.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(palette.textDisabled, onFill)))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    /** Room around a raised surface for its shadows (see [Neu.pad]). */
    private fun neuPad(depth: Depth): Int = Neu.pad(depth, resources.displayMetrics.density).roundToInt()

    private fun neu(
        style: NeuDrawable.Style,
        depth: Depth,
        corner: Float = Pleos.RADIUS_CARD.toFloat(),
        fill: Int = palette.page,
        pressable: Boolean = false,
        shape: NeuDrawable.Shape = NeuDrawable.Shape.ROUND_RECT,
    ) = NeuDrawable(palette, resources.displayMetrics.density, shape, corner, depth, style, fill, pressable)

    private companion object {
        const val REQ_LOCATION = 11
        const val EXTRA_CMD = "zzan.cmd"
        const val EXTRA_DEMO = "zzan.demo"
        const val EXTRA_LEVEL = "zzan.level"
        const val EXTRA_PERSONA = "zzan.persona"
        const val EXTRA_ARG = "zzan.arg"

        /** At or above this width the two columns sit side by side (Pleos: one app across the app area). */
        const val WIDE_DP = 1200
        val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
