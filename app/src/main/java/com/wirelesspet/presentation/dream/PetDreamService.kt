package com.wirelesspet.presentation.dream

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.service.dreams.DreamService
import com.wirelesspet.WirelessPetApplication
import com.wirelesspet.automation.SessionDriver
import com.wirelesspet.data.AutomationMode
import com.wirelesspet.data.EventLog
import com.wirelesspet.data.UserSettings
import com.wirelesspet.pet.PetRenderer
import com.wirelesspet.policy.EnergyPlan
import com.wirelesspet.policy.EnergyPolicy
import com.wirelesspet.policy.PresentationDecision
import com.wirelesspet.policy.PresentationPolicy
import com.wirelesspet.policy.PresentationRequest
import com.wirelesspet.policy.PresentationSource
import com.wirelesspet.power.ChargingStatus
import com.wirelesspet.power.ThermalLevel
import com.wirelesspet.presentation.useFullDisplay
import com.wirelesspet.presentation.HostKind
import com.wirelesspet.presentation.PresentationLifetime
import com.wirelesspet.session.SessionCommand
import com.wirelesspet.session.SessionPhase
import com.wirelesspet.session.SessionUpdate
import com.wirelesspet.session.SuppressionReason
import com.wirelesspet.session.WirelessSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 系统屏保宿主（设计文档 §4.1、§6.2）。
 *
 * 之前的实现有个致命缺口：屏保要求"共享会话已经允许展示"，但会话只能由立即模式的
 * 前台服务推进，切换到屏保模式后服务已停止，于是一启动就被自己拒绝。
 *
 * 现在屏保用与立即模式相同的 [SessionDriver] 自己推进会话，并订阅电源、会话、温度、
 * 关闭请求与设置变化；解绑时释放全部引用与计时任务。
 */
class PetDreamService : DreamService() {

    private val graph get() = (application as WirelessPetApplication).graph

