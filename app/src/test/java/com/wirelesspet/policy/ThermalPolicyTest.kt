package com.wirelesspet.policy

import com.wirelesspet.power.ThermalLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 温度分级基线（40.5 / 43.0 / 46.0 / 49.0℃）来自小米 14 实测：
 * 无线充电稳定期 40～44℃，边充电边调试可到 46～48℃。
 * 早期用 45.5℃ 当极高温，等于手机上永远不展示，故按实测重新校准。
 */
class ThermalPolicyTest {

    @Test
    fun `温度分级映射`() {
        val policy = ThermalPolicy()
        assertEquals(ThermalLevel.NONE, policy.evaluate(30f, ThermalLevel.NONE, 0))
        policy.reset()
        assertEquals(ThermalLevel.LIGHT, policy.evaluate(41f, ThermalLevel.NONE, 0))
        policy.reset()
        assertEquals(ThermalLevel.MODERATE, policy.evaluate(43.5f, ThermalLevel.NONE, 0))
        policy.reset()
        assertEquals(ThermalLevel.SEVERE, policy.evaluate(46.5f, ThermalLevel.NONE, 0))
        policy.reset()
        assertEquals(ThermalLevel.CRITICAL, policy.evaluate(50f, ThermalLevel.NONE, 0))
    }

    @Test
    fun `系统热状态可以与温度叠加`() {
        val policy = ThermalPolicy()
        assertEquals(ThermalLevel.SEVERE, policy.evaluate(30f, ThermalLevel.SEVERE, 0))
        policy.reset()
        assertEquals(
            "系统报严重时，即使电池温度较低也要按严重处理",
            ThermalLevel.CRITICAL,
            policy.evaluate(45f, ThermalLevel.CRITICAL, 0),
        )
    }

    @Test
    fun `降温需要冷却时间`() {
        val policy = ThermalPolicy(coolDownMs = 60_000L)
        assertEquals(ThermalLevel.MODERATE, policy.evaluate(44.0f, ThermalLevel.NONE, 0))
        assertEquals("冷却时间内保持降载", ThermalLevel.MODERATE, policy.evaluate(30f, ThermalLevel.NONE, 30_000))
        assertEquals("冷却结束后恢复", ThermalLevel.NONE, policy.evaluate(30f, ThermalLevel.NONE, 61_000))
    }

}
