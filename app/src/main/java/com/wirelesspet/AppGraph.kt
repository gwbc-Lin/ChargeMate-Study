package com.wirelesspet

import android.content.Context
import com.wirelesspet.automation.AutomationNotifications
import com.wirelesspet.core.MonoClock
import com.wirelesspet.core.SystemMonoClockHolder
import com.wirelesspet.core.SystemWallClockHolder
import com.wirelesspet.data.EventLog
import com.wirelesspet.data.SessionSnapshotStore
import com.wirelesspet.data.SettingsRepository
import com.wirelesspet.data.RuntimeFactsStore
import com.wirelesspet.data.UserSettings
import com.wirelesspet.diagnostics.DiagnosticsExporter
import com.wirelesspet.platform.CapabilityDetector
import com.wirelesspet.platform.DeviceInfoProvider
import com.wirelesspet.platform.OemAdapter
import com.wirelesspet.policy.ThermalPolicy
import com.wirelesspet.power.PowerSourceMonitor
import com.wirelesspet.presentation.PresentationCoordinator
import com.wirelesspet.power.ThermalLevel
import com.wirelesspet.session.ChargingSessionController
import com.wirelesspet.session.PersistedSession
import com.wirelesspet.session.SessionTimings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalTime

/**
 * 应用级依赖容器（首版单模块，手工装配，避免引入 DI 框架）。
 *
 * 关键约束来自设计文档 §14：业务状态通过明确事件流转，UI、服务、屏保共用同一份状态，
 * 不允许各组件各自维护"是否正在充电"。
 */
