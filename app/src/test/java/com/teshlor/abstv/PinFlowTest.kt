package com.teshlor.abstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinFlowTest {
    private val now = 1_000_000L

    private fun type(start: PinPadState, digits: String): PinPadState = digits.fold(start) { s, d -> s.press(d, now) }

    // --- pad state ---
    @Test fun digitsFillAndTheFourthLocksInputAndSubmits() {
        var s = PinPadState()
        s = type(s, "123")
        assertEquals("123", s.digits); assertFalse(s.checking); assertNull(s.complete)
        s = s.press('4', now)
        assertEquals("1234", s.complete); assertTrue(s.checking)
        // locked while the check runs
        assertEquals(s, s.press('5', now))
        assertEquals(s, s.delete(now))
    }

    @Test fun deleteRemovesTheLastDigitOnly() {
        var s = type(PinPadState(), "12")
        s = s.delete(now); assertEquals("1", s.digits)
        s = s.delete(now).delete(now); assertEquals("", s.digits) // nothing left to delete
    }

    @Test fun onlyDigitsAreAccepted() {
        assertEquals("", PinPadState().press('a', now).digits)
        assertEquals("", PinPadState().press(' ', now).digits)
    }

    @Test fun clearedAfterAWrongPinReopensInput() {
        val s = type(PinPadState(), "1234").cleared()
        assertEquals("", s.digits); assertFalse(s.checking)
        assertEquals("5", s.press('5', now).digits)
    }

    @Test fun cooldownIgnoresInputUntilItEnds() {
        val s = PinPadState().cooling(now + 30_000)
        assertTrue(s.coolingDown(now))
        assertEquals("", s.press('1', now).digits)
        assertEquals("", s.press('1', now + 29_999).digits)
        assertEquals("1", s.press('1', now + 30_000).digits) // over (the screen also clears the field when its timer ends)
        assertEquals(0L, s.cooldownOver().cooldownUntilMs)
    }

    @Test fun countdownRoundsUpAndFormats() {
        assertEquals(30, cooldownSecondsLeft(now + 30_000, now))
        assertEquals(30, cooldownSecondsLeft(now + 29_001, now))
        assertEquals(1, cooldownSecondsLeft(now + 1, now))
        assertEquals(0, cooldownSecondsLeft(now, now))
        assertEquals(0, cooldownSecondsLeft(now - 5, now))
        assertEquals("0:27", formatCountdown(27))
        assertEquals("0:05", formatCountdown(5))
        assertEquals("1:05", formatCountdown(65))
    }

    @Test fun wrongPinMessagesFollowTheTriesLeft() {
        assertEquals("Wrong PIN. Try again.", wrongPinMessage(4))
        assertEquals("Wrong PIN. Try again.", wrongPinMessage(3))
        assertTrue(wrongPinMessage(2).startsWith("Wrong PIN. 2 more tries"))
        assertTrue(wrongPinMessage(1).startsWith("Wrong PIN. 1 more try,"))
    }

    @Test fun remoteNumberKeysMapToDigits() {
        assertEquals('0', digitForKeyCode(7)); assertEquals('9', digitForKeyCode(16))
        assertEquals('0', digitForKeyCode(144)); assertEquals('9', digitForKeyCode(153))
        assertNull(digitForKeyCode(17)) // KEYCODE_STAR
        assertNull(digitForKeyCode(KEYCODE_DEL_CODE))
        assertNull(digitForKeyCode(66)) // ENTER
    }

    // --- step machine ---
    private fun task(p: PinPurpose) = PinTask(p, "P1")

    @Test fun startSteps() {
        assertEquals(PinStep.UNLOCK, PinPurpose.SWITCH.startStep())
        for (p in listOf(PinPurpose.REMOVE_PROFILE, PinPurpose.CHANGE, PinPurpose.REMOVE_PIN)) assertEquals(PinStep.VERIFY, p.startStep())
        for (p in listOf(PinPurpose.SET, PinPurpose.ADD_PROFILE)) assertEquals(PinStep.CHOOSE, p.startStep())
    }

    @Test fun unlockOpensTheProfile() {
        assertEquals(PinStepResult.Done(null, PinNav.OPEN_PROFILE, null), task(PinPurpose.SWITCH).onVerified())
    }

    @Test fun removeProfileAfterThePinOrAfterTheForgotPassword() {
        val t = task(PinPurpose.REMOVE_PROFILE)
        assertEquals(PinStepResult.Done(null, PinNav.REMOVE_PROFILE, null), t.onVerified())
        assertEquals(PinStepResult.Done(null, PinNav.REMOVE_PROFILE, null), t.onPasswordAccepted())
    }

    @Test fun removePinClearsAndGoesBack() {
        assertEquals(PinStepResult.Done(PinSave.Clear, PinNav.BACK, "PIN removed"), task(PinPurpose.REMOVE_PIN).onVerified())
    }

    @Test fun setPinChoosesConfirmsThenSaves() {
        val t = task(PinPurpose.SET)
        assertEquals(PinStep.CHOOSE, t.step)
        val c = t.onChosen("4821").task
        assertEquals(PinStep.CONFIRM, c.step); assertEquals("4821", c.first)
        assertEquals(PinStepResult.Done(PinSave.Set("4821"), PinNav.BACK, "PIN set"), c.onConfirmed("4821"))
    }

    @Test fun changePinVerifiesThenChoosesAndSaysChanged() {
        val v = task(PinPurpose.CHANGE)
        val choose = (v.onVerified() as PinStepResult.Next).task
        assertEquals(PinStep.CHOOSE, choose.step)
        assertEquals("Choose a new PIN", choose.instruction("Evan"))
        val done = choose.onChosen("1111").task.onConfirmed("1111") as PinStepResult.Done
        assertEquals("PIN changed", done.text); assertEquals(PinNav.BACK, done.nav)
    }

    @Test fun addProfileSetsThenOpensTheProfile() {
        val done = task(PinPurpose.ADD_PROFILE).onChosen("2468").task.onConfirmed("2468") as PinStepResult.Done
        assertEquals(PinNav.OPEN_PROFILE, done.nav); assertEquals(PinSave.Set("2468"), done.save); assertEquals("PIN set", done.text)
    }

    @Test fun mismatchGoesBackToChooseWithTheNoticeAndForgetsTheFirstPin() {
        val c = task(PinPurpose.SET).onChosen("1234").task
        val r = c.onConfirmed("1235") as PinStepResult.Next
        assertEquals(PinStep.CHOOSE, r.task.step); assertNull(r.task.first); assertEquals(PIN_MISMATCH_MESSAGE, r.task.notice)
        // a clean second attempt works and drops the notice
        val again = r.task.onChosen("1235").task
        assertNull(again.notice)
        assertTrue(again.onConfirmed("1235") is PinStepResult.Done)
    }

    @Test fun forgotPinThenChooseKeepsOldPinUntilConfirmedAndBackReturnsToUnlock() {
        val unlock = task(PinPurpose.SWITCH)
        assertEquals(PinGhost.FORGOT, unlock.ghost())
        val choose = (unlock.onPasswordAccepted() as PinStepResult.Next).task
        assertTrue(choose.afterForgot)
        assertEquals(PinGhost.REMOVE_INSTEAD, choose.ghost())
        assertEquals("Choose a new PIN", choose.instruction("Evan"))
        // Back keeps the old PIN and returns to UNLOCK
        assertEquals(PinTask(PinPurpose.SWITCH, "P1", PinStep.UNLOCK), choose.onBack())
        // a new PIN after Forgot says "PIN changed" and goes on to open the profile
        val done = choose.onChosen("9090").task.onConfirmed("9090") as PinStepResult.Done
        assertEquals(PinNav.OPEN_PROFILE, done.nav); assertEquals("PIN changed", done.text)
        // "Remove PIN instead" clears and goes on too
        assertEquals(PinStepResult.Done(PinSave.Clear, PinNav.OPEN_PROFILE, "PIN removed"), choose.onRemoveInstead())
    }

    @Test fun settingsForgotPinReturnsToSettings() {
        val choose = (task(PinPurpose.CHANGE).onPasswordAccepted() as PinStepResult.Next).task
        assertEquals(PinNav.BACK, choose.onRemoveInstead().nav)
    }

    @Test fun backWalksOneStepAtATime() {
        val confirm = task(PinPurpose.SET).onChosen("1234").task
        val choose = confirm.onBack()!!
        assertEquals(PinStep.CHOOSE, choose.step); assertNull(choose.first)
        assertNull(choose.onBack()) // CHOOSE: leave the PIN screens
        assertNull(task(PinPurpose.SWITCH).onBack()) // UNLOCK: leave
        assertNull(task(PinPurpose.CHANGE).onBack())
    }

    @Test fun ghostButtonsAndInstructions() {
        assertEquals(PinGhost.FORGOT, task(PinPurpose.CHANGE).ghost())
        assertNull(task(PinPurpose.SET).ghost())
        assertNull(task(PinPurpose.SET).onChosen("1234").task.ghost())
        assertEquals("Enter your PIN", task(PinPurpose.SWITCH).instruction("Evan"))
        assertEquals("Enter your current PIN", task(PinPurpose.CHANGE).instruction("Evan"))
        assertEquals("Enter the PIN to remove Evan", task(PinPurpose.REMOVE_PROFILE).instruction("Evan"))
        assertEquals("Choose a 4-digit PIN", task(PinPurpose.SET).instruction("Evan"))
        assertEquals("Enter it again to confirm", task(PinPurpose.SET).onChosen("1234").task.instruction("Evan"))
    }

    // --- login pieces ---
    @Test fun rejectedPasswordGetsTheDesignedCopyOnlyOnPasswordOnlyScreens() {
        val bad = AbsHttpException(401)
        assertEquals("That password didn’t work. Check it and try again.", loginErrorMessage(LoginMode.REAUTH, bad))
        assertEquals("That password didn’t work. Check it and try again.", loginErrorMessage(LoginMode.FORGOT_PIN, bad))
        assertEquals("Server returned HTTP 401", loginErrorMessage(LoginMode.FIRST, bad))
        assertEquals("Server returned HTTP 500", loginErrorMessage(LoginMode.REAUTH, AbsHttpException(500)))
    }
}
