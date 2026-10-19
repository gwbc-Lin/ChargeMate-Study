package com.wirelesspet.policy

import com.wirelesspet.power.ThermalLevel

/**
 * 温度分级（设计文档 §9.2）。
 *
 * 只调整应用自身负载，不控制无线充电功率。阈值取电池温度 + 系统热状态中的较高者；
 * 降级（降温恢复）需要冷却时间，避免在阈值附近来回切换。
 *
 * 阈值来自小米 14 真机实测：无线充电稳定期电池温度约 40～44℃，
 * 边充电边做 adb/亮屏测试时可以到 46～48℃。早期把 45.5℃ 当"极高温"，
 * 结果手机上几乎永远不展示（预览也会被瞬间关掉），因此按实测重新校准。
 * 这仍然只是第一版基线，不是硬件安全结论；电池温度也不代表芯片与机身表面温度。
 */
class ThermalPolicy(
    private val lightCelsius: Float = 40.5f,
    private val moderateCelsius: Float = 43.0f,
    private val severeCelsius: Float = 46.0f,
    private val criticalCelsius: Float = 49.0f,
    private val coolDownMs: Long = 90_000L,
) {
    private var level: ThermalLevel = ThermalLevel.NONE
    private var lastChangeAtElapsedMs: Long = Long.MIN_VALUE

    fun current(): ThermalLevel = level

    fun evaluate(
        temperatureCelsius: Float,
        systemLevel: ThermalLevel,
        nowElapsedMs: Long,
    ): ThermalLevel {
        val temperatureLevel = levelForTemperature(temperatureCelsius)
        val target = if (temperatureLevel.ordinal >= systemLevel.ordinal) temperatureLevel else systemLevel

        if (target.ordinal > level.ordinal) {
            // 升温立即降载，安全优先。
            level = target
            lastChangeAtElapsedMs = nowElapsedMs
        } else if (target.ordinal < level.ordinal) {
            val cooledDown = lastChangeAtElapsedMs == Long.MIN_VALUE ||
                nowElapsedMs - lastChangeAtElapsedMs >= coolDownMs
            if (cooledDown) {
                level = target
                lastChangeAtElapsedMs = nowElapsedMs
            }
        }
        return level
    }

    fun reset() {
        level = ThermalLevel.NONE
        lastChangeAtElapsedMs = Long.MIN_VALUE
    }

    private fun levelForTemperature(celsius: Float): ThermalLevel = when {
        celsius.isNaN() -> ThermalLevel.NONE
        celsius >= criticalCelsius -> ThermalLevel.CRITICAL
        celsius >= severeCelsius -> ThermalLevel.SEVERE
        celsius >= moderateCelsius -> ThermalLevel.MODERATE
        celsius >= lightCelsius -> ThermalLevel.LIGHT
        else -> ThermalLevel.NONE
    }
}