class AppGraph(private val context: Context) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val clock: MonoClock = SystemMonoClockHolder.clock

    val eventLog = EventLog(
        directory = File(context.filesDir, "diagnostics"),
        wallClock = SystemWallClockHolder.clock,
    )

    val characterStore = com.wirelesspet.pet.CharacterStore(context)

    val settingsRepository = SettingsRepository.create(context)
    val sessionStore = SessionSnapshotStore.create(context)
    val runtimeFacts = RuntimeFactsStore.create(context)

    val settings: StateFlow<UserSettings> = settingsRepository.settings.stateIn(
        scope = appScope,
        started = SharingStarted.Eagerly,
        initialValue = UserSettings.DEFAULT,
    )

    val powerMonitor = PowerSourceMonitor(context, clock) { message ->
        eventLog.log(EventLog.Category.POWER, message)
    }
    val thermalPolicy = ThermalPolicy()
    val notifications = AutomationNotifications(context)

    /** 上一次自动展示被系统拦截的时间（墙上时间）；非空时首页会给授权引导。 */
    val lastLaunchBlockedAt = MutableStateFlow<Long?>(null)

    val coordinator = PresentationCoordinator(
        context = context,
        notifications = notifications,
        eventLog = eventLog,
        clock = clock,
        scope = appScope,
        onLaunchBlocked = {
            val at = SystemWallClockHolder.clock.currentTimeMillis()
            lastLaunchBlockedAt.value = at
            appScope.launch { runCatching { runtimeFacts.markLaunchBlocked(at) } }
        },
    )
    val capabilityDetector = CapabilityDetector(context)
    val oemAdapter = OemAdapter(context)
    val diagnosticsExporter = DiagnosticsExporter(context, eventLog, capabilityDetector)

    /**
     * 全应用唯一的温度等级（带回差）。
     *
     * 由这里统一推进，服务、页面、屏保只订阅不各自计算：
     * 之前每个组件各自 `evaluate()`，同一个温控器被多方喂数据，
     * 页面在展示中也拿不到等级变化，等于没做动态降载。
     */
    private val _thermal = MutableStateFlow(ThermalLevel.NONE)
    val thermal: StateFlow<ThermalLevel> = _thermal.asStateFlow()

    /** 监听服务是否在运行。由 AutomationService 维护，供首页与通知使用。 */
    val automationRunning = MutableStateFlow(false)

    /** 是否曾经识别到无线电源；普通应用无法直接查询硬件能力。 */
    val wirelessEverSeen = MutableStateFlow(false)

    /** 配置是否已经从 DataStore 读取完成，避免首页在首帧误显示为"未设置"。 */
    val settingsLoaded = MutableStateFlow(false)

    private val bootCountValue: Int = DeviceInfoProvider.bootCount(context)

    val sessionController = ChargingSessionController(
        timings = SessionTimings(),
        onTransition = { state, commands ->
            eventLog.log(
                EventLog.Category.SESSION,
                "状态 ${state.phase.name} session=${state.sessionId} 抑制=${state.suppression.name} " +
                    "电源=${state.lastPower?.type?.name ?: "UNKNOWN"} 命令=${commands.size}",
            )
            appScope.launch { persistSession(state) }
        },
    )

    private var monitorClients = 0

    init {
        notifications.ensureChannels()
        eventLog.log(
            EventLog.Category.SERVICE,
            "应用进程启动：${DeviceInfoProvider.romLabel()} Android ${DeviceInfoProvider.androidRelease} " +
                "启动周期=$bootCountValue",
        )
        appScope.launch {
            wirelessEverSeen.value = runCatching { runtimeFacts.wirelessEverSeen() }.getOrDefault(false)
            lastLaunchBlockedAt.value = runCatching { runtimeFacts.lastLaunchBlockedAt() }.getOrNull()
        }
        appScope.launch {
            settingsRepository.settings.collect {
                settingsLoaded.value = true
            }
        }
        // 温度等级：只在这一处推进，其他组件订阅 thermal。
        appScope.launch {
            powerMonitor.snapshot.collect { snapshot ->
                if (snapshot == null) return@collect
                val level = thermalPolicy.evaluate(
                    temperatureCelsius = snapshot.temperatureCelsius,
                    systemLevel = snapshot.thermalLevel,
                    nowElapsedMs = now(),
                )
                if (level != _thermal.value) {
                    _thermal.value = level
                    eventLog.log(
                        EventLog.Category.POLICY,
                        "温度等级变化：$level（电池 ${String.format("%.1f", snapshot.temperatureCelsius)}℃ / " +
                            "系统 ${snapshot.thermalLevel}）",
                    )
                }
            }
        }
    }

    fun now(): Long = clock.elapsedRealtimeMs()

    fun bootCount(): Int = bootCountValue

    /** 宿主冷启动时先刷新一次，避免用默认或停止监听前的热状态进入展示。 */
    fun refreshThermal() {
        val power = powerMonitor.readSticky() ?: return
        _thermal.value = thermalPolicy.evaluate(
            power.temperatureCelsius, power.thermalLevel, now(),
        )
    }

    fun localMinuteOfDay(): Int = LocalTime.now().let { it.hour * 60 + it.minute }

    /** 用户处理完后台启动授权后，清掉首页的拦截提示。 */
    fun acknowledgeLaunchBlocked() {
        lastLaunchBlockedAt.value = null
        appScope.launch { runCatching { runtimeFacts.clearLaunchBlocked() } }
    }

    fun acquirePowerMonitor() {
        synchronized(this) {
            monitorClients += 1
            if (monitorClients == 1) powerMonitor.start()
        }
    }

    fun releasePowerMonitor() {
        synchronized(this) {
            if (monitorClients > 0) monitorClients -= 1
            if (monitorClients == 0) powerMonitor.stop()
        }
    }

    /** 首页/设置页修改配置的统一入口，避免每次都拼一套 repository 调用。 */
    fun updateSettings(transform: (UserSettings) -> UserSettings) {
        appScope.launch { settingsRepository.update(transform) }
    }

    fun shutdown() {
        releasePowerMonitor()
        powerMonitor.stop()
        eventLog.shutdown()
    }

    private suspend fun persistSession(state: com.wirelesspet.session.WirelessSessionState) {
        runCatching {
            if (state.sessionId != null) {
                sessionStore.save(
                    PersistedSession(
                        sessionId = state.sessionId,
                        phase = state.phase.name,
                        suppression = state.suppression.name,
                        powerType = state.lastPower?.type?.name ?: "UNKNOWN",
                        bootCount = bootCountValue,
                        savedAtWallMs = SystemWallClockHolder.clock.currentTimeMillis(),
                    ),
                )
            } else {
                sessionStore.clear()
            }
        }
    }
}
