package com.zzandrive.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.text.LineBreakConfig
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
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
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import com.zzandrive.app.runtime.Approval
import com.zzandrive.app.runtime.AskStep
import com.zzandrive.app.runtime.AskUi
import com.zzandrive.app.runtime.UiState
import com.zzandrive.app.runtime.ZzanController
import com.zzandrive.app.ui.CharacterStageView
import com.zzandrive.app.ui.Palette
import com.zzandrive.app.ui.SpotlightDrawable
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
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Parked-only product screen (no distractionOptimized: the system blocks it while driving, R-33).
 * It only draws [UiState] and forwards taps; all logic lives in [ZzanController].
 *
 * Minimal home (기획서 3-4·6): the date and this month's 손익 above the character, the character in
 * the middle, its level and the next food below. Everything else sits in a rail on the driver's side
 * (left): what data the screen runs on, the buttons, and the car's state. The recommendation card
 * opens beside (or, in a narrow window, below) the character only while a decision or a trip is open.
 * Colors come from the character (ui/Palette.kt) and follow the system's day/night setting.
 * Window size changes (Pleos split windows) re-arrange the same views without recreating the
 * activity, and nothing redraws or animates while the screen is not visible.
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

    private lateinit var rail: LinearLayout
    private lateinit var modeChip: TextView
    private lateinit var statusText: TextView
    private lateinit var content: LinearLayout
    private lateinit var home: LinearLayout
    private lateinit var side: ScrollView
    private lateinit var dateText: TextView
    private lateinit var monthAmount: TextView
    private lateinit var todayText: TextView
    private lateinit var stage: CharacterStageView
    private lateinit var celebration: LinearLayout
    private lateinit var celebrationTitle: TextView
    private lateinit var celebrationSub: TextView
    private lateinit var levelPill: TextView
    private lateinit var personaText: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var remainingText: TextView
    private lateinit var totalText: TextView
    private lateinit var resultText: TextView
    private lateinit var bubble: TextView
    private lateinit var listenBadge: TextView
    private lateinit var recTitle: TextView
    private lateinit var recPlan: TextView
    private lateinit var recTable: TableLayout
    private lateinit var recDiff: TextView
    private lateinit var recOrigin: TextView
    private lateinit var recButtons: LinearLayout
    private lateinit var adviceButton: android.widget.Button
    private lateinit var recCard: LinearLayout
    private lateinit var routeCard: LinearLayout
    private lateinit var routeTitle: TextView
    private lateinit var routePlan: TextView
    private lateinit var routeTable: TableLayout
    private lateinit var routeNote: TextView
    private lateinit var wageRow: LinearLayout
    private var renderedWage = -1
    private lateinit var askCard: LinearLayout
    private lateinit var askTitle: TextView
    private lateinit var askHint: TextView
    private lateinit var askChoices: LinearLayout
    private var renderedAsk: AskUi? = null
    private lateinit var findButton: Button
    private val railButtons = mutableListOf<Button>()
    private lateinit var personaOverlay: FrameLayout
    private lateinit var locationOverlay: FrameLayout
    private lateinit var noticeOverlay: FrameLayout
    private lateinit var settingsOverlay: FrameLayout
    private lateinit var thresholdRow: LinearLayout
    private var renderedThreshold = -1
    private lateinit var voiceRow: LinearLayout
    private var renderedVoice: Pair<Boolean, Boolean>? = null
    private lateinit var resetOverlay: FrameLayout

    private var shownCelebration = -1
    private var playedReaction: String? = null
    private var choosingPersona = false

    private val handler = Handler(Looper.getMainLooper())
    private val midnight = Runnable {
        controller?.refresh() // the date and today's / this month's totals roll over
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
        // The location permission is asked from the explanation card (see render), after the character choice.
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
        }
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
        // What the screen runs on: demo data in orange, real vehicle data in teal (prices are the plan's examples either way).
        modeChip.text = if (s.demo) {
            "시연 데이터\n${s.modeReason}\n가격·거리: ${DataOrigin.PLAN_EXAMPLE.label}"
        } else {
            "차량 값: ${s.vehicleOrigin?.label ?: "연결 중"}\n가격·거리: ${DataOrigin.PLAN_EXAMPLE.label}"
        }
        findButton.text = if (s.energyType.isElectric) "충전소 찾기" else "주유소 찾기"
        findButton.isEnabled = s.routes == null && !s.routesPending // the departure comparison first: [확인]
        findButton.alpha = if (findButton.isEnabled) 1f else 0.45f
        renderSettings(s)
        modeChip.background = rounded(if (s.demo) Palette.DEMO else Palette.TEAL, 16)

        val persona = s.persona
        val who = persona ?: Persona.ZZANDORI
        personaOverlay.visibility = if (persona == null || choosingPersona) View.VISIBLE else View.GONE
        val askLocation = !s.demo && persona != null && !choosingPersona && !app.locationGranted && !locationExplained
        locationOverlay.visibility = if (askLocation) View.VISIBLE else View.GONE
        val covered = personaOverlay.visibility == View.VISIBLE || locationOverlay.visibility == View.VISIBLE ||
            noticeOverlay.visibility == View.VISIBLE || settingsOverlay.visibility == View.VISIBLE || resetOverlay.visibility == View.VISIBLE

        // Above the character: the cumulative saving (never below the amount the current level was reached at),
        // then this month's and today's 손익 as they are, negative included (R-35).
        dateText.text = DATE.format(s.today)
        monthAmount.text = WonFormat.screen(s.totalSavedWon)
        todayText.text = "이번 달 ${signed(s.monthNetWon)} · 오늘 ${signed(s.todayNetWon)}"
        todayText.setTextColor(if (s.monthNetWon < 0 || s.todayNetWon < 0) palette.negative else palette.muted)

        // The character moves only while parked (R-46); the system blocks this screen while driving.
        stage.logTag = tag
        stage.motionAllowed = s.parked != false && !covered
        val charLevel = s.character.level
        val celebrate = s.pendingCelebrations.maxOrNull()
        if (celebrate != null && celebrate != shownCelebration) {
            shownCelebration = celebrate
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

        // Below the character: level badge, progress to the next food, totals.
        levelPill.text = "Lv.$charLevel ${progress.food(charLevel)}"
        personaText.text = who.displayName + if (stage.borrowed) " · 그림 준비 중" else ""
        val target = (s.remainingWon ?: 0) + s.character.accrualWon
        levelBar.max = if (s.remainingWon == null) 1 else target.toInt().coerceAtLeast(1)
        levelBar.progress = if (s.remainingWon == null) 1 else s.character.accrualWon.toInt()
        remainingText.text = s.remainingWon?.let { "${progress.nextFood(charLevel)}까지 ${WonFormat.screen(it)}" } ?: "최고 레벨이야"
        totalText.text = "이번 레벨 ${WonFormat.screen(s.character.accrualWon)}" +
            if (s.remainingWon != null) " / ${WonFormat.screen(target)}" else ""

        // The car's state, in the rail: what a driver needs, nothing a developer reads.
        val level = if (s.energyType.isElectric) {
            listOfNotNull(s.socPercent?.let { "배터리 %.0f%%".format(it) }, s.energy?.let { "%.1fkWh".format(it) }).joinToString(" · ")
        } else {
            listOfNotNull(s.socPercent?.let { "연료 %.0f%%".format(it) }, s.energy?.let { "%.1fL".format(it) }).joinToString(" · ")
        }.ifEmpty { "차량 값을 기다리는 중" }
        statusText.text = listOfNotNull(
            level,
            listOfNotNull(
                s.parked?.let { if (it) "정차 중" else "주행 중" },
                if (s.refilling) "${if (s.energyType.isElectric) "충전" else "주유"} 확인 중" else null,
            ).joinToString(" · ").ifEmpty { null },
            "알림 기준 ${s.threshold}%",
            when {
                !s.voiceAvailable -> "음성 답: 화면으로만"
                s.listening -> "음성 답: 듣는 중…"
                s.voiceAnswers -> "음성 답: 켜짐"
                else -> "음성 답: 꺼짐"
            },
            "위치 권한 꺼짐(들른 곳 확인 불가)".takeIf { !s.demo && !app.locationGranted },
        ).joinToString("\n")

        val cardOpen = renderAsk(s) || renderRecommendation(s, s.energyType.unit) || renderRoutes(s)
        resultText.text = s.lastResult ?: ""
        resultText.setTextColor(if (s.resultIsLoss) palette.negative else palette.good)
        resultText.visibility = if (s.lastResult == null) View.GONE else View.VISIBLE
        bubble.text = s.lastLine?.let { "“$it”" } ?: ""
        // While the microphone is open the badge shows "듣는 중…", even in a narrow window.
        listenBadge.visibility = if (s.listening) View.VISIBLE else View.GONE
        // In a narrow window an open card needs the room: the totals and the last line wait.
        val roomy = wide || !cardOpen
        bubble.visibility = if (s.lastLine == null || !roomy) View.GONE else View.VISIBLE
        totalText.visibility = if (roomy) View.VISIBLE else View.GONE
    }

    /** One question at a time (기획서 p.5): the card shows the question and its answers as large chips. */
    private fun renderAsk(s: UiState): Boolean {
        val ask = s.ask
        askCard.visibility = if (ask != null) View.VISIBLE else View.GONE
        if (ask == null) {
            renderedAsk = null
            return false
        }
        recCard.visibility = View.GONE
        routeCard.visibility = View.GONE
        side.visibility = View.VISIBLE
        if (ask != renderedAsk) {
            renderedAsk = ask
            askTitle.text = ask.title
            askHint.text = ask.hint
            askChoices.removeAllViews()
            ask.choices.chunked(2).forEach { pair ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEach { c ->
                    row.addView(
                        button(c.label, if (c.emphasized) Kind.MAIN else Kind.QUIET) {
                            when (ask.step) {
                                AskStep.REFERENCE -> controller?.answerReference(c.id)
                                AskStep.FILL -> controller?.answerFill(c.id)
                            }
                        }.apply { textSize = if (wide) 26f else 22f },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, dp(6), dp(12), dp(6)) },
                    )
                }
                if (pair.size == 1) row.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
                askChoices.addView(row)
            }
        }
        return true
    }

    /**
     * The comparison card: the conclusion first, at most a few rows, road distance, and where the
     * numbers come from. Open while a decision or a trip is open; afterwards it folds into the result line.
     */
    private fun renderRecommendation(s: UiState, unit: String): Boolean {
        val decision = s.recommendation?.takeIf { s.approval == Approval.WAITING || s.approval == Approval.APPROVED }
        // "No stop needed" (spec D-34) uses the same card with a single [확인]; a decision card comes first.
        val rec = decision ?: s.advice
        recCard.visibility = if (rec != null) View.VISIBLE else View.GONE
        side.visibility = if (rec != null) View.VISIBLE else View.GONE
        if (rec == null) return false
        routeCard.visibility = View.GONE
        val saving = rec.expectedSaving.toWon()
        val best = rec.best.offer
        val whole = rec.mode == CompareMode.ROUTE_TOTAL
        recTitle.text = when (rec.mode) {
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
        }
        recPlan.text = listOfNotNull(
            "예상".takeIf { rec.creditRule != CreditRule.NONE && !rec.recommendsBaseline }, // the title stays one phrase
            "시간비용 포함".takeIf { whole },
            rec.referenceName?.takeIf { !whole }?.let { "기준 위치: $it" },
            rec.fillLabel?.let { "넣을 양: $it" } ?: "넣을 양: ${rec.plannedQuantity.stripTrailingZeros().toPlainString()}$unit",
            rec.wagePerHour?.let { "시급 ${WonFormat.screen(it.toWon())}" },
        ).joinToString(" · ")
        recTable.removeAllViews()
        if (whole) {
            // 기획 계산식: each route's whole spend, the lowest as 0원 and the others as what they lose against it.
            recTable.addView(row(true, "장소", "단가", "거리·시간", "총 지출"))
            rec.ranked.take(3).forEach { c ->
                val stop = c.offer.id != RoutePolicy.NO_STOP_ID
                val name = c.offer.name + if (c.offer.id == best.id) "\n[추천]" else ""
                val price = if (stop) "${WonFormat.screen(c.offer.unitPrice.toWon())}/$unit" else "–"
                val leg = c.offer.route
                val trip = leg?.let {
                    "${it.km.stripTrailingZeros().toPlainString()}km · ${duration(it.minutes.toInt())}" +
                        if (it.tollWon.signum() > 0) "\n통행료 ${WonFormat.screen(it.tollWon.toWon())}" else ""
                } ?: "–"
                val diff = rec.best.total.subtract(c.total).toWon()
                val total = WonFormat.screen(c.total.toWon()) + "\n" + if (diff == 0L) "기준 0원" else "−${WonFormat.screen(-diff)}"
                recTable.addView(row(false, name, price, trip, total))
            }
        } else {
            val withPrice = rec.mode == CompareMode.COST_INCLUDED
            recTable.addView(if (withPrice) row(true, "장소", "도로 거리", "단가", "합계") else row(true, "장소", "도로 거리", "단가"))
            rec.ranked.take(3).forEach { c ->
                val tag = when {
                    c.offer.id == best.id -> "추천"
                    c.offer.id == rec.baseline.offer.id -> "가장 가까움"
                    else -> null
                }
                val name = c.offer.name + (tag?.let { "\n[$it]" } ?: "")
                val km = "${c.offer.oneWayKm.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()}km"
                val price = "${WonFormat.screen(c.offer.unitPrice.toWon())}/$unit"
                recTable.addView(if (withPrice) row(false, name, km, price, WonFormat.screen(c.total.toWon())) else row(false, name, km, price))
            }
        }
        // In "nearest one only" the shown place is itself past 5 km, so the excluded count would mislead.
        val excluded = if (rec.mode == CompareMode.NEAREST_ONLY) 0 else rec.excludedIds.size
        recDiff.text = if (excluded > 0) "도로로 5km 넘는 ${excluded}곳은 뺐어요" else ""
        recDiff.visibility = if (excluded > 0) View.VISIBLE else View.GONE
        val efficiency = "${if (rec.energyType.isElectric) "전비" else "연비"} " +
            "${rec.efficiency.kmPerUnit.stripTrailingZeros().toPlainString()}km/$unit: ${rec.efficiency.origin.label}"
        recOrigin.text = when {
            rec.noStop -> "남은 ${if (rec.energyType.isElectric) "배터리" else "연료"}로 목적지까지 갈 수 있고, 들르지 않는 편이 덜 들어요."
            rec.creditRule == CreditRule.NONE -> "가격을 비교하지 않은 안내라 절약액은 쌓이지 않아요."
            else -> "예상치예요. 차가 넣은 양을 확인해야 쌓여요."
        } + "\n" + if (whole) {
            "총 지출 = 전체 주행 연료비(기준 단가) + 넣을 양 × (이곳 단가 − 기준 단가) + 통행료 + 주행 시간 × 시급\n" +
                "기준 단가 ${rec.travelPrice?.let { WonFormat.screen(it, 2) }}/$unit: ${DataOrigin.SURVEY.label} · $efficiency · 가격·경로: ${best.origin.label}"
        } else {
            "가격·도로 거리: ${best.origin.label} · $efficiency"
        }
        recButtons.visibility = if (decision != null && s.approval == Approval.WAITING) View.VISIBLE else View.GONE
        adviceButton.visibility = if (decision == null) View.VISIBLE else View.GONE
        return true
    }

    /**
     * 기획서 3-2: the routes before departure with the best first in the title, each route's 운행비 (toll included on
     * expressways) and the value with the time counted at the wage. Example data and "not saved" are said on the card.
     */
    private fun renderRoutes(s: UiState): Boolean {
        val c = s.routes
        routeCard.visibility = if (c != null) View.VISIBLE else View.GONE
        if (c == null) return false
        side.visibility = View.VISIBLE
        val unit = s.energyType.unit
        val best = c.best
        val allStop = c.results.all { it.needsStop }
        routeTitle.text = if (allStop) {
            "${c.destinationName}까지 어느 길이든 알림 기준 아래로 내려가요"
        } else {
            "${c.destinationName}까지 ${Josa.ga(best.option.name)} 나아요"
        }
        val energyName = when (s.energyType) {
            EnergyType.GASOLINE -> "휘발유"
            EnergyType.DIESEL -> "경유"
            EnergyType.ELECTRIC -> "전기"
        }
        routePlan.text = "출발 전 · 시급 ${WonFormat.screen(c.wagePerHour.toWon())} 기준\n" +
            "연비 ${c.efficiency.kmPerUnit.stripTrailingZeros().toPlainString()}km/$unit(${c.efficiency.origin.label}) · " +
            "$energyName ${WonFormat.screen(c.unitPrice, 2)}/$unit(조사값)"
        routeTable.removeAllViews()
        routeTable.addView(row(true, "경로", "거리·시간", "운행비", "시간 포함"))
        c.results.forEach { r ->
            val name = r.option.name + if (!allStop && r.option.id == best.option.id) "\n[추천]" else ""
            val use = r.energy.setScale(0, RoundingMode.HALF_UP).toPlainString() + unit
            val trip = "${r.option.distanceKm.stripTrailingZeros().toPlainString()}km · $use\n${duration(r.option.minutes)}" +
                if (r.needsStop) "\n도중 주유 필요" else ""
            val cost = WonFormat.screen(r.operatingCost.toWon()) +
                if (r.toll.signum() > 0) "\n(통행료 ${WonFormat.screen(r.toll.toWon())} 포함)" else ""
            routeTable.addView(row(false, name, trip, cost, WonFormat.screen(r.value.toWon())))
        }
        routeNote.text = "시간 포함 = 운행비 + 걸리는 시간 × 시급. 이 비교는 절약액으로 쌓지 않아요.\n" +
            when {
                allStop -> "어느 길로 가도 도착 전에 알림 기준(${s.threshold}%) 아래로 내려가요. 알림이 뜨면 가는 길 " +
                    "${if (s.energyType.isElectric) "충전소" else "주유소"}를 들르는 경로를 비교해 드려요.\n"
                c.results.any { it.needsStop } -> "알림 기준 아래로 내려가는 길은 가는 길 주유소를 함께 봐야 해서 추천에서 뺐어요.\n"
                else -> ""
            } +
            "경로·통행료: ${c.origin.label} · 단가: ${DataOrigin.SURVEY.label}"
        return true
    }

    private fun duration(minutes: Int): String =
        if (minutes >= 60) "${minutes / 60}시간" + (if (minutes % 60 > 0) " ${minutes % 60}분" else "") else "${minutes}분"

    /** Threshold chips in the settings sheet (spec D-22) and the voice-answer switch. */
    private fun renderSettings(s: UiState) {
        if (s.wage != renderedWage) {
            renderedWage = s.wage
            wageRow.removeAllViews()
            RoutePlanner.WAGE_CHOICES.forEach { w ->
                wageRow.addView(
                    button(WonFormat.screen(w.toLong()), if (w == s.wage) Kind.MAIN else Kind.QUIET) { controller?.setWage(w) }.apply { textSize = 22f },
                    LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) },
                )
            }
        }
        val voiceKey = s.voiceAnswers to s.voiceAvailable
        if (voiceKey != renderedVoice) {
            renderedVoice = voiceKey
            voiceRow.removeAllViews()
            if (!s.voiceAvailable) { // no speech recognition here: say so instead of a switch that does nothing
                voiceRow.addView(text("이 기기에서는 음성 인식을 쓸 수 없어 화면으로 답해요.", 22f, palette.muted))
            } else {
                listOf(true to "켜기", false to "끄기").forEach { (on, label) ->
                    voiceRow.addView(
                        button(label, if (s.voiceAnswers == on) Kind.MAIN else Kind.QUIET) { controller?.setVoiceAnswers(on) }.apply { textSize = 22f },
                        LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) },
                    )
                }
            }
        }
        if (s.threshold == renderedThreshold) return
        renderedThreshold = s.threshold
        thresholdRow.removeAllViews()
        (TriggerPolicy.MIN..TriggerPolicy.MAX step TriggerPolicy.STEP).forEach { p ->
            thresholdRow.addView(
                button("$p%", if (p == s.threshold) Kind.MAIN else Kind.QUIET) { controller?.setThreshold(p) }.apply { textSize = 22f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) },
            )
        }
    }

    private fun signed(won: Long) = (if (won > 0) "+" else "") + WonFormat.screen(won)

    private fun amountColor(won: Long) = when {
        won > 0 -> palette.accent
        won < 0 -> palette.negative
        else -> palette.text
    }

    // ------------------------------------------------------------------ layout

    /**
     * Wide window (one app across the app area): rail, character, and the card beside it.
     * Narrow window (split): a slimmer rail, the card below the character. Same buttons in both (function parity).
     */
    private fun applyLayout(widthDp: Int) {
        wide = widthDp >= WIDE_DP
        rail.layoutParams = LinearLayout.LayoutParams(dp(if (wide) 320 else 248), ViewGroup.LayoutParams.MATCH_PARENT)
        railButtons.forEach { it.textSize = if (wide) 26f else 22f }
        modeChip.textSize = if (wide) 20f else 18f
        statusText.textSize = if (wide) 20f else 18f
        content.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        home.layoutParams = if (wide) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        side.layoutParams = if (wide) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.85f).apply { marginStart = dp(20) }
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.9f).apply { topMargin = dp(12) }
        }
        levelBar.layoutParams = LinearLayout.LayoutParams(if (wide) dp(600) else ViewGroup.LayoutParams.MATCH_PARENT, dp(16)).apply {
            topMargin = dp(10)
            bottomMargin = dp(8)
        }
        // The level-up card must not hide the food the character eats: next to the head when there is room.
        celebration.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            if (wide) Gravity.TOP or Gravity.END else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(palette.background) }
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
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(20), dp(24), dp(20))
        }

        // Rail on the driver's side: data source, buttons, the car's state.
        rail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        modeChip = text("", 20f, Color.WHITE).apply {
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setLineSpacing(0f, 1.1f)
        }
        rail.addView(modeChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) })
        // Product actions on top; demo and reset for judges at the bottom.
        findButton = button("충전소 찾기", Kind.MAIN) { controller?.findStation() }
        railButtons += findButton
        railButtons += button("설정", Kind.QUIET) {
            settingsOverlay.visibility = View.VISIBLE
            lastState?.let(::render)
        }
        railButtons.forEach { b ->
            rail.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
        rail.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))
        rail.addView(text("시연·심사용", 20f, palette.muted).apply { setPadding(dp(4), 0, 0, dp(6)) })
        listOf(
            button("시연 B · 충전", Kind.BOLT) { startDemo("B") },
            button("시연 A · 주유", Kind.BOLT) { startDemo("A") },
            button("시연 C · 운전 습관", Kind.BOLT) { startDemo("C") },
            button("실차 모드", Kind.QUIET) { startDemo("off") },
            button("처음부터", Kind.QUIET) {
                resetOverlay.visibility = View.VISIBLE // one tap must not wipe the real record
                lastState?.let(::render)
            },
        ).forEach { b ->
            railButtons += b
            rail.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        statusText = text("", 20f, palette.muted).apply {
            setLineSpacing(0f, 1.15f)
            setPadding(dp(4), dp(8), 0, 0)
        }
        rail.addView(statusText)
        body.addView(rail)

        content = LinearLayout(this)
        home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        // Above the character: date, this month's 손익 (large), today.
        dateText = text("", 32f, palette.text, center = true)
        home.addView(dateText)
        home.addView(text("누적 절약액", 26f, palette.muted, center = true).apply { setPadding(0, dp(6), 0, 0) })
        monthAmount = text("", 96f, palette.text, bold = true, center = true).apply {
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
        }
        home.addView(monthAmount)
        todayText = text("", 26f, palette.muted, center = true)
        home.addView(todayText)

        // The character, centered, with the level-up card over its lower part.
        val stageBox = FrameLayout(this).apply {
            background = SpotlightDrawable(palette.spot, palette.background)
            minimumHeight = dp(200)
        }
        stage = CharacterStageView(this).apply {
            contentDescription = "캐릭터"
            isClickable = true
            setOnClickListener { playTap() }
        }
        stageBox.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // While the microphone is open: a badge at the foot of the character, never cut off by long lines.
        listenBadge = text("듣는 중… 말로 답해도 돼요", 22f, Palette.NAVY, bold = true, center = true).apply {
            background = rounded(Palette.BOLT, 40)
            setPadding(dp(22), dp(8), dp(22), dp(8))
            visibility = View.GONE
        }
        stageBox.addView(
            listenBadge,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(8) },
        )
        celebration = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(16), dp(28), dp(16))
            background = rounded(palette.surface, 28, alpha = 242)
            elevation = dp(6).toFloat()
            visibility = View.GONE
        }
        celebrationTitle = text("", 36f, palette.accent, bold = true, center = true)
        celebrationSub = text("", 26f, palette.text, center = true)
        celebration.addView(celebrationTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        celebration.addView(celebrationSub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        celebration.addView(button("좋아!", Kind.MAIN) {
            controller?.celebrationShown(shownCelebration)
            celebration.visibility = View.GONE
        }, LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        stageBox.addView(celebration) // placed by applyLayout: beside the head when wide, under the character when narrow
        home.addView(stageBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Below the character: level badge (the bolt's yellow) and character, progress, the next food.
        val levelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        levelPill = text("", 30f, Palette.NAVY, bold = true).apply {
            background = rounded(Palette.BOLT, 40)
            setPadding(dp(22), dp(6), dp(22), dp(6))
        }
        levelRow.addView(levelPill)
        personaText = text("", 26f, palette.muted).apply { setPadding(dp(16), 0, 0, 0) }
        levelRow.addView(personaText)
        home.addView(levelRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = ColorStateList.valueOf(palette.fill)
            progressBackgroundTintList = ColorStateList.valueOf(palette.track)
        }
        home.addView(levelBar)
        remainingText = text("", 30f, palette.text, center = true)
        home.addView(remainingText)
        totalText = text("", 24f, palette.muted, center = true)
        home.addView(totalText)
        resultText = text("", 26f, palette.good, bold = true, center = true)
        home.addView(resultText)
        bubble = text("", 24f, palette.muted, center = true).apply {
            // Two lines are always reserved, so a long line is never pushed off the bottom (the character gives way).
            minLines = 2
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        home.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(home)

        // The question card (one question at a time) and the comparison card share the side panel.
        askCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(22))
            background = rounded(palette.surface, 28)
            visibility = View.GONE
        }
        askTitle = text("", 34f, palette.text, bold = true)
        askHint = text("", 22f, palette.muted)
        askChoices = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }
        askCard.addView(askTitle)
        askCard.addView(askHint)
        askCard.addView(askChoices)
        askCard.addView(button("취소", Kind.QUIET) { controller?.cancelAsk() }.apply { textSize = 22f }, LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.WRAP_CONTENT))

        // Comparison card (open decisions and trips only).
        recCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(22))
            background = rounded(palette.surface, 28)
        }
        recTitle = text("", 32f, palette.good, bold = true)
        recPlan = text("", 22f, palette.muted)
        recTable = TableLayout(this).apply {
            isStretchAllColumns = true
            setColumnShrinkable(0, true) // long station names wrap instead of pushing the cost off the card
            setPadding(0, dp(8), 0, dp(8))
        }
        recDiff = text("", 22f, palette.text)
        recOrigin = text("", 20f, palette.muted)
        recButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        recButtons.addView(button("응, 들를게", Kind.MAIN) { controller?.approve() }, rowParams())
        recButtons.addView(button("아니", Kind.QUIET) { controller?.decline() }, rowParams())
        recButtons.addView(button("왜 거기야?", Kind.QUIET) { controller?.explain() }, rowParams())
        adviceButton = button("확인", Kind.MAIN) { controller?.closeAdvice() }.apply { visibility = View.GONE }
        listOf(recTitle, recPlan, recTable, recDiff, recOrigin, recButtons).forEach(recCard::addView)
        recCard.addView(adviceButton, LinearLayout.LayoutParams(dp(240), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        // Route comparison card (before departure, 기획서 3-2).
        routeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(22))
            background = rounded(palette.surface, 28)
            visibility = View.GONE
        }
        routeTitle = text("", 32f, palette.good, bold = true)
        routePlan = text("", 22f, palette.muted)
        routeTable = TableLayout(this).apply {
            isStretchAllColumns = true
            setColumnShrinkable(2, true) // the cost cell wraps "(통행료 … 포함)"; route names and [추천] stay whole
            setPadding(0, dp(8), 0, dp(8))
        }
        routeNote = text("", 20f, palette.muted)
        listOf(routeTitle, routePlan, routeTable, routeNote).forEach(routeCard::addView)
        routeCard.addView(
            button("확인", Kind.MAIN) { controller?.closeRoutes() },
            LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) },
        )
        val sideBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sideBox.addView(askCard)
        sideBox.addView(recCard)
        sideBox.addView(routeCard)
        side = ScrollView(this).apply {
            addView(sideBox)
            visibility = View.GONE
        }
        content.addView(side)
        body.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(20) })
        root.addView(body)

        personaOverlay = overlay()
        val personaBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        personaBox.addView(text("함께할 캐릭터를 골라 주세요", 36f, palette.text, bold = true, center = true))
        personaBox.addView(text("목소리와 말투가 달라요. 바꿔도 레벨과 금액은 그대로예요.", 24f, palette.muted, center = true))
        val choices = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        Persona.entries.forEach { p ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(32), dp(24), dp(32), 0)
            }
            // Still pictures here: nothing moves behind a choice.
            val still = CharacterStageView(this).apply {
                motionAllowed = false
                setCharacter(p, 0)
            }
            col.addView(still, LinearLayout.LayoutParams(dp(340), dp(340)))
            col.addView(text(if (still.borrowed) "그림 준비 중" else " ", 22f, palette.muted, center = true))
            col.addView(button(p.displayName, if (p == Persona.ZZANDORI) Kind.MAIN else Kind.PINK) {
                choosingPersona = false
                controller?.choosePersona(p)
                personaOverlay.visibility = View.GONE
                lastState?.let(::render)
            }, LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT))
            choices.addView(col)
        }
        personaBox.addView(choices)
        personaOverlay.addView(personaBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(personaOverlay)

        locationOverlay = overlay()
        val locationBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }
        locationBox.addView(text("위치 권한을 물어볼게요", 36f, palette.text, bold = true, center = true))
        listOf(
            "추천한 충전소·주유소에 들렀는지 확인하고, 현재 위치 근처를 찾을 때만 위치를 써요.",
            "위치는 기기 안에만 저장하고 밖으로 보내지 않아요. 이동 경로도 남기지 않아요.",
            "에뮬레이터에서는 다음 창에 '알 수 없는 작업'이라고 나와요. Pleos 위치 권한을 묻는 창이에요.",
            "허용하지 않아도 시연 B·A는 그대로 볼 수 있어요.",
        ).forEach { line -> locationBox.addView(text(line, 24f, palette.muted, center = true)) }
        val locationButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }
        locationButtons.addView(button("계속", Kind.MAIN) {
            locationExplained = true
            locationOverlay.visibility = View.GONE
            lastState?.let(::render)
            requestPermissions(arrayOf(ZzanApp.FUSED_LOCATION), REQ_LOCATION)
        }, rowParams())
        locationButtons.addView(button("나중에", Kind.QUIET) {
            locationExplained = true
            locationOverlay.visibility = View.GONE
            lastState?.let(::render)
        }, rowParams())
        locationBox.addView(locationButtons)
        locationOverlay.addView(locationBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(locationOverlay)

        noticeOverlay = overlay()
        val noticeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(64), dp(40), dp(64), dp(40))
        }
        val notices = runCatching { assets.open("open_source_notices.txt").bufferedReader(Charsets.UTF_8).use { it.readText() } }
            .getOrDefault("오픈소스 고지 파일을 읽지 못했어요.")
        noticeBox.addView(ScrollView(this).apply { addView(text(notices, 20f, palette.text)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        noticeBox.addView(button("닫기", Kind.QUIET) {
            noticeOverlay.visibility = View.GONE
            lastState?.let(::render)
        }, LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        noticeOverlay.addView(noticeBox)
        root.addView(noticeOverlay)

        // Settings (parked only, like the whole screen): the threshold (spec D-22), character, notices.
        settingsOverlay = overlay()
        val settingsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(32))
            background = rounded(palette.surface, 28)
        }
        settingsBox.addView(text("설정", 36f, palette.text, bold = true))
        settingsBox.addView(text("알림 시작 기준", 26f, palette.text, bold = true).apply { setPadding(0, dp(16), 0, 0) })
        settingsBox.addView(text("남은 양이 이 아래로 내려가면 캐릭터가 먼저 알려요. 차량 경고등 신호는 앱이 읽을 수 없어 이 값을 써요.", 22f, palette.muted))
        thresholdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
        }
        settingsBox.addView(thresholdRow)
        settingsBox.addView(text("음성으로 답하기", 26f, palette.text, bold = true).apply { setPadding(0, dp(12), 0, 0) })
        settingsBox.addView(text("캐릭터가 물은 뒤 5초 동안 들어요. \"응\", \"아니\", \"가득\"처럼 짧게 답하면 돼요. 답이 없으면 아무것도 실행하지 않아요.", 22f, palette.muted))
        voiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
        }
        settingsBox.addView(voiceRow)
        settingsBox.addView(text("시간의 값 (시급)", 26f, palette.text, bold = true).apply { setPadding(0, dp(12), 0, 0) })
        settingsBox.addView(text("경로와 가는 길 주유소를 비교할 때 1시간을 이 금액으로 쳐요. 출발 전 경로 비교는 비교만 하고 쌓지 않아요.", 22f, palette.muted))
        wageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
        }
        settingsBox.addView(wageRow)
        val settingsButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        settingsButtons.addView(button("캐릭터 바꾸기", Kind.QUIET) {
            settingsOverlay.visibility = View.GONE
            choosingPersona = true
            personaOverlay.visibility = View.VISIBLE
            lastState?.let(::render)
        }, rowParams())
        settingsButtons.addView(button("오픈소스 고지", Kind.QUIET) {
            settingsOverlay.visibility = View.GONE
            noticeOverlay.visibility = View.VISIBLE
            lastState?.let(::render)
        }, rowParams())
        settingsButtons.addView(button("닫기", Kind.MAIN) {
            settingsOverlay.visibility = View.GONE
            lastState?.let(::render)
        }, rowParams())
        settingsBox.addView(settingsButtons)
        settingsOverlay.addView(settingsBox, FrameLayout.LayoutParams(dp(1100).coerceAtMost(resources.displayMetrics.widthPixels - dp(40)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(settingsOverlay)

        // "처음부터" asks first; it wipes the real savings record.
        resetOverlay = overlay()
        val resetBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(48), dp(32), dp(48), dp(32))
            background = rounded(palette.surface, 28)
        }
        resetBox.addView(text("기록을 모두 지울까요?", 34f, palette.text, bold = true, center = true))
        resetBox.addView(text("레벨과 절약 기록이 처음으로 돌아가요. 되돌릴 수 없어요.", 24f, palette.muted, center = true))
        val resetButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }
        resetButtons.addView(button("지우기", Kind.PINK) {
            resetOverlay.visibility = View.GONE
            controller?.resetAll()
        }, rowParams())
        resetButtons.addView(button("취소", Kind.QUIET) {
            resetOverlay.visibility = View.GONE
            lastState?.let(::render)
        }, rowParams())
        resetBox.addView(resetButtons)
        resetOverlay.addView(resetBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(resetOverlay)
        return root
    }

    private fun overlay() = FrameLayout(this).apply {
        setBackgroundColor(palette.scrim)
        visibility = View.GONE
        isClickable = true
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun rounded(color: Int, radiusDp: Int, alpha: Int = 255) = GradientDrawable().apply {
        setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)))
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false, center: Boolean = false) = TextView(this).apply {
        koreanLineBreaks()
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (center) gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(3), 0, dp(3))
    }

    /** MAIN: deep teal. BOLT: the bolt's yellow (demo actions). PINK: 짠순이. QUIET: outline only. */
    private enum class Kind { MAIN, BOLT, PINK, QUIET }

    /** Large button with the Pleos press feedback (shrinks to 95% while touched). */
    @SuppressLint("ClickableViewAccessibility")
    private fun button(label: String, kind: Kind, onClick: () -> Unit) = Button(this).apply {
        koreanLineBreaks()
        text = label
        isAllCaps = false
        textSize = 26f
        setTypeface(typeface, Typeface.BOLD)
        minHeight = dp(76)
        background = when (kind) {
            Kind.MAIN -> rounded(Palette.TEAL, 20)
            Kind.BOLT -> rounded(Palette.BOLT, 20)
            Kind.PINK -> rounded(Palette.PINK, 20)
            Kind.QUIET -> rounded(palette.surface, 20).apply { setStroke(dp(2), palette.outline) }
        }
        setTextColor(
            when (kind) {
                Kind.MAIN, Kind.PINK -> Color.WHITE
                Kind.BOLT -> Palette.NAVY
                Kind.QUIET -> palette.text
            },
        )
        stateListAnimator = null
        setPadding(dp(24), dp(14), dp(24), dp(14))
        setOnClickListener { onClick() }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
            }
            false
        }
    }

    /** Break Korean lines between phrases, not inside a word ("들를\n래?"), where the platform supports it (API 33+). */
    private fun TextView.koreanLineBreaks() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lineBreakWordStyle = LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
        }
    }

    private fun rowParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) }

    private fun row(header: Boolean, vararg cells: String) = TableRow(this).apply {
        cells.forEach { c ->
            addView(text(c, if (header) 20f else 22f, if (header) palette.muted else palette.text).apply { setPadding(0, dp(5), dp(16), dp(5)) })
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val REQ_LOCATION = 11
        const val EXTRA_CMD = "zzan.cmd"
        const val EXTRA_DEMO = "zzan.demo"
        const val EXTRA_LEVEL = "zzan.level"
        const val EXTRA_PERSONA = "zzan.persona"
        const val EXTRA_ARG = "zzan.arg"

        /** At or above this width the card opens beside the character (Pleos: one app across the app area). */
        const val WIDE_DP = 1200
        val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)
    }
}
