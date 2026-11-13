package com.zzandrive.app.runtime

import android.util.Log
import com.zzandrive.core.dialog.AnswerParser
import com.zzandrive.core.dialog.DialogEffect
import com.zzandrive.core.dialog.DialogEvent
import com.zzandrive.core.dialog.DialogState
import com.zzandrive.core.dialog.Expect
import com.zzandrive.core.dialog.Heard
import com.zzandrive.core.dialog.FillChoices
import com.zzandrive.core.dialog.Reference
import com.zzandrive.core.dialog.StationDialog
import com.zzandrive.core.domain.CandidatePolicy
import com.zzandrive.core.domain.CelebrationPolicy
import com.zzandrive.core.domain.CompareMode
import com.zzandrive.core.domain.CreditRule
import com.zzandrive.core.domain.FillAnswer
import com.zzandrive.core.domain.PlanContext
import com.zzandrive.core.domain.PlanRequest
import com.zzandrive.core.domain.Reachability
import com.zzandrive.core.domain.RouteBranch
import com.zzandrive.core.domain.CharacterProgress
import com.zzandrive.core.domain.CharacterState
import com.zzandrive.core.domain.Efficiency
import com.zzandrive.core.domain.PlanReference
import com.zzandrive.core.domain.Recommendation
import com.zzandrive.core.domain.Recommender
import com.zzandrive.core.domain.RouteComparison
import com.zzandrive.core.domain.RoutePlanner
import com.zzandrive.core.domain.TriggerPolicy
import com.zzandrive.core.domain.bd
import com.zzandrive.core.domain.toWon
import com.zzandrive.core.settlement.Ledger
import com.zzandrive.core.settlement.LedgerCodec
import com.zzandrive.core.settlement.LedgerEvent
import com.zzandrive.core.settlement.OpeningBalance
import com.zzandrive.core.settlement.RecommendationDecided
import com.zzandrive.core.settlement.RecommendationIssued
import com.zzandrive.core.settlement.RefillRecorded
import com.zzandrive.core.settlement.SavingConfirmed
import com.zzandrive.core.settlement.SettlementDecision
import com.zzandrive.core.settlement.SettlementPolicy
import com.zzandrive.core.settlement.OpportunityCost
import com.zzandrive.core.settlement.OpportunityCostConfirmed
import com.zzandrive.core.settlement.SettlementSkipped
import com.zzandrive.core.settlement.SkipReason
import com.zzandrive.core.verification.RefillConfig
import com.zzandrive.core.verification.RefillDetected
import com.zzandrive.core.verification.RefillDetector
import com.zzandrive.core.verification.PassDetector
import com.zzandrive.core.verification.StationMatcher
import com.zzandrive.core.voice.Josa
import com.zzandrive.core.voice.Persona
import com.zzandrive.core.voice.VoiceLines
import com.zzandrive.core.voice.WonFormat
import com.zzandrive.data.api.Cancellable
import com.zzandrive.data.api.ChargeHintSample
import com.zzandrive.data.api.Clock
import com.zzandrive.data.api.DataOrigin
import com.zzandrive.data.api.DriveSample
import com.zzandrive.data.api.EnergySample
import com.zzandrive.data.api.EnergyType
import com.zzandrive.data.api.GeoPoint
import com.zzandrive.data.api.LocationSample
import com.zzandrive.data.api.PlaceSource
import com.zzandrive.data.api.RouteSource
import com.zzandrive.data.api.Scheduler
import com.zzandrive.data.api.StationSource
import com.zzandrive.data.api.VehicleSample
import com.zzandrive.data.api.VehicleSource
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** What the screens draw. Everything the driver sees comes from here. */
data class UiState(
    val demo: Boolean,
    val energyType: EnergyType,
    val modeReason: String,
    val vehicleOrigin: DataOrigin?,
    val socPercent: Double?,
    val energy: Double?,
    val capacity: Double?,
    val parked: Boolean?,
    val gear: String?,
    val chargeHint: ChargeHintSample?,
    val location: GeoPoint?,
    val persona: Persona?,
    val character: CharacterState,
    val remainingWon: Long?,
    val totalSavedWon: Long,
    val todayNetWon: Long,
    val monthNetWon: Long,
    val recommendation: Recommendation?,
    val approval: Approval,
    val refilling: Boolean,
    val lastResult: String?,
    val lastLine: String?,
    val pendingCelebrations: List<Int>,
    /** Today in Asia/Seoul: the date on screen and the day of [todayNetWon] / month of [monthNetWon]. */
    val today: LocalDate,
    /** The last settlement's mood for the character (not stored; a restart forgets it). */
    val reaction: Reaction?,
    /** The question on screen, if the character is asking one (기획서 p.5, one at a time). */
    val ask: AskUi? = null,
    /** When to recommend, in % (spec D-22). */
    val threshold: Int = 0,
    /** The microphone is open for an answer (shown, Pleos: say when it is listening). */
    val listening: Boolean = false,
    val voiceAnswers: Boolean = false,
    val voiceAvailable: Boolean = false,
    /** The routes compared before departure (기획서 3-2), while the card is open. */
    val routes: RouteComparison? = null,
    /** Won per hour for the time value (spec D-31). */
    val wage: Int = 0,
    /** The departure comparison is about to open (demo A). */
    val routesPending: Boolean = false,
)

enum class AskStep { REFERENCE, FILL }

/** A tappable answer. [id] is what the debug command and the controller take ("current", "q40", "m10000", "full", "skip"). */
data class AskChoice(val id: String, val label: String, val emphasized: Boolean = false)

data class AskUi(val step: AskStep, val title: String, val hint: String, val choices: List<AskChoice>)

/** Happy after a credited saving that did not level up, sad when a refill was not credited. [id] is the detection id. */
data class Reaction(val id: String, val happy: Boolean)

enum class Approval { NONE, WAITING, APPROVED, DECLINED, SETTLED }

/**
 * The app's runtime (Application scope, so it keeps working while the system blocks the screen
 * during driving). Flow: low energy → recommend + speak → driver approves → navigation →
 * refill detected → matched → settled → level → celebrate when parked (spec F02/F03/F05/F06).
 */
