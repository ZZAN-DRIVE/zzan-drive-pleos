package com.zzandrive.core

import com.zzandrive.core.dialog.DialogEffect
import com.zzandrive.core.dialog.DialogEvent
import com.zzandrive.core.dialog.DialogState
import com.zzandrive.core.dialog.Reference
import com.zzandrive.core.dialog.StationDialog
import com.zzandrive.core.domain.FillAnswer
import com.zzandrive.core.domain.bd
import com.zzandrive.data.api.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The plan's conversation (p.5 ④⑥, p.9): one question at a time, consent stays a separate step. */
class StationDialogTest {
    private val parents = Reference("parents", "본가", GeoPoint(37.491, 127.488))

    @Test
    fun `no answer to the amount while driving goes on with distance only, an unheard question waits for the stop`() {
        val asking = DialogState.AskFill(Reference.CURRENT, proactive = true)
        assertEquals(
            DialogState.Idle to DialogEffect.Recommend(Reference.CURRENT, FillAnswer.Skip, onRoute = false, proactive = true),
            StationDialog.reduce(asking, DialogEvent.NoAnswer(driving = true)),
        )
        assertEquals(asking to DialogEffect.None, StationDialog.reduce(asking, DialogEvent.NoAnswer(driving = false))) // the card stays
        assertEquals(DialogState.WaitForStop to DialogEffect.None, StationDialog.reduce(asking, DialogEvent.Unheard(driving = true)))
        assertEquals(
            DialogState.AskFill(Reference.CURRENT, proactive = true) to DialogEffect.AskFill(proactive = true),
            StationDialog.reduce(DialogState.WaitForStop, DialogEvent.Parked),
        )
    }

    @Test
    fun `a search asks the place, then the amount, then recommends`() {
        var (s, e) = StationDialog.reduce(DialogState.Idle, DialogEvent.Find(routeActive = false))
        assertEquals(DialogState.AskReference, s)
        assertEquals(DialogEffect.AskReference, e)
        StationDialog.reduce(s, DialogEvent.ReferenceChosen(parents)).let { (ns, ne) -> s = ns; e = ne }
        assertEquals(DialogState.AskFill(parents, proactive = false), s)
        assertEquals(DialogEffect.AskFill(proactive = false), e)
        StationDialog.reduce(s, DialogEvent.FillAnswered(FillAnswer.Money(bd(10000)))).let { (ns, ne) -> s = ns; e = ne }
        assertEquals(DialogState.Idle, s)
        assertEquals(DialogEffect.Recommend(parents, FillAnswer.Money(bd(10000)), onRoute = false, proactive = false), e)
    }

    @Test
    fun `while guiding a search needs no questions - the cheapest on the route`() {
        val (s, e) = StationDialog.reduce(DialogState.Idle, DialogEvent.Find(routeActive = true))
        assertEquals(DialogState.Idle, s)
        assertEquals(DialogEffect.Recommend(Reference.CURRENT, FillAnswer.Skip, onRoute = true, proactive = false), e)
    }

    @Test
    fun `low level while parked asks only the amount, around the current location`() {
        val (s, e) = StationDialog.reduce(DialogState.Idle, DialogEvent.Low(parked = true))
        assertEquals(DialogState.AskFill(Reference.CURRENT, proactive = true), s)
        assertEquals(DialogEffect.AskFill(proactive = true), e)
    }

    @Test
    fun `low level while driving says one line and asks after the stop`() {
        val (s, e) = StationDialog.reduce(DialogState.Idle, DialogEvent.Low(parked = false))
        assertEquals(DialogState.WaitForStop, s)
        assertEquals(DialogEffect.DrivingNotice, e)
        val (s2, e2) = StationDialog.reduce(s, DialogEvent.Parked)
        assertEquals(DialogState.AskFill(Reference.CURRENT, proactive = true), s2)
        assertEquals(DialogEffect.AskFill(proactive = true), e2)
        // Stopping again later changes nothing.
        assertEquals(DialogState.Idle to DialogEffect.None, StationDialog.reduce(DialogState.Idle, DialogEvent.Parked))
    }

    @Test
    fun `stray events are ignored and cancel always returns to idle`() {
        assertEquals(DialogState.Idle to DialogEffect.None, StationDialog.reduce(DialogState.Idle, DialogEvent.FillAnswered(FillAnswer.Full)))
        assertEquals(DialogState.Idle to DialogEffect.None, StationDialog.reduce(DialogState.Idle, DialogEvent.ReferenceChosen(parents)))
        val asking = DialogState.AskFill(parents, proactive = false)
        assertEquals(asking to DialogEffect.None, StationDialog.reduce(asking, DialogEvent.Low(parked = true))) // no second topic
        assertEquals(DialogState.Idle to DialogEffect.None, StationDialog.reduce(asking, DialogEvent.Cancel))
    }
}
