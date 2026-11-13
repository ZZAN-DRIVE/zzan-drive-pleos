package com.zzandrive.core.dialog

import com.zzandrive.core.domain.FillAnswer
import com.zzandrive.data.api.GeoPoint

/** Where a search is centred (기획서 p.5 ④ "현재 위치를 기준으로 볼지, 사용자가 지정한 다른 장소를 기준으로 볼지"). */
data class Reference(val id: String, val name: String, val point: GeoPoint?) {
    companion object {
        /** The car's position at the time of the search. */
        val CURRENT = Reference("current", "현재 위치", null)
    }
}

/** One question at a time (Google/Alexa conversation design; 기획서 p.9 the "응" consent is a separate step). */
sealed interface DialogState {
    data object Idle : DialogState

    /** "어디 근처로 찾을까?" — only when the driver asked (p.5 ④); the automatic case uses the current location (⑥). */
    data object AskReference : DialogState

    /** "얼마나 넣을까?" (p.5 "양 또는 금액을 먼저 묻고"). [proactive]: started by the low level, not by the driver. */
    data class AskFill(val reference: Reference, val proactive: Boolean) : DialogState

    /**
     * The level got low while driving and the amount cannot be asked by voice now (voice answers off, or the question
     * was not heard). The screen is blocked, so the character asks after the car stops.
     */
    data object WaitForStop : DialogState
}

sealed interface DialogEvent {
    /** [찾기] (p.5 ④⑥). [routeActive]: navigation is guiding, so the cheapest on the route is offered (⑥). */
    data class Find(val routeActive: Boolean) : DialogEvent

    /**
     * The level reached the threshold (p.4, p.9). [canAnswerByVoice]: speech recognition is on, so even while driving
     * the amount can be asked by voice (one short question, a few seconds of listening, nothing done on silence).
     */
    data class Low(val parked: Boolean, val canAnswerByVoice: Boolean = false) : DialogEvent

    /** The car came to a stop (gear P). */
    data object Parked : DialogEvent

    data class ReferenceChosen(val reference: Reference) : DialogEvent

    data class FillAnswered(val answer: FillAnswer) : DialogEvent

    /**
     * The driver did not answer (silence, or twice not understood). While driving the amount question then goes on
     * without a price, distance only (기획서 p.5·p.9 "답하지 않으면 … 경로만 비교", spec D-25); parked, the card stays.
     */
    data class NoAnswer(val driving: Boolean) : DialogEvent

    /** The question was never heard (audio focus taken by a call, TTS error): while driving it is asked again at the stop. */
    data class Unheard(val driving: Boolean) : DialogEvent

    data object Cancel : DialogEvent
}

sealed interface DialogEffect {
    data object None : DialogEffect
    data object AskReference : DialogEffect
    data class AskFill(val proactive: Boolean) : DialogEffect
    data class Recommend(val reference: Reference, val fill: FillAnswer, val onRoute: Boolean, val proactive: Boolean) : DialogEffect
    data object DrivingNotice : DialogEffect
}

/** The conversation as a pure state machine (tested on the JVM; the controller only performs the effects). */
object StationDialog {
    fun reduce(state: DialogState, event: DialogEvent): Pair<DialogState, DialogEffect> = when (event) {
        DialogEvent.Cancel -> DialogState.Idle to DialogEffect.None
        is DialogEvent.Find ->
            if (event.routeActive) {
                DialogState.Idle to DialogEffect.Recommend(Reference.CURRENT, FillAnswer.Skip, onRoute = true, proactive = false)
            } else {
                DialogState.AskReference to DialogEffect.AskReference
            }
        is DialogEvent.Low -> when {
            state != DialogState.Idle -> state to DialogEffect.None // already talking about it
            event.parked || event.canAnswerByVoice -> DialogState.AskFill(Reference.CURRENT, proactive = true) to DialogEffect.AskFill(proactive = true)
            else -> DialogState.WaitForStop to DialogEffect.DrivingNotice
        }
        is DialogEvent.NoAnswer ->
            if (event.driving && state is DialogState.AskFill) {
                DialogState.Idle to DialogEffect.Recommend(state.reference, FillAnswer.Skip, onRoute = false, proactive = state.proactive)
            } else {
                state to DialogEffect.None
            }
        is DialogEvent.Unheard ->
            if (event.driving && state is DialogState.AskFill && state.proactive) {
                DialogState.WaitForStop to DialogEffect.None // ask again once the car stops
            } else {
                state to DialogEffect.None
            }
        DialogEvent.Parked ->
            if (state == DialogState.WaitForStop) {
                DialogState.AskFill(Reference.CURRENT, proactive = true) to DialogEffect.AskFill(proactive = true)
            } else {
                state to DialogEffect.None
            }
        is DialogEvent.ReferenceChosen ->
            if (state == DialogState.AskReference) {
                DialogState.AskFill(event.reference, proactive = false) to DialogEffect.AskFill(proactive = false)
            } else {
                state to DialogEffect.None
            }
        is DialogEvent.FillAnswered ->
            if (state is DialogState.AskFill) {
                DialogState.Idle to DialogEffect.Recommend(state.reference, event.answer, onRoute = false, proactive = state.proactive)
            } else {
                state to DialogEffect.None
            }
    }
}
