package com.wirelesspet.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.wirelesspet.core.MonoClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 电源状态监听（设计文档 §5）。
 *
 * - 监听电池、插拔电源与热状态回调，不做周期轮询；
 * - 启动时读取粘性广播拿到当前状态，因此恢复、首帧判断都不需要等待下一次广播；
 * - 不持有唤醒锁，不注册精确闹钟；
 * - 是否"曾检测到无线电源"由上层记录，本类不判断能力。
 */
class PowerSourceMonitor(
    private val context: Context,
    private val clock: MonoClock,
    private val report: (String) -> Unit = {},
) {
    private val _snapshot = MutableStateFlow<PowerSnapshot?>(null)
    val snapshot: StateFlow<PowerSnapshot?> = _snapshot.asStateFlow()

    private val powerManager: PowerManager? =
        context.getSystemService(PowerManager::class.java)

    private val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    private val eventFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) publish(intent)
            else {
                report("电源插拔事件：${intent.action}")
                refresh()
            }
        }
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        val current = _snapshot.value ?: return@OnThermalStatusChangedListener
        _snapshot.value = current.copy(
            thermalLevel = thermalLevelOf(status),
            observedAtElapsedMs = clock.elapsedRealtimeMs(),
        )
    }

    private var started = false

    /** 读取当前电源状态；未启动或读取失败返回 null。 */
    fun current(): PowerSnapshot? = _snapshot.value ?: readSticky()

    fun start() {
        if (started) return
        started = true
        runCatching {
            ContextCompat.registerReceiver(
                context,
                batteryReceiver,
                eventFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { report("电源广播注册失败：${it.javaClass.simpleName}") }
        readSticky()?.let { _snapshot.value = it }
        runCatching {
            powerManager?.addThermalStatusListener(
                ContextCompat.getMainExecutor(context),
                thermalListener,
            )
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching { powerManager?.removeThermalStatusListener(thermalListener) }
    }

    /** 一次性读取粘性广播，用于进程恢复时确认"当前真实电源状态"。 */
    fun refresh(): PowerSnapshot? = readSticky()?.also { _snapshot.value = it }

    fun readSticky(): PowerSnapshot? = runCatching {
        val intent = context.registerReceiver(null, batteryFilter) ?: return@runCatching null
        BatteryIntents.fromIntent(
            intent = intent,
            thermalLevel = thermalLevelOf(powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE),
            observedAtElapsedMs = clock.elapsedRealtimeMs(),
        )
    }.getOrNull()

    val thermalStatus: ThermalLevel
        get() = thermalLevelOf(powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE)

    private fun publish(intent: Intent) {
        _snapshot.value = BatteryIntents.fromIntent(
            intent = intent,
            thermalLevel = thermalStatus,
            observedAtElapsedMs = clock.elapsedRealtimeMs(),
        )
    }

    companion object {
        fun thermalLevelOf(status: Int): ThermalLevel = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalLevel.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN,
            -> ThermalLevel.CRITICAL

            else -> ThermalLevel.NONE
        }
    }
}