class ZzanController(
    private val demo: Boolean,
    private val vehicle: VehicleSource,
    private val stations: StationSource,
    private val navigator: Navigator,
    private val speaker: Speaker,
    private val store: LedgerStore,
    private val prefs: Prefs,
    private val clock: Clock,
    private val scheduler: Scheduler,
    private val modeReason: () -> String = { if (demo) "시연 모드" else "확인 중" },
    /** Demo only: runs after the driver approves (plays the refill phase). */
    private val afterApprove: (() -> Unit)? = null,
    /** Saved places offered as "다른 장소" (p.5 ④). */
    private val places: PlaceSource = PlaceSource { emptyList() },
    /** Navigation is guiding a route (p.5 ⑥: then a search offers the cheapest on the route). */
    private val routeActive: () -> Boolean = { false },
    /** The route being guided, with stations along it (p.4 ②-1·③); null when there is no destination. */
    private val routes: RouteSource = RouteSource { null },
    /** Spoken answers (Pleos SpeechToText); [Listener.NONE] keeps everything on the screen. */
    private val listener: Listener = Listener.NONE,
    /** Demo only: runs after the driver closes the route comparison (the trip goes on). */
    private val afterRoutes: (() -> Unit)? = null,
) {
    private val progress = CharacterProgress()
    private val zone = ZoneId.of("Asia/Seoul")
    private var ledger = Ledger.replay(store.load().asSequence(), progress)
    private val observers = mutableListOf<(UiState) -> Unit>()
    private var subscription: Cancellable? = null
    private var ticker: Cancellable? = null

    private var detector = newDetector()
    private var energyType: EnergyType = vehicle.energyType
    private var lastEnergy: EnergySample? = null
    private var lastHint: ChargeHintSample? = null
    private var parked: Boolean? = null
    private var gear: String? = null
    private var location: GeoPoint? = null
    private var vehicleOrigin: DataOrigin? = null
    private var armed = true
    private var approval = Approval.NONE
    private var lastResult: String? = null
    private var lastLine: String? = null
    private var reaction: Reaction? = null
    private var dialog: DialogState = DialogState.Idle

    /** What the open listening window answers, or null when not listening. */
    private var expecting: Expect? = null

    /** Numbers each spoken question; a callback from an older one (its line ended, its window closed) is void. */
    private var questionSeq = 0

    /** After stop() (a demo switched off) nothing more is asked or heard. */
    private var stopped = false

    /** A recommendation question the driver did not hear or answer while driving: asked once more at the stop. */
    private var consentAtStop = false

    /** The recommendation already asked again at a stop: it is not held for another stop. */
    private var consentAskedAtStopFor: String? = null
    private var reprompted = false
    private var listening = false

    /** The amount was understood from speech: say it back with the recommendation so a mishearing is caught. */
    private var fillHeard = false

    /** spec D-28: watches the approved recommended station for a pass without a refill. */
    private val pass = PassDetector()

    /** A line that must wait until the car stops (the opportunity cost is told after stopping, R-30). */
    private var pendingParkedLine: String? = null

    /** The routes compared before departure (기획서 3-2), open until the driver closes the card. */
    private var routeComparison: RouteComparison? = null

    /** The departure comparison is about to open (demo A, until the level arrives): [찾기] waits. */
    private var routesPending = false

    fun start() {
        subscription = vehicle.subscribe(::onSample)
        ticker = scheduleTick()
        restoreApproval()
        log("READY mode=${modeName()} demo=$demo origin=${if (demo) "MOCK" else "PLEOS_SDK"} state=${ledger.state}")
        publish()
    }

    fun stop() {
        stopped = true
        stopListening()
        subscription?.cancel()
        ticker?.cancel()
    }

    fun observe(listener: (UiState) -> Unit): Cancellable {
        observers += listener
        listener(state())
        return Cancellable { observers -= listener }
    }

    // ------------------------------------------------------------------ commands

    /** Re-publishes the current state (the screen calls it at midnight so the date and today/month totals roll over). */
    fun refresh() = publish()

    fun choosePersona(p: Persona) {
        prefs.persona = p
        publish()
    }

    /** "지금 비교": recommend now regardless of the low-energy trigger (debug command; the screen uses [findStation]). */
    fun compareNow() = issueRecommendation(reason = "manual")

    /** [충전소 찾기]/[주유소 찾기] (기획서 p.5 ④⑥, 시나리오 B p.17): asks the place, then the amount. */
    fun findStation() {
        if (routeComparison != null || routesPending) { // the departure comparison is open: [확인] first (the demo goes on from there)
            log("FIND ignored (route card open)")
            return
        }
        dispatch(DialogEvent.Find(routeActive()))
    }

    /**
     * 기획서 3-2 / 4장 A-1: before departure, the routes to the destination side by side and the best by 운행비 + 시간
     * 환산 (spec D-31). Example routes until a route API is connected; the time value is weighed, never saved.
     */
    /** The departure comparison will open shortly (demo A): the find button waits for it. */
    fun prepareRoutes() {
        routesPending = true
        publish()
    }

    fun compareRoutes() {
        routesPending = false
        if (routeComparison == null && (dialog != DialogState.Idle || recommendationOpen())) { // a question or decision is open
            log("ROUTES skipped (busy)")
            return publish()
        }
        val type = vehicle.energyType
        val choices = routes.choices(type) ?: run { log("ROUTES none (no route data)"); return }
        val e = lastEnergy
        val cap = e?.capacity
        val now = e?.amount ?: cap?.let { c -> e?.socPercent?.let { c * it / 100 } }
        val c = RoutePlanner.compare(
            choices, efficiency(type), PlanReference.survey(type).unitPrice, java.math.BigDecimal(prefs.wage),
            energyNow = now?.let { java.math.BigDecimal.valueOf(it) },
            thresholdAmount = cap?.let { java.math.BigDecimal.valueOf(it * prefs.threshold(type) / 100.0) },
        ) ?: run { log("ROUTES none"); return }
        routeComparison = c
        log(
            "ROUTES dest=${c.destinationName} best=${c.best.option.id} rival=${c.rival?.option?.id ?: "-"} faster=${c.minutesFaster} " +
                "costMore=${c.costMoreWon} wage=${prefs.wage} " +
                c.results.joinToString(" ") { "${it.option.id}:${it.operatingCost.toWon()}+${it.timeValue.toWon()}${if (it.needsStop) ":stop" else ""}" },
        )
        val stopNames = c.results.filter { it.needsStop }.map { it.option.name }
        val advice = lines().routeAdvice(c.best.option.name, c.rival?.option?.name, c.minutesFaster, c.costMoreWon, prefs.wage.toLong())
        val line = when {
            stopNames.isEmpty() -> advice
            stopNames.size == c.results.size -> lines().routeStops(type.isElectric, null)
            c.results.size - stopNames.size == 1 -> lines().routeStops(type.isElectric, c.best.option.name, stopNames)
            else -> lines().routeStops(type.isElectric, c.best.option.name, stopNames, advice) // compared among the stop-free routes
        }
        // The verdict belongs to this comparison: a newer one (wage, threshold) or [확인] drops or stops it.
        speaker.refresh()
        lastLine = line
        log("SAY $line")
        speaker.voice(prefs.persona ?: Persona.ZZANDORI)
        speaker.speakWhile(line, { !stopped && routeComparison === c })
        publish()
    }

    /** [확인] on the route comparison. In the demo the trip goes on from here. */
    fun closeRoutes() {
        if (routeComparison == null) return
        routeComparison = null
        speaker.refresh() // the verdict stops with the card
        log("ROUTES closed")
        publish()
        afterRoutes?.invoke()
    }

    /** Settings: the hourly wage for the time value (spec D-31). An open comparison is worked out again. */
    fun setWage(won: Int) {
        if (won !in RoutePlanner.WAGE_CHOICES) return
        prefs.wage = won
        log("SET wage=$won")
        if (routeComparison != null) compareRoutes() else publish()
    }

    /** Answer to "어디 근처로 찾을까?": "current" or a saved place id. */
    fun answerReference(id: String) {
        stopListening() // a tap ends the spoken question too
        val ref = references().firstOrNull { it.id == id } ?: run { log("ANSWER reference=$id unknown"); return }
        log("ANSWER reference=${ref.id} name=${ref.name}")
        dispatch(DialogEvent.ReferenceChosen(ref))
    }

    /** Answer to "얼마나 넣을까?": "full", "q40" (L/kWh), "m10000" (won) or "skip". */
    fun answerFill(choiceId: String) {
        stopListening()
        val answer = FillChoices.parse(choiceId) ?: run { log("ANSWER fill=$choiceId unknown"); return }
        if (answer != FillAnswer.Skip) prefs.lastFillChoice = choiceId
        log("ANSWER fill=$choiceId")
        pendingFillLabel = FillChoices.label(answer, vehicle.energyType.unit)
        dispatch(DialogEvent.FillAnswered(answer))
    }

    fun cancelAsk() {
        stopListening()
        dispatch(DialogEvent.Cancel)
    }

    /** Setting: answer by voice (on by default where speech recognition works). */
    fun setVoiceAnswers(on: Boolean) {
        prefs.voiceAnswers = on
        if (!on) stopListening()
        log("SET voiceAnswers=$on")
        publish()
    }

    /** Debug: what speech recognition returned, as text (tests the understanding without audio). */
    fun hear(text: String) {
        val expect = expecting ?: currentExpect()
        stopListening() // the typed answer replaces the open window
        onHeard(text, expect)
    }

    /** Debug: recorded PCM through the real on-device recognizer (the emulator has no microphone). */
    fun hearPcm(pcm: ByteArray) {
        val expect = expecting ?: currentExpect()
        stopListening()
        val q = questionSeq
        listening = true
        publish()
        listener.recognize(pcm) { text ->
            if (q != questionSeq) return@recognize
            listening = false
            onHeard(text, expect)
        }
    }

    private fun currentExpect(): Expect = when (dialog) {
        DialogState.AskReference -> Expect.REFERENCE
        is DialogState.AskFill -> Expect.FILL
        else -> Expect.CONSENT
    }

    private fun voiceOn(): Boolean = prefs.voiceAnswers && listener.available

    /** Say a question, then open a short listening window once the line has been played (never while speaking). */
    private fun ask(line: String, expect: Expect) {
        stopListening() // one question at a time: an older window closes and its result is ignored
        lastLine = line
        log("SAY $line")
        speaker.voice(prefs.persona ?: Persona.ZZANDORI)
        if (!voiceOn()) {
            val q = ++questionSeq // answered by a tap or replaced: the line is dropped or stopped
            speaker.speakWhile(line, { !stopped && q == questionSeq })
            return
        }
        reprompted = false
        speakAndListen(line, expect)
    }

    /**
     * Speaks [line], then listens for [expect], unless another question, a tap or stop() came in between (its number
     * changed). A line the driver never heard (audio focus taken by a call, a TTS error) is not left hanging: while
     * driving the question waits for the stop.
     */
    private fun speakAndListen(line: String, expect: Expect) {
        expecting = expect
        val q = ++questionSeq
        // The line is dropped (or stopped) once the question is over: answered by a tap, replaced, or the runtime stopped.
        speaker.speakWhile(line, { !stopped && q == questionSeq }) { played ->
            if (stopped || q != questionSeq || expecting != expect) return@speakWhile
            if (played) {
                listen(expect, q)
            } else {
                expecting = null
                log("ASK unheard expect=$expect")
                if (expect == Expect.CONSENT) holdConsentForStop() else dispatch(DialogEvent.Unheard(driving = parked == false))
                publish()
            }
        }
    }

    private fun listen(expect: Expect, q: Int) {
        if (!voiceOn() || stopped) return
        listening = true
        log("LISTEN expect=$expect window=$LISTEN_WINDOW_MS")
        publish()
        listener.listen(LISTEN_WINDOW_MS) { text ->
            if (q != questionSeq) { // the question changed (tapped, cancelled, asked again) while listening
                log("HEARD stale text=${text ?: "-"} expect=$expect")
                return@listen
            }
            listening = false
            onHeard(text, expect)
        }
    }

    private fun stopListening() {
        expecting = null
        questionSeq++ // callbacks of the question that was open are void, and its line if not played yet
        speaker.refresh()
        if (listening) {
            listener.stop()
            listening = false
            publish() // "듣는 중…" goes away even when nothing else changes
        }
    }

    /** While driving, a recommendation question that went unheard or unanswered comes back once at the stop. */
    private fun holdConsentForStop() {
        if (currentRecommendation()?.id == consentAskedAtStopFor) return // asked at a stop already: once is enough
        if (parked == false && approval == Approval.WAITING) {
            consentAtStop = true
            log("ASK consent at the stop")
        }
    }

    /** The closed set of answers (AnswerParser); anything else is asked once more, then left to the screen. */
    private fun onHeard(text: String?, expect: Expect) {
        val heard = AnswerParser.parse(text, expect, places.saved().map { it.id to it.name }, electric = vehicle.energyType.isElectric)
        log("HEARD text=${text ?: "-"} expect=$expect intent=${heard.javaClass.simpleName}${(heard as? Heard.Fill)?.choiceId?.let { " fill=$it" } ?: ""}${(heard as? Heard.Place)?.id?.let { " place=$it" } ?: ""}")
        expecting = null
        when (heard) {
            is Heard.Place -> if (dialog == DialogState.AskReference) answerReference(heard.id)
            is Heard.Fill -> if (dialog is DialogState.AskFill) {
                fillHeard = true // the next recommendation repeats the amount (implicit confirmation)
                answerFill(heard.choiceId)
            }
            Heard.Yes -> if (approval == Approval.WAITING) approve()
            Heard.No -> if (approval == Approval.WAITING) decline()
            Heard.Why -> if (approval == Approval.WAITING) {
                explain()
                speakAndListen(lines().askAgain(), Expect.CONSENT)
            }
            Heard.Nearest -> if (approval == Approval.WAITING) goNearest()
            // "그만" answers the question that was asked: a place or amount question closes, an open decision is a no.
            Heard.Stop -> if (expect == Expect.CONSENT && approval == Approval.WAITING) {
                decline()
            } else {
                cancelAsk()
                say(lines().askClosed())
            }
            Heard.Unknown -> when {
                text.isNullOrBlank() -> {
                    // Silence: nothing is done. While driving an amount question goes on with distance only (p.5) and
                    // a recommendation question comes back once at the stop.
                    if (expect == Expect.CONSENT) holdConsentForStop()
                    dispatch(DialogEvent.NoAnswer(driving = parked == false))
                }
                !reprompted -> {
                    reprompted = true
                    val place = if (expect == Expect.CONSENT) currentRecommendation()?.best?.offer?.name else null
                    val again = lines().reprompt(expect, vehicle.energyType.isElectric, place)
                    log("SAY $again")
                    lastLine = again
                    speakAndListen(again, expect)
                }
                else -> {
                    if (expect == Expect.CONSENT) holdConsentForStop()
                    dispatch(DialogEvent.NoAnswer(driving = parked == false))
                }
            }
        }
        publish()
    }

    /**
     * "그냥 가까운 데로 가" (기획서 p.9): the driver's own words are the consent (p.21), so the nearest compared place is
     * set at once, without a price comparison, and nothing is credited for it.
     */
    private fun goNearest() {
        val rec = currentRecommendation() ?: return
        stopListening()
        val nearest = rec.baseline.offer
        if (nearest.id == rec.best.offer.id) return approve() // the nearest is the recommended place: that is a yes
        append(RecommendationDecided(rec.id, clock.nowMs(), approved = false))
        approval = Approval.DECLINED
        armed = false // like "아니": no new alert until the level rises past the threshold again
        val sent = nearest.location?.let { navigator.navigateTo(nearest.id, nearest.name, it) } ?: false
        log("DECIDE nearest rec=${rec.id} station=${nearest.id} navigation=$sent")
        if (sent) {
            say(lines().navigationStarted(nearest.name))
            lastResult = "${Josa.ro(nearest.name)} 안내했어요 · 가격을 비교하지 않은 선택이라 절약액은 쌓이지 않아요"
        } else { // never claim navigation that did not happen
            say(lines().navigationFailed(driving = parked == false))
            lastResult = "내비 연결이 안 돼 ${Josa.ro(nearest.name)} 안내하지 못했어요."
        }
        publish()
    }

    /** spec D-22: the recommendation threshold, 10~40 %. */
    fun setThreshold(percent: Int) {
        prefs.setThreshold(vehicle.energyType, percent)
        log("SET threshold=${prefs.threshold(vehicle.energyType)} type=${vehicle.energyType}")
        if (routeComparison != null) compareRoutes() else publish() // the stop flags depend on it
    }

    fun approve() {
        val rec = currentRecommendation() ?: return
        if (approval != Approval.WAITING) return
        stopListening() // a tap during the question: no microphone after the decision
        consentAtStop = false
        if (ledger.isClosed(rec.id, clock.nowMs())) return closeStale(rec.id)
        append(RecommendationDecided(rec.id, clock.nowMs(), approved = true))
        approval = Approval.APPROVED
        val best = rec.best.offer
        if (rec.comparison != null) best.location?.let { pass.watch(rec.id, it) }
        val sent = best.location?.let { navigator.navigateTo(best.id, best.name, it) } ?: false
        log("DECIDE approved=true rec=${rec.id} navigation=$sent")
        val what = if (rec.energyType.isElectric) "충전을" else "주유를"
        if (sent) {
            say(lines().navigationStarted(best.name))
            lastResult = "${Josa.ro(best.name)} 안내를 넘겼어요. $what 기다리는 중"
        } else {
            // Never claim navigation that did not happen.
            say(lines().navigationFailed(driving = parked == false))
            lastResult = "내비 연결이 안 돼 안내를 넘기지 못했어요." +
                if (rec.comparison != null) " ${best.name}에서 넣으면 쌓여요" else ""
        }
        publish()
        afterApprove?.invoke()
    }

    fun decline() {
        val rec = currentRecommendation() ?: return
        if (approval != Approval.WAITING) return
        stopListening()
        consentAtStop = false
        if (ledger.isClosed(rec.id, clock.nowMs())) return closeStale(rec.id)
        append(RecommendationDecided(rec.id, clock.nowMs(), approved = false))
        approval = Approval.DECLINED
        armed = false // "아니" holds until the level rises past the threshold again
        log("DECIDE approved=false rec=${rec.id}")
        say(lines().declined())
        lastResult = "이번 추천은 넘겼어요. '응'으로 고른 추천만 절약으로 쌓여요."
        publish()
    }

    /** "왜 거기야?": the reason in numbers (spec F05). */
    fun explain() {
        val rec = currentRecommendation() ?: return
        stopListening()
        val best = rec.best.offer
        val target = rec.comparison
        val cheaperBy = target?.offer?.unitPrice?.subtract(best.unitPrice)?.toWon() ?: 0
        val text = when {
            rec.mode == CompareMode.ON_ROUTE -> "가는 길에서 ${rec.energyType.unit}당 제일 싼 곳이야."
            target == null -> "가격은 빼고 가까운 곳으로 골랐어."
            rec.recommendsBaseline || target.offer.id == best.id -> lines().nearestChosen(best.name)
            // One line from the same numbers as the card (no separately rounded totals).
            cheaperBy > 0 -> lines().explain(best.name, rec.energyType.unit, cheaperBy, rec.expectedSaving.toWon())
            else -> "더 가는 비용까지 합해도 ${WonFormat.spoken(rec.expectedSaving.toWon())} 덜 들어."
        }
        say(text)
        publish() // the reason shows in the bubble at once
    }

    /** Debug/demo: start at level 2 with 3,000 won and 짠순이 (시나리오 B). */
    fun seedScenarioB() = seed(level = 2, accrual = 3_000, persona = Persona.ZZANSUNI)

    /** Debug/demo: start at level 0 with 1,000 won and 짠돌이 (시나리오 A). */
    fun seedScenarioA() = seed(level = 0, accrual = 1_000, persona = Persona.ZZANDORI)

    /**
     * Debug/demo: jump to [level] (accrual 0) and play that level's level-up screen and line when parked,
     * to walk through all 11 levels. Replaces the savings record, like the scenario seeds.
     */
    fun previewLevel(level: Int, persona: Persona? = null) {
        val l = level.coerceIn(0, progress.maxLevel)
        seed(level = l, accrual = 0, persona = persona ?: prefs.persona ?: Persona.ZZANDORI)
        if (l == 0) return
        prefs.lastCelebratedLevel = l - 1
        prefs.lastAnnouncedLevel = l - 1
        announcePendingCelebrations()
        publish()
    }

    fun resetAll() {
        store.clear()
        prefs.clear()
        ledger = Ledger(progress)
        detector = newDetector()
        approval = Approval.NONE
        armed = true
        lastResult = null
        lastLine = null
        reaction = null
        dialog = DialogState.Idle
        pass.clear()
        pendingParkedLine = null
        routeComparison = null
        consentAtStop = false
        stopListening()
        log("RESET")
        publish()
    }

    fun celebrationShown(level: Int) {
        if (level > prefs.lastCelebratedLevel) prefs.lastCelebratedLevel = level
        publish()
    }

    // ------------------------------------------------------------------ vehicle data

    private fun onSample(s: VehicleSample) {
        vehicleOrigin = s.origin
        when (s) {
            is EnergySample -> {
                if (lastEnergy == null) { // once per start: proves vehicle data reaches the runtime (verify-app.sh)
                    log("DATA first energy=${"%.2f".format(s.amount)} ${vehicle.energyType.unit} soc=${s.socPercent?.let { "%.0f".format(it) }} origin=${s.origin}")
                }
                lastEnergy = s
                if (vehicle.energyType != energyType) { // mode decided after start (R-10): rebuild the detector
                    energyType = vehicle.energyType
                    detector = newDetector()
                    log("MODE ${modeName()} (${modeReason()})")
                }
                checkTrigger(s)
            }
            is ChargeHintSample -> lastHint = s
            is DriveSample -> {
                val wasParked = parked
                parked = s.parked
                gear = s.gear
                if (wasParked != true && s.parked) {
                    pendingParkedLine?.let { line -> pendingParkedLine = null; say(line) }
                    if (consentAtStop) {
                        consentAtStop = false
                        val rec = currentRecommendation()
                        if (rec != null && approval == Approval.WAITING) {
                            consentAskedAtStopFor = rec.id
                            ask(lines().askAgainAtStop(rec.best.offer.name), Expect.CONSENT)
                        }
                    }
                    announcePendingCelebrations()
                    dispatch(DialogEvent.Parked) // a low level noticed while driving is asked about now
                }
            }
            is LocationSample -> {
                location = s.point
                pass.onLocation(s.point, detector.isRefilling)?.let(::onPassed)
            }
        }
        detector.onSample(s)?.let(::onRefill)
        publish()
    }

    private fun scheduleTick(): Cancellable = scheduler.schedule(1_000) {
        detector.tick(clock.nowMs())?.let(::onRefill)
        // An open card whose recommendation is past the 12-hour window closes by itself.
        if (recommendationOpen()) ledger.latestRecommendation()?.takeIf { ledger.isClosed(it.recId, clock.nowMs()) }?.let { closeStale(it.recId) }
        ticker = scheduleTick()
    }

    private fun closeStale(recId: String) {
        log("REC closed rec=$recId (over or older than 12 h)")
        approval = Approval.SETTLED
        publish()
    }

    /**
     * Low energy trigger (spec D-22): the threshold is a setting (default EV 25 %, fuel 20 %) because apps cannot read
     * the warning lights; re-armed 5 %p above it. Nothing new while a recommendation is still open.
     */
    private fun checkTrigger(e: EnergySample) {
        val pct = e.socPercent ?: return
        val threshold = prefs.threshold(vehicle.energyType)
        if (TriggerPolicy.rearms(pct, threshold)) armed = true
        if (armed && TriggerPolicy.fires(pct, threshold)) {
            armed = false // the crossing is used up even when a card or a question is already open
            if (recommendationOpen() || dialog != DialogState.Idle) {
                log("LOW level=${pct.toInt()}% threshold=$threshold suppressed (open)")
                return
            }
            log("LOW level=${pct.toInt()}% threshold=$threshold parked=$parked")
            // 기획서 p.5: ask the amount first. While driving without voice answers, one line now and the question after the stop.
            dispatch(DialogEvent.Low(parked = parked != false, canAnswerByVoice = voiceOn()))
        }
    }

    private fun recommendationOpen(): Boolean = approval == Approval.WAITING || approval == Approval.APPROVED

    private fun efficiency(type: EnergyType): Efficiency =
        if (type.isElectric) PlanReference.DEMO_EV_EFFICIENCY else PlanReference.DEMO_FUEL_EFFICIENCY

    /** The fixed-amount flow (debug "compare"): 40 kWh / 50 L (the demo assumption) for every candidate, against the nearest. */
    private fun issueRecommendation(reason: String) {
        val type = vehicle.energyType
        val planned = if (type.isElectric) bd(40) else bd(50)
        val rec = Recommender.recommend("rec-${clock.nowMs()}", clock.nowMs(), type, planned, efficiency(type), stations.offers(type, location), location)
            ?: run { log("REC none (no compatible station)"); return }
        issue(rec, reason)
    }

    /** The plan's flow (spec D-23..D-27): candidates around the reference place, priced with the answered amount. */
    private fun planRecommendation(ref: Reference, fill: FillAnswer, onRoute: Boolean, reason: String) {
        val type = vehicle.energyType
        val now = clock.nowMs()
        // With a destination, the automatic case is ②-1·③: only stations the car can still reach (spec D-30).
        val mustStop = if (!onRoute && reason == "low") mustStopPlan(type) else null
        val offers = mustStop?.second ?: stations.candidates(type, ref.point ?: location)
        val context = when {
            onRoute -> PlanContext.OnRouteRequest
            mustStop != null -> PlanContext.MustStop(mustStop.first)
            else -> PlanContext.NoDestination
        }
        val request = PlanRequest(
            id = "rec-$now", nowMs = now, energyType = type, offers = offers, efficiency = efficiency(type), fill = fill,
            room = roomLeft(), context = context, referenceName = if (mustStop != null) "경로" else ref.name,
            fillLabel = pendingFillLabel ?: FillChoices.label(fill, type.unit), issueLocation = location,
        )
        pendingFillLabel = null
        if (mustStop == null) {
            val (inside, outside) = CandidatePolicy.split(offers.filter { it.energyType == type })
            log("CAND ref=${ref.id} in=${inside.joinToString(",") { it.id }} excluded=${outside.joinToString(",") { "${it.id}:${it.oneWayKm.stripTrailingZeros().toPlainString()}" }}")
        }
        val rec = Recommender.plan(request) ?: run {
            if (onRoute) { // nothing known on the route yet: ask the usual way instead of a dead end
                log("REC none on route, asking instead")
                return dispatch(DialogEvent.Find(routeActive = false))
            }
            log("REC none (no compatible station)")
            say("주변에 갈 만한 곳을 못 찾았어.")
            return publish()
        }
        issue(rec, reason)
    }

    /**
     * ②-1·③ (spec D-30): the branch for the guided route and the stations within reach. The level is already at the
     * threshold when this runs, so "before the threshold" leaves no room: both branches plan a stop within 90 % of
     * the remaining range. Null without a route or a readable level (then the search is around the current location).
     */
    private fun mustStopPlan(type: EnergyType): Pair<RouteBranch, List<com.zzandrive.data.api.StationOffer>>? {
        val route = routes.active(type) ?: return null
        val e = lastEnergy ?: return null
        val cap = e.capacity ?: return null
        val now = e.amount ?: e.socPercent?.let { cap * it / 100 } ?: return null
        val energyNow = java.math.BigDecimal.valueOf(now)
        val thresholdAmount = java.math.BigDecimal.valueOf(cap * prefs.threshold(type) / 100.0)
        val eff = efficiency(type).kmPerUnit
        val branch = Reachability.classify(energyNow, thresholdAmount, route.remainingKm, eff)
        val limit = Reachability.limitKm(RouteBranch.UNREACHABLE, energyNow, thresholdAmount, eff)
        val reachable = Reachability.reachable(route.stations.filter { it.energyType == type }, limit)
        log(
            "ROUTE dest=${route.destinationName} remainKm=${route.remainingKm.toPlainString()} branch=$branch " +
                "limitKm=${limit?.setScale(1, java.math.RoundingMode.HALF_UP)?.toPlainString()} reachable=${reachable.joinToString(",") { it.id }}",
        )
        return if (reachable.isEmpty()) null else branch to reachable
    }

    private fun issue(rec: Recommendation, reason: String) {
        val saving = rec.expectedSaving.toWon()
        append(RecommendationIssued.of(rec, saving))
        approval = Approval.WAITING
        // No automatic one right after a manual one in the low zone; above it the low alert stays armed.
        val pct = lastEnergy?.socPercent
        if (pct == null || TriggerPolicy.fires(pct, prefs.threshold(vehicle.energyType))) armed = false
        lastResult = null
        log(
            "REC id=${rec.id} reason=$reason baseline=${rec.baseline.offer.id} best=${rec.best.offer.id} expected=$saving " +
                "mode=${rec.mode} rule=${rec.creditRule} qty=${rec.plannedQuantity.stripTrailingZeros().toPlainString()}",
        )
        // The amount heard by voice is said back ("40kWh면", "가득이면") so a mishearing is caught (implicit confirmation).
        val echo = if (fillHeard && rec.mode == CompareMode.COST_INCLUDED) rec.fillLabel?.let(Josa::myeon) else null
        fillHeard = false
        ask(recommendationLine(rec, saving, echo), Expect.CONSENT)
        publish()
    }

    private fun recommendationLine(rec: Recommendation, saving: Long, echo: String? = null): String {
        val v = lines()
        val best = rec.best.offer
        val electric = rec.energyType.isElectric
        val lead = echo?.let { "$it, " } ?: ""
        return when (rec.mode) {
            CompareMode.COST_INCLUDED -> when {
                rec.creditRule == CreditRule.VS_WORST -> v.mustStop(electric, best.name, saving, echo) // the amount after "못 가"
                rec.recommendsBaseline -> lead + v.nearestIsCheapest(best.name)
                parked == false -> lead + v.recommendShort(best.name, saving) // driving: one place, one amount
                electric -> lead + v.recommendCharge(rec.baseline.offer.name, best.name, saving)
                else -> lead + v.recommendRefuel(rec.baseline.offer.name, best.name, saving)
            }
            CompareMode.ROUTE_ONLY -> v.routeOnly(best.name, km(best.oneWayKm))
            CompareMode.NEAREST_ONLY -> v.nearestOnly(best.name, km(best.oneWayKm))
            CompareMode.ON_ROUTE -> v.onRoute(best.name, best.unitPrice.toWon(), rec.energyType.unit)
        }
    }

    private fun km(v: java.math.BigDecimal): String = v.setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** Capacity − current level (litres or kWh), for "가득" and as a cap; null when unknown. */
    private fun roomLeft(): java.math.BigDecimal? {
        val e = lastEnergy ?: return null
        val cap = e.capacity ?: return null
        val now = e.amount ?: e.socPercent?.let { cap * it / 100 } ?: return null
        return java.math.BigDecimal.valueOf(cap - now).setScale(2, java.math.RoundingMode.HALF_UP).takeIf { it.signum() > 0 }
    }

    // ------------------------------------------------------------------ conversation (기획서 p.5, one question at a time)

    private var pendingFillLabel: String? = null

    private fun references(): List<Reference> =
        listOf(Reference.CURRENT) + places.saved().map { Reference(it.id, it.name, it.point) }

    private fun dispatch(event: DialogEvent) {
        val (next, effect) = StationDialog.reduce(dialog, event)
        dialog = next
        when (effect) {
            DialogEffect.None -> Unit
            DialogEffect.AskReference -> {
                log("ASK reference")
                ask(lines().askReference(), Expect.REFERENCE)
            }
            is DialogEffect.AskFill -> {
                log("ASK fill reason=${if (effect.proactive) "low" else "find"}")
                val electric = vehicle.energyType.isElectric
                val level = lastEnergy?.socPercent?.takeIf { effect.proactive }?.let { lines().lowLevel(electric, it.toInt()) + " " } ?: ""
                ask(level + lines().askFill(electric), Expect.FILL)
            }
            is DialogEffect.Recommend -> planRecommendation(effect.reference, effect.fill, effect.onRoute, if (effect.proactive) "low" else "find")
            DialogEffect.DrivingNotice -> {
                log("NOTICE low while driving")
                say(lines().drivingNotice(vehicle.energyType.isElectric))
            }
        }
        publish()
    }

    private fun askUi(): AskUi? = when (val d = dialog) {
        DialogState.AskReference -> AskUi(
            AskStep.REFERENCE,
            "어디 근처로 찾을까요?",
            "도로로 5km 안의 곳을 비교해요",
            references().mapIndexed { i, r -> AskChoice(r.id, if (r.id == Reference.CURRENT.id) r.name else "${r.name} (저장한 곳)", emphasized = i == 0) },
        )
        is DialogState.AskFill -> {
            val choices = FillChoices.options(vehicle.energyType, roomLeft()).map { (id, label) -> AskChoice(id, label) }
            val last = prefs.lastFillChoice?.takeIf { id -> choices.any { it.id == id } } ?: "full"
            AskUi(
                AskStep.FILL,
                if (vehicle.energyType.isElectric) "얼마나 충전할까요?" else "얼마나 넣을까요?",
                "${d.reference.name} 기준 · 건너뛰면 가격 없이 가까운 곳만 봐요",
                choices.map { it.copy(emphasized = it.id == last) },
            )
        }
        else -> null
    }

    private fun onRefill(d: RefillDetected) {
        if (ledger.isDetectionHandled(d.id)) return
        pass.clear() // a refill happened: not a pass
        // Only a recommendation that is still open counts (a settled one cannot be credited twice).
        val issued = ledger.events.filterIsInstance<RecommendationIssued>()
            // Approved and not ruled out: within 12 h it can still be credited (split charge); past 12 h with no
            // refill yet it is settled as EXPIRED below. One that already had a refill and is old stays out.
            .lastOrNull { r ->
                ledger.approved(r.recId) && !ledger.isEnded(r.recId) &&
                    (d.endMs - r.atMs <= SettlementPolicy.MATCH_WINDOW_MS || !ledger.hadRefill(r.recId))
            }
            ?: ledger.openRecommendation(d.endMs)
            ?: ledger.latestRecommendation()?.takeIf { ledger.isDeclined(it.recId) && d.endMs - it.atMs <= SettlementPolicy.MATCH_WINDOW_MS }
        val rec = issued?.toRecommendation()
        val at = d.location ?: location
        val used = rec?.let { r -> StationMatcher.match(at, r.ranked.associate { it.offer.id to it.offer.location }) }
        log("DETECT id=${d.id} q=${"%.3f".format(d.quantity)} basis=${d.basis} at=${at?.let { "%.5f,%.5f".format(it.lat, it.lon) }} station=$used")
        append(
            RefillRecorded(
                detectionId = d.id, atMs = d.endMs, startMs = d.startMs, energyType = energyType.name,
                quantity = d.quantity.toString(), basis = d.basis.name, lat = at?.lat, lon = at?.lon, matchedStationId = used,
            ),
        )
        val approved = issued?.let { ledger.approved(it.recId) } ?: false
        val qtyText = "%.1f%s".format(d.quantity, energyType.unit)
        when (val decision = SettlementPolicy.decide(rec, approved, d, used, locationKnown = at != null)) {
            is SettlementDecision.Credit -> {
                val result = append(SavingConfirmed(rec!!.id, d.id, d.endMs, decision.amountWon, decision.raw.toPlainString()))
                    ?: run { log("SETTLE duplicate rec=${rec.id} ignored"); publish(); return }
                approval = Approval.SETTLED
                val s = ledger.state
                log("SETTLE amount=${decision.amountWon} level=${s.level} accrual=${s.accrualWon} remaining=${progress.remainingToNext(s)} reached=${result.levelsReached}")
                lastResult = "$qtyText 확인 · ${WonFormat.screen(decision.amountWon)} 절약 확정"
                // Voice is fine while driving; the level-up line and screen wait for a stop (R-46).
                val leveledUp = result.levelsReached.isNotEmpty()
                if (!leveledUp || parked != true) say(lines().savingConfirmed(decision.amountWon))
                if (!leveledUp) reaction = Reaction(d.id, happy = true) // a level-up has its own scene
                announcePendingCelebrations()
            }
            is SettlementDecision.Skip -> {
                append(SettlementSkipped(rec?.id, d.id, d.endMs, decision.reason.name, decision.rawWon))
                log("SETTLE skipped reason=${decision.reason} raw=${decision.rawWon}")
                // A refill closes whatever card was open, so the next low level can recommend again.
                if (recommendationOpen()) approval = Approval.SETTLED
                lastResult = "$qtyText 확인 · 적립 안 함: ${decision.reason.message}"
                // A refill nobody recommended is not the driver's failure: no voice, no sad face.
                if (approved && decision.reason in SAD_REASONS) reaction = Reaction(d.id, happy = false)
                // "'응' 한 추천이 아니라서" only when they did stop at the recommended place.
                val speak = when (decision.reason) {
                    SkipReason.NO_RECOMMENDATION -> false
                    SkipReason.NOT_APPROVED -> rec != null && used == rec.best.offer.id
                    else -> approved
                }
                if (speak) lines().notCredited(decision.reason)?.let(::say)
            }
        }
        publish()
    }

    /**
     * spec D-28: the car came within 300 m of the approved station and left beyond 500 m without refuelling or
     * charging. Deduct min(promised saving, extra trip) once, close the recommendation, and say it after stopping.
     */
    private fun onPassed(recId: String) {
        val issued = ledger.recommendation(recId) ?: return
        val now = clock.nowMs()
        if (!ledger.approved(recId) || ledger.isClosed(recId, now)) return
        val rec = issued.toRecommendation()
        val amount = OpportunityCost.amountWon(rec, approved = true) // checked above
        log("PASS station=${rec.best.offer.id} rec=$recId amount=$amount")
        val result = append(OpportunityCostConfirmed(recId, now, amount)) ?: return
        if (recommendationOpen()) approval = Approval.SETTLED
        val s = ledger.state
        log("OPP amount=$amount applied=${result.penaltyAppliedWon ?: 0} level=${s.level} accrual=${s.accrualWon}")
        if (amount > 0) {
            lastResult = "${Josa.reul(rec.best.offer.name)} 그냥 지나쳐서 ${WonFormat.screen(amount)}을 뺐어요 (기회비용)"
            reaction = Reaction("opp-$recId", happy = false)
            val line = lines().opportunityCost(rec.best.offer.name, amount)
            if (parked == true) say(line) else pendingParkedLine = line
        } else {
            lastResult = "${Josa.reul(rec.best.offer.name)} 지나쳤어요 · 가까운 곳이 추천이라 빼는 금액은 없어요"
        }
        publish()
    }

    /**
     * Level-up lines play only when parked (R-46), once per level: a restart does not repeat them.
     * The level-up screen is separate and stays until the driver closes it ([celebrationShown]).
     */
    private fun announcePendingCelebrations() {
        val pending = CelebrationPolicy.pending(prefs.lastAnnouncedLevel, ledger.state.level, parked == true)
        val top = pending.maxOrNull() ?: return
        prefs.lastAnnouncedLevel = top
        val s = ledger.state
        val line = if (top == s.level) {
            lines().levelUp(progress.food(top), progress.nextFood(top), progress.remainingToNext(s))
        } else {
            lines().levelUp(progress.food(top), progress.nextFood(top), null)
        }
        say(line)
    }

    // ------------------------------------------------------------------ helpers

    private fun seed(level: Int, accrual: Long, persona: Persona) {
        resetAll()
        prefs.persona = persona
        prefs.lastCelebratedLevel = level
        prefs.lastAnnouncedLevel = level
        append(OpeningBalance("seed-${clock.nowMs()}", clock.nowMs(), level, accrual))
        log("SEED level=$level accrual=$accrual persona=$persona")
        publish()
    }

    private fun append(e: LedgerEvent) = ledger.append(e).takeIf { it.accepted }?.also { store.append(e) }

    private fun currentRecommendation(): Recommendation? = ledger.latestRecommendation()?.toRecommendation()

    /** A recommendation older than the 12-hour window, or ended by a refill, does not come back after a restart. */
    private fun restoreApproval() {
        val latest = ledger.latestRecommendation() ?: return
        val now = clock.nowMs()
        // This discharge cycle already had its recommendation; ask again only after the level rises.
        if (now - latest.atMs <= SettlementPolicy.MATCH_WINDOW_MS) armed = false
        approval = when {
            ledger.isDeclined(latest.recId) -> Approval.DECLINED
            ledger.isClosed(latest.recId, now) || ledger.hadRefill(latest.recId) -> Approval.SETTLED
            ledger.approved(latest.recId) -> Approval.APPROVED
            else -> Approval.WAITING
        }
        if (approval == Approval.APPROVED) {
            val rec = latest.toRecommendation()
            if (rec.comparison != null) rec.best.offer.location?.let { pass.watch(rec.id, it) }
        }
    }

    private fun lines() = VoiceLines(prefs.persona ?: Persona.ZZANDORI)

    private fun say(text: String) {
        lastLine = text
        log("SAY $text")
        speaker.voice(prefs.persona ?: Persona.ZZANDORI)
        speaker.speakWhile(text, { !stopped })
    }

    /** A short note on the screen (demo scene changes). */
    fun note(text: String) {
        lastResult = text
        publish()
    }

    /** Ends an open question (another runtime takes over the screen and the microphone). */
    fun endQuestion() = stopListening()

    private fun newDetector() = if (vehicle.energyType.isElectric) {
        RefillDetector(RefillConfig.EV, capacityFallback = 77.4, idPrefix = "charge")
    } else {
        RefillDetector(RefillConfig.FUEL, idPrefix = "refuel")
    }

    private fun modeName() = if (vehicle.energyType.isElectric) "EV" else "FUEL"

    private fun state(): UiState {
        val s = ledger.state
        val today = LocalDate.now(zone)
        return UiState(
            demo = demo,
            energyType = vehicle.energyType,
            modeReason = modeReason(),
            vehicleOrigin = vehicleOrigin,
            socPercent = lastEnergy?.socPercent,
            energy = lastEnergy?.amount,
            capacity = lastEnergy?.capacity,
            parked = parked,
            gear = gear,
            chargeHint = lastHint,
            location = location,
            persona = prefs.persona,
            character = s,
            remainingWon = progress.remainingToNext(s),
            totalSavedWon = progress.totalSaved(s),
            todayNetWon = ledger.dailyNet(today),
            monthNetWon = ledger.monthlyNet(YearMonth.from(today)),
            recommendation = if (approval == Approval.NONE) null else currentRecommendation(),
            approval = approval,
            refilling = detector.isRefilling,
            lastResult = lastResult,
            lastLine = lastLine,
            pendingCelebrations = CelebrationPolicy.pending(prefs.lastCelebratedLevel, s.level, parked == true),
            today = today,
            reaction = reaction,
            ask = askUi(),
            threshold = prefs.threshold(vehicle.energyType),
            listening = listening,
            voiceAnswers = prefs.voiceAnswers,
            voiceAvailable = listener.available,
            routes = routeComparison,
            routesPending = routesPending,
            wage = prefs.wage,
        )
    }

    private fun publish() {
        val s = state()
        observers.toList().forEach { it(s) }
    }

    private fun log(msg: String) = Log.i(if (demo) DEMO_TAG else TAG, msg)

    /** For tests and the diagnostics line: the raw ledger as JSON lines. */
    fun ledgerLines(): List<String> = ledger.events.map(LedgerCodec::encode)

    companion object {
        /** How long the microphone stays open after a question (the SDK has no timeout setting, spec V-13). */
        const val LISTEN_WINDOW_MS = 5_000L

        /** Skips that mean an approved plan did not work out: the character looks sad. */
        private val SAD_REASONS = setOf(SkipReason.OTHER_STATION, SkipReason.NOT_POSITIVE, SkipReason.EXPIRED, SkipReason.NO_LOCATION)

        /** Live runtime log tag (scripts/e2e-scenario-b.sh reads it). */
        const val TAG = "ZzanApp"

        /** Demo-mode log tag, so demo lines never satisfy a live check and vice versa. */
        const val DEMO_TAG = "ZzanDemo"
    }
}
