package com.wirelesspet.policy

import com.wirelesspet.data.EnergyProfile
import com.wirelesspet.data.UserSettings
import com.wirelesspet.power.ThermalLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyPolicyTest {

    private val settings = UserSettings.DEFAULT

    @Test
    fun `严重升温只保留静态展示`() {
        val plan = EnergyPolicy.planFor(settings, ThermalLevel.SEVERE, PresentationSource.CHARGING_SESSION)
        assertTrue(plan.staticOnly)
        assertTrue(plan.idleFps == 0)
        assertTrue(!plan.keepScreenOn)
    }

    @Test
    fun `极高温下预览改为限时静态`() {
        val plan = EnergyPolicy.emergencyPreviewPlan(settings, ThermalLevel.CRITICAL)
        assertTrue(plan.staticOnly)
        assertEquals(60_000L, plan.maxDurationMs)
        assertTrue("极高温要压到低亮度", plan.brightness <= 0.15f)
        assertTrue(!plan.keepScreenOn)
    }

    @Test
    fun `所有正常档位和展示来源超过两分钟仍不超时`() {
        EnergyProfile.entries.forEach { profile ->
            PresentationSource.entries.forEach { source ->
                listOf(ThermalLevel.NONE, ThermalLevel.LIGHT, ThermalLevel.MODERATE, ThermalLevel.SEVERE).forEach { thermal ->
                    val plan = EnergyPolicy.planFor(settings.copy(energyProfile = profile), thermal, source)
                    assertEquals(0L, plan.maxDurationMs)
                    assertEquals(null, com.wirelesspet.presentation.PresentationLifetime(0).remainingMs(plan.maxDurationMs, 125_000))
                }
            }
        }
    }
    @Test
    fun `手动预览不继承省电档的低帧率`() {
        val plan = EnergyPolicy.planFor(settings, ThermalLevel.NONE, PresentationSource.PREVIEW)
        assertEquals(30, plan.activeFps)
        assertEquals(30, plan.idleFps)
        val warm = EnergyPolicy.planFor(settings, ThermalLevel.MODERATE, PresentationSource.PREVIEW)
        assertTrue(warm.activeFps < plan.activeFps)
    }
}
