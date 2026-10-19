package com.wirelesspet.automation

import com.wirelesspet.AppGraph
import com.wirelesspet.data.EventLog
import com.wirelesspet.power.PowerSnapshot
import com.wirelesspet.session.SessionCommand
import com.wirelesspet.session.SessionPhase
import com.wirelesspet.session.SessionUpdate
import com.wirelesspet.session.WirelessSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 会话驱动（设计文档 §5 "业务状态通过明确事件流转"）。
 *
 * 电源事件 → 会话状态机 → 防抖是否到期，这套推进逻辑原本只存在于立即模式的
 * 前台服务里，导致屏保模式切过去之后没有任何人建立会话。现在抽成独立组件，
 * 立即模式与屏保模式共用同一份会话推进逻辑，区别只在"收到开始展示指令后做什么"。
 *
 * 本类只负责推进状态与派发指令，不启动页面、不改设置。
 */
class SessionDriver(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val onUpdate: (SessionUpdate) -> Unit,
) {
    private var powerJob: Job? = null
    private var sourceSettingsJob: Job? = null
    private var deadlineJob: Job? = null
    private var running = false

    val state: WirelessSessionState get() = graph.sessionController.state.value

    /** 幂等启动：先恢复历史会话，再订阅电源事件。 */
    suspend fun start() {
        if (running) return
        running = true
        graph.acquirePowerMonitor()
        graph.sessionController.wiredChargingEnabled = graph.settingsRepository.settings.first().wiredChargingEnabled
        restoreSession()
        sourceSettingsJob = scope.launch {
            graph.settingsRepository.settings.map { it.wiredChargingEnabled }.distinctUntilChanged().collect { allowed ->
                graph.sessionController.wiredChargingEnabled = allowed
                feedCurrentPower()
            }
        }
        powerJob = scope.launch {
            graph.powerMonitor.snapshot.collect { snapshot ->
                if (snapshot != null) feed(snapshot)
            }
        }
    }

    fun stop() {
        sourceSettingsJob?.cancel(); sourceSettingsJob = null
        powerJob?.cancel()
        powerJob = null
        deadlineJob?.cancel()
        deadlineJob = null
        if (running) {
            running = false
            graph.releasePowerMonitor()
        }
    }

    /** 手动喂一次当前电源状态（例如宿主刚绑定、还没有广播时）。 */
    fun feedCurrentPower() {
        graph.powerMonitor.current()?.let { feed(it) }
    }

    private fun feed(snapshot: PowerSnapshot) {
        if (snapshot.isWireless && !graph.wirelessEverSeen.value) {
            graph.wirelessEverSeen.value = true
            scope.launch { graph.runtimeFacts.markWirelessObserved() }
        }
        graph.eventLog.log(
            EventLog.Category.POWER,
            "电源 ${snapshot.type.label} ${snapshot.levelPercent}% ${snapshot.status.label} " +
                "温度=${if (snapshot.temperatureCelsius.isNaN()) "?" else String.format("%.1f", snapshot.temperatureCelsius)}",
        )
        deliver(graph.sessionController.onPowerChanged(snapshot, graph.now()), "电源 ${snapshot.type.label}")
    }

    private fun deliver(update: SessionUpdate, reason: String) {
        update.commands.forEach { command ->
            when (command) {
                is SessionCommand.StartPresentation -> graph.eventLog.log(
                    EventLog.Category.SESSION,
                    "无线会话建立 session=${command.sessionId}（$reason）",
                )

                is SessionCommand.StopPresentation -> graph.eventLog.log(
                    EventLog.Category.SESSION,
                    "会话指令：停止展示 session=${command.sessionId} 原因=${command.reason.label}（$reason）",
                )
            }
        }
        onUpdate(update)
        if (running) scheduleDeadline(update.state)
    }

    private fun scheduleDeadline(state: WirelessSessionState) {
        deadlineJob?.cancel()
        val deadline = state.deadlineElapsedMs ?: return
        val waitMs = (deadline - graph.now()).coerceAtLeast(0L)
        deadlineJob = scope.launch {
            delay(waitMs)
            val phaseBefore = state.phase
            val due = graph.sessionController.onDeadline(graph.now())
            deliver(due, "防抖窗口到期")
            if (due.state.phase == SessionPhase.IDLE && phaseBefore == SessionPhase.CONFIRMING_DISCONNECT) {
                graph.eventLog.log(EventLog.Category.SESSION, "无线电源已稳定断开，会话结束")
            }
        }
    }

    private suspend fun restoreSession() {
        val persisted = runCatching { graph.sessionStore.load() }.getOrNull() ?: return
        val restored = graph.sessionController.restore(
            persisted = persisted,
            power = graph.powerMonitor.current(),
            nowElapsedMs = graph.now(),
            currentBootCount = graph.bootCount(),
        )
        if (!restored.state.isActiveSession) {
            graph.eventLog.log(
                EventLog.Category.RECOVERY,
                "历史会话未恢复：启动周期、电源状态或阶段不匹配",
            )
            return
        }
        graph.eventLog.log(
            EventLog.Category.RECOVERY,
            "恢复历史会话 session=${restored.state.sessionId} 抑制=${restored.state.suppression}，等待手动继续",
        )
        // 首页可见只代表恢复监听，不代表用户要求恢复宠物展示。
    }
}
