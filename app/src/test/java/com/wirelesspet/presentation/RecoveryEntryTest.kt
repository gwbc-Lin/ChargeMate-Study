package com.wirelesspet.presentation

import com.wirelesspet.data.AutomationMode
import com.wirelesspet.data.UserSettings
import com.wirelesspet.policy.PresentationDecision
import com.wirelesspet.policy.PresentationPolicy
import com.wirelesspet.policy.PresentationRequest
import com.wirelesspet.policy.PresentationSource
import com.wirelesspet.power.PowerSnapshot
import com.wirelesspet.power.PowerType
import com.wirelesspet.power.ThermalLevel
import com.wirelesspet.session.*
import org.junit.Assert.*
import org.junit.Test

class RecoveryEntryTest {
    private val power = PowerSnapshot(type = PowerType.WIRELESS, pluggedBitmask = 4)
    private fun persisted(reason: String) = PersistedSession(
        42L, SessionPhase.ACTIVE.name, reason, PowerType.WIRELESS.name, 7, 0,
    )

    private fun restore(reason: String): ChargingSessionController = ChargingSessionController().also {
        it.restore(persisted(reason), power, 1_000L, 7)
    }

    private fun decision(state: WirelessSessionState, settings: UserSettings = UserSettings.DEFAULT.copy(
        automationEnabled = true,
    ), source: PresentationSource = PresentationSource.CHARGING_SESSION) = PresentationPolicy.decide(
        PresentationRequest(source, settings, power, state, true, false, ThermalLevel.NONE, 12 * 60,
            userInitiated = true),
    )

    @Test fun `所有会话级退出原因在进程恢复后保留`() {
        SuppressionReason.entries.filter { it.bindsToSession && it != SuppressionReason.MAX_DURATION }.forEach { reason ->
            val state = restore(reason.name).state.value
            assertEquals(reason, state.suppression)
            assertFalse(state.allowsPresentation)
        }
    }

    @Test fun `冷进程点击有效通知先恢复再由明确操作解除抑制`() {
        val controller = restore(SuppressionReason.USER_DISMISSED.name)
        val candidate = SessionEntry.forPresentation(controller.state.value, 42L, true)!!
        assertTrue(decision(candidate) is PresentationDecision.Present)
        // 策略输入的临时解除不修改真实会话，检查通过后才提交。
        assertEquals(SuppressionReason.USER_DISMISSED, controller.state.value.suppression)
        controller.onResumeRequested(1_001L)
        assertTrue(controller.state.value.allowsPresentation)
    }

    @Test fun `过期通知不能解除新会话的退出抑制`() {
        val controller = restore(SuppressionReason.USER_DISMISSED.name)
        assertNull(SessionEntry.forPresentation(controller.state.value, 41L, true))
        assertNull(SessionEntry.forPresentation(controller.state.value, null, true))
        assertEquals(SuppressionReason.USER_DISMISSED, controller.state.value.suppression)
    }

}
