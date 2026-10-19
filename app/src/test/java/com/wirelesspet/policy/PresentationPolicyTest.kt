package com.wirelesspet.policy

import com.wirelesspet.data.AutomationMode
import com.wirelesspet.data.UserSettings
import com.wirelesspet.power.*
import com.wirelesspet.session.*
import org.junit.Assert.*
import org.junit.Test

class PresentationPolicyTest {
    private val settings = UserSettings.DEFAULT.copy(automationEnabled = true, allowInterruptOtherApps = true, wakeScreenWhenOff = false, showOnLockScreen = false)
    private val request = PresentationRequest(
        source = PresentationSource.CHARGING_SESSION,
        settings = settings,
        power = PowerSnapshot(type = PowerType.WIRELESS, pluggedBitmask = 4),
        session = WirelessSessionState(phase = SessionPhase.ACTIVE, sessionId = 42L),
        screenOn = true, keyguardLocked = false, thermal = ThermalLevel.NONE, localMinuteOfDay = 720,
    )

    @Test fun `automatic presentation respects prerequisites and blocking priority`() {
        assertTrue(PresentationPolicy.decide(request) is PresentationDecision.Present)
        assertTrue(PresentationPolicy.decide(request.copy(
            power = PowerSnapshot(type = PowerType.USB), settings = settings.copy(wiredChargingEnabled = true),
        )) is PresentationDecision.Present)
        val dismissed = request.session.copy(suppression = SuppressionReason.USER_DISMISSED)
        val cases = listOf(
            request.copy(settings = settings.copy(automationEnabled = false)) to SuppressionReason.AUTOMATION_OFF,
            request.copy(power = null) to SuppressionReason.UNKNOWN_POWER,
            request.copy(power = PowerSnapshot(type = PowerType.AC)) to SuppressionReason.NOT_WIRELESS,
            request.copy(session = WirelessSessionState()) to SuppressionReason.SESSION_INACTIVE,
            request.copy(keyguardLocked = true, screenOn = false) to SuppressionReason.LOCKED,
            request.copy(screenOn = false) to SuppressionReason.SCREEN_OFF,
            request.copy(session = dismissed, screenOn = false) to SuppressionReason.USER_DISMISSED,
            request.copy(session = dismissed, thermal = ThermalLevel.CRITICAL) to SuppressionReason.THERMAL,
        )
        for ((input, reason) in cases) {
            assertEquals(reason.name, reason, (PresentationPolicy.decide(input) as PresentationDecision.Skip).reason)
        }
    }

    @Test fun `manual resume bypasses interruption preference but retains safety checks`() {
        val automatic = request.copy(settings = settings.copy(allowInterruptOtherApps = false))
        assertTrue(PresentationPolicy.decide(automatic) is PresentationDecision.Notify)
        val manual = automatic.copy(userInitiated = true)
        assertTrue(PresentationPolicy.decide(manual) is PresentationDecision.Present)
        for (input in listOf(
            manual.copy(power = PowerSnapshot(type = PowerType.AC)),
            manual.copy(session = WirelessSessionState()),
            manual.copy(thermal = ThermalLevel.CRITICAL),
        )) assertTrue(PresentationPolicy.decide(input) is PresentationDecision.Skip)
    }

    @Test fun `preview and dream follow their own entry rules`() {
        assertTrue(PresentationPolicy.decide(request.copy(
            source = PresentationSource.PREVIEW, settings = UserSettings.DEFAULT,
            session = WirelessSessionState(), thermal = ThermalLevel.CRITICAL,
        )) is PresentationDecision.Present)
        val dream = request.copy(source = PresentationSource.DREAM, screenOn = false)
        assertTrue(PresentationPolicy.decide(dream) is PresentationDecision.Skip)
        assertTrue(PresentationPolicy.decide(dream.copy(
            settings = settings.copy(automationMode = AutomationMode.DREAM),
        )) is PresentationDecision.Present)
    }

    @Test fun `quiet hours block presentation and preserve midnight boundaries`() {
        val quiet = request.copy(localMinuteOfDay = 120, settings = settings.copy(
            quietHoursEnabled = true, quietStartMinuteOfDay = 1380, quietEndMinuteOfDay = 420,
        ))
        assertEquals(SuppressionReason.TIME_WINDOW, (PresentationPolicy.decide(quiet) as PresentationDecision.Skip).reason)
        assertTrue(PresentationPolicy.isWithinQuietHours(1410, 1380, 420))
        assertTrue(PresentationPolicy.isWithinQuietHours(419, 1380, 420))
        assertFalse(PresentationPolicy.isWithinQuietHours(420, 1380, 420))
        assertFalse(PresentationPolicy.isWithinQuietHours(600, 600, 600))
    }
}
