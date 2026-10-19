package com.wirelesspet.power

import android.content.Intent
import android.os.BatteryManager

/**
 * 把 ACTION_BATTERY_CHANGED 的 extras 转成标准化的 [PowerSnapshot]。
 *
 * 只读系统广播内容，不做任何轮询。EXTRAS 缺失时按"未知"处理，
 * 未知电源不允许触发自动展示（设计文档 §13）。
 */
object BatteryIntents {

    fun fromIntent(intent: Intent, thermalLevel: ThermalLevel, observedAtElapsedMs: Long): PowerSnapshot {
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val temperatureTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)

        val percent = when {
            level < 0 || scale <= 0 -> -1
            else -> (level * 100 + scale / 2) / scale
        }

        return PowerSnapshot(
            type = PowerSnapshot.pluggedType(plugged),
            pluggedBitmask = plugged,
            levelPercent = percent,
            status = statusOf(status),
            temperatureCelsius = if (temperatureTenths == Int.MIN_VALUE) Float.NaN else temperatureTenths / 10f,
            thermalLevel = thermalLevel,
            observedAtElapsedMs = observedAtElapsedMs,
        )
    }

    private fun statusOf(raw: Int): ChargingStatus = when (raw) {
        BatteryManager.BATTERY_STATUS_CHARGING -> ChargingStatus.CHARGING
        BatteryManager.BATTERY_STATUS_DISCHARGING -> ChargingStatus.DISCHARGING
        BatteryManager.BATTERY_STATUS_FULL -> ChargingStatus.FULL
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> ChargingStatus.NOT_CHARGING
        else -> ChargingStatus.UNKNOWN
    }
}