    private lateinit var scope: CoroutineScope
    private var driver: SessionDriver? = null
    private var renderer: PetRenderer? = null
    private var settingsJob: Job? = null
    private var sessionJob: Job? = null
    private var closeJob: Job? = null
    private var thermalJob: Job? = null
    private var plan = EnergyPolicy.planFor(UserSettings.DEFAULT, ThermalLevel.NONE, PresentationSource.DREAM)
    private val ownerToken = UUID.randomUUID().toString()
    private var sessionId: Long? = null
    private var lifetime: PresentationLifetime? = null
    private var durationJob: Job? = null
    private var powerJob: Job? = null
    private var presenting = false
    private var presentationEnded = false
    private var finalized = false

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        finalized = false
        presentationEnded = false
        scope.launch { prepare() }
    }

    private suspend fun prepare() {
        // 冷启动时 DataStore 可能还没读完，等一次真实配置（有上限，避免无限等待）。
        val settings = withTimeoutOrNull(2_500L) {
            runCatching { graph.settingsRepository.settings.first() }.getOrNull()
        } ?: graph.settings.value

        if (com.wirelesspet.BuildConfig.SPINE_ENABLED && !settings.spineLicenseAccepted) {
            skip("请先打开应用确认 Spine 许可")
            return
        }
        if (!settings.automationEnabled) {
            skip("自动化已关闭")
            return
        }
        if (settings.automationMode != AutomationMode.DREAM) {
            skip("未选择闲置屏保模式")
            return
        }

        // 与立即模式共用同一套会话推进逻辑：屏保也需要一个真实的无线充电会话。
        val sessionDriver = SessionDriver(graph, scope) { update -> onSessionUpdate(update) }
        driver = sessionDriver
        sessionDriver.start()
        sessionDriver.feedCurrentPower()

        val established = withTimeoutOrNull(2_000L) {
            graph.sessionController.state.first { it.isActiveSession }
        } != null
        if (!established) {
            skip("未能建立无线充电会话（当前电源不是无线，或连接仍在抖动）")
            return
        }

        graph.refreshThermal()
        val decision = PresentationPolicy.decide(requestFor(settings))
        if (decision !is PresentationDecision.Present) {
            val reason = when (decision) {
                is PresentationDecision.Skip -> decision.message
                is PresentationDecision.Notify -> decision.message
                is PresentationDecision.Present -> ""
            }
            skip(reason)
            return
        }

        sessionId = graph.sessionController.state.value.sessionId
        if (!graph.coordinator.registerDreamHost(sessionId, ownerToken)) {
            skip("已有其它展示宿主，屏保让位")
            return
        }

        isInteractive = false
        isFullscreen = true
        window?.useFullDisplay()
        window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        val view = PetRenderer(this).also { pet ->
            // 屏保不提供关闭入口：交互只让宠物回应，避免误退出系统屏保状态。
            pet.onPetTapped = { }
        }
        setContentView(view)
        renderer = view
        plan = EnergyPolicy.planFor(settings, graph.thermal.value, PresentationSource.DREAM)
        lifetime = PresentationLifetime(graph.now())
        applyPlan(plan)
        view.start(plan)
        view.updateBatteryLabel(
            if (settings.showBatteryInfo) graph.powerMonitor.current() else null,
        )
        presenting = true
        scheduleDuration()
        graph.eventLog.log(
            EventLog.Category.PRESENTATION,
            "屏保已接管展示：session=${graph.sessionController.state.value.sessionId} 档位=${plan.label}",
        )
        subscribe()
    }

    private fun requestFor(settings: UserSettings) = PresentationRequest(
        source = PresentationSource.DREAM,
        settings = settings,
        power = graph.powerMonitor.current(),
        session = graph.sessionController.state.value,
        screenOn = true,
        keyguardLocked = false,
        thermal = graph.thermal.value,
        localMinuteOfDay = graph.localMinuteOfDay(),
    )

    private fun onSessionUpdate(update: SessionUpdate) {
        update.commands.forEach { command ->
            if (command is SessionCommand.StopPresentation) {
                graph.eventLog.log(
                    EventLog.Category.SESSION,
                    "屏保收到停止展示指令（${command.reason.label}）",
                )
                endPresentation()
            }
        }
    }

    private fun subscribe() {
        sessionJob = scope.launch {
            graph.sessionController.state.collect { state -> onSessionChanged(state) }
        }
        settingsJob = scope.launch {
            graph.settingsRepository.settings.collect { settings ->
                if (!settings.automationEnabled) {
                    endPresentation()
                } else if (settings.automationMode != AutomationMode.DREAM) {
                    endPresentation()
                } else {
                    applyPlan(EnergyPolicy.planFor(settings, graph.thermal.value, PresentationSource.DREAM))
                    if (settings.fullChargeExit && graph.powerMonitor.current()?.status == ChargingStatus.FULL) {
                        graph.sessionController.suppressForSession(SuppressionReason.FULL_CHARGE, graph.now())
                        endPresentation()
                    }
                }
            }
        }
        powerJob = scope.launch {
            graph.powerMonitor.snapshot.collect { snapshot ->
                if (snapshot == null || !presenting) return@collect
                renderer?.updateBatteryLabel(
                    if (graph.settings.value.showBatteryInfo) snapshot else null,
                )
                if (snapshot.status == ChargingStatus.FULL && graph.settings.value.fullChargeExit) {
                    graph.sessionController.suppressForSession(SuppressionReason.FULL_CHARGE, graph.now())
                    endPresentation()
                }
            }
        }
        closeJob = scope.launch {
            graph.coordinator.closeRequests.collect { endPresentation() }
        }
        thermalJob = scope.launch {
            graph.thermal.collect { level -> onThermalChanged(level) }
        }
    }

    private fun onSessionChanged(state: WirelessSessionState) {
        if (!state.retainsHost(sessionId)) {
            endPresentation()
            return
        }
        if (state.phase == SessionPhase.CONFIRMING_DISCONNECT) {
            // 电源暂时消失：先不结束，等宽限期结束（会话变成 IDLE 时会走上面的分支）。
            isScreenBright = false
            graph.eventLog.log(EventLog.Category.PRESENTATION, "屏保：电源暂时消失，等待防抖确认")
        } else {
            isScreenBright = plan.keepScreenOn
        }
    }

    private fun onThermalChanged(level: ThermalLevel) {
        if (!presenting) return
        if (level == ThermalLevel.CRITICAL) {
            graph.eventLog.log(EventLog.Category.PRESENTATION, "屏保：温度极高温，结束展示")
            if (graph.sessionController.state.value.isActiveSession) {
                graph.sessionController.suppressForSession(SuppressionReason.THERMAL_END, graph.now())
            }
            endPresentation()
            return
        }
        val newPlan = EnergyPolicy.planFor(graph.settings.value, level, PresentationSource.DREAM)
        if (newPlan == plan) return
        applyPlan(newPlan)
        graph.eventLog.log(
            EventLog.Category.PRESENTATION,
            "屏保：温度等级=$level，方案更新为 ${newPlan.label}",
        )
    }

    private fun applyPlan(newPlan: EnergyPlan) {
        plan = newPlan
        window?.let { dreamWindow ->
            dreamWindow.attributes = dreamWindow.attributes.apply {
                screenBrightness = newPlan.brightness.coerceIn(0.01f, 1f)
            }
        }
        isScreenBright = newPlan.keepScreenOn &&
            graph.sessionController.state.value.phase == SessionPhase.ACTIVE
        renderer?.updatePlan(newPlan)
        if (presenting) scheduleDuration()
    }

    private fun scheduleDuration() {
        durationJob?.cancel()
        val remaining = lifetime?.remainingMs(plan.maxDurationMs, graph.now()) ?: return
        durationJob = scope.launch {
            delay(remaining)
            graph.sessionController.suppressForSession(SuppressionReason.MAX_DURATION, graph.now())
            endPresentation()
        }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        if (!presenting) return
        renderer?.resume()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        renderer?.pause()
    }

    override fun onDetachedFromWindow() {
        finalizeOnce()
        super.onDetachedFromWindow()
    }

    /**
     * 结束展示：释放帧调度、订阅、会话驱动与宿主槽位，然后结束整个梦境。
     *
     * 屏保没有"部分关闭"的形态：一旦不再展示，就应该让系统收起梦境，
     * 否则用户会停在一块无内容的黑屏上。
     */
    private fun endPresentation() {
        if (presentationEnded) return
        presentationEnded = true
        presenting = false
        renderer?.release()
        renderer = null
        stopSubscriptions()
        driver?.stop()
        driver = null
        graph.coordinator.onHostFinished(HostKind.DREAM, ownerToken)
        graph.eventLog.log(EventLog.Category.PRESENTATION, "屏保：展示已释放")
        scope.cancel()
        finish()
    }

    private fun stopSubscriptions() {
        durationJob?.cancel()
        durationJob = null
        powerJob?.cancel()
        powerJob = null
        sessionJob?.cancel()
        sessionJob = null
        settingsJob?.cancel()
        settingsJob = null
        closeJob?.cancel()
        closeJob = null
        thermalJob?.cancel()
        thermalJob = null
    }

    /** 解绑兜底释放：即使没走到 endPresentation，也要把作用域与驱动收干净。 */
    private fun finalizeOnce() {
        if (finalized) return
        finalized = true
        renderer?.release()
        renderer = null
        stopSubscriptions()
        driver?.stop()
        driver = null
        graph.coordinator.onHostFinished(HostKind.DREAM, ownerToken)
        scope.cancel()
        graph.eventLog.log(EventLog.Category.PRESENTATION, "屏保已释放全部资源")
    }

    private fun skip(reason: String) {
        graph.eventLog.log(EventLog.Category.PRESENTATION, "屏保放弃展示：$reason")
        endPresentation()
    }

    override fun onDestroy() {
        finalizeOnce()
        super.onDestroy()
    }
}
