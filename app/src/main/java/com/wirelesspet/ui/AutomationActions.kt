package com.wirelesspet.ui

import android.content.Context
import android.widget.Toast
import com.wirelesspet.AppGraph
import com.wirelesspet.automation.AutomationService
import com.wirelesspet.data.AutomationMode
import com.wirelesspet.data.EventLog
import com.wirelesspet.session.SuppressionReason
import kotlinx.coroutines.flow.first

/**
 * 首页/设置页共用的自动化动作。
 *
 * 所有开启动作都先把配置写入 DataStore，再启动前台服务：否则服务创建时读到的
 * 还是上一次的"关闭"状态，会立刻自行停止（真机 M1 阶段实测过这个问题）。
 * 每个分支都把结果写进诊断日志。
 */
object AutomationActions {

    suspend fun toggle(context: Context, graph: AppGraph, enable: Boolean): String {
        graph.settingsRepository.setAutomationEnabled(enable)
        return if (enable) start(context, graph) else stop(context, graph)
    }

    /**
     * 切换自动化模式。
     *
     * 必须"先写完配置，再按新配置决定动作"：之前是先异步写配置、立刻重启读一次配置，
     * 有概率仍读到旧值（例如从屏保切回立即展示时读到 DREAM），结果只弹出屏保设置、
     * 前台服务没启动，用户看到的就是"放上充电器毫无反应"。
     */
    suspend fun applyMode(context: Context, graph: AppGraph, mode: AutomationMode): String {
        graph.settingsRepository.setAutomationMode(mode)
        val settings = graph.settingsRepository.settings.first()
        graph.eventLog.log(
            EventLog.Category.SERVICE,
            "切换模式为 ${mode.label}（自动化=${if (settings.automationEnabled) "开" else "关"}）",
        )
        return when (mode) {
            AutomationMode.IMMEDIATE -> {
                if (settings.automationEnabled) {
                    start(context, graph)
                } else {
                    "已切换为立即展示，开启主开关后生效"
                }
            }

            AutomationMode.DREAM -> {
                if (!settings.automationEnabled) {
                    "已切换为闲置屏保，开启主开关并选择系统屏保后生效"
                } else if (!graph.capabilityDetector.dreamServiceVisible()) {
                    graph.eventLog.log(
                        EventLog.Category.ERROR,
                        "屏保模式不可用：当前系统没有提供屏保入口",
                    )
                    "当前系统没有提供屏保入口，闲置屏保模式不可用"
                } else {
                    graph.eventLog.log(
                        EventLog.Category.CAPABILITY,
                        "屏保模式：引导用户进入系统屏保设置",
                    )
                    val opened = graph.capabilityDetector.openDreamSettings()
                    if (opened) "请在系统屏保中选择「ChargeMate」"
                    else "无法打开屏保设置，请手动前往设置 → 显示 → 屏保"
                }
            }
        }
    }

    suspend fun start(context: Context, graph: AppGraph): String {
        graph.settingsRepository.setAutomationEnabled(true)
        val settings = graph.settingsRepository.settings.first()
        if (settings.automationMode == AutomationMode.DREAM) {
            if (!graph.capabilityDetector.dreamServiceVisible()) {
                graph.eventLog.log(
                    EventLog.Category.ERROR,
                    "屏保模式不可用：当前系统没有提供屏保入口",
                )
                return "当前系统没有提供屏保入口，闲置屏保模式不可用"
            }
            graph.eventLog.log(
                EventLog.Category.CAPABILITY,
                "屏保模式：引导用户进入系统屏保设置",
            )
            val opened = graph.capabilityDetector.openDreamSettings()
            return if (opened) "请在系统屏保中选择「ChargeMate」" else "无法打开屏保设置，请手动前往设置 → 显示 → 屏保"
        }
        val result = runCatching { AutomationService.start(context) }
        return if (result.isSuccess) {
            graph.eventLog.log(EventLog.Category.SERVICE, "用户开启自动化，已请求启动监听服务")
            "已开启监听，放上无线充电器试试"
        } else {
            graph.eventLog.log(
                EventLog.Category.ERROR,
                "启动监听服务失败：${result.exceptionOrNull()?.javaClass?.simpleName}",
            )
            "启动失败：系统拒绝了后台启动，请检查权限后重试"
        }
    }

    fun stop(context: Context, graph: AppGraph): String {
        AutomationService.stop(context)
        graph.coordinator.requestClose(SuppressionReason.AUTOMATION_OFF)
        graph.sessionController.reset(graph.now())
        graph.eventLog.log(EventLog.Category.SERVICE, "用户关闭自动化，已停止监听服务")
        return "已关闭自动化"
    }

    suspend fun testAutoStart(context: Context, graph: AppGraph): String {
        graph.settingsRepository.setAutomationEnabled(true)
        graph.eventLog.log(
            EventLog.Category.CAPABILITY,
            "开始自动启动测试：请把手机放到无线充电器上，观察是否自动打开显示页面",
        )
        AutomationService.start(context)
        return "测试已开始：放上无线充电器后观察是否自动展示，结果可在诊断页查看"
    }

    /**
     * 用户主动"继续展示"。
     *
     * 与自动触发分开：这里先确认自动化和会话仍然有效（不满足时给出具体原因），
     * 然后解除本会话的启动受限记录；服务没在运行时先把它拉起来，
     * 否则只清除抑制却没人负责展示。
     */
    suspend fun resume(context: Context, graph: AppGraph): String {
        val settings = graph.settingsRepository.settings.first()
        if (!settings.automationEnabled) {
            return "自动化已关闭，请先开启充电时自动显示"
        }
        if (settings.automationMode != AutomationMode.IMMEDIATE) {
            return "当前是屏保模式，请到系统屏保设置里查看"
        }
        val session = graph.sessionController.state.value
        if (!session.isActiveSession) {
            return "当前没有生效中的无线充电会话，无法继续展示"
        }

        graph.coordinator.onUserResumeRequested()
        if (graph.automationRunning.value) {
            AutomationService.resume(context)
        } else {
            val started = runCatching { AutomationService.start(context) }.isSuccess
            if (!started) {
                graph.eventLog.log(
                    EventLog.Category.ERROR,
                    "继续展示失败：无法启动监听服务，改为在应用内恢复会话",
                )
                graph.sessionController.onResumeRequested(graph.now())
                return "系统拒绝了后台启动，请在应用内手动查看"
            }
            AutomationService.resume(context)
        }
        graph.eventLog.log(EventLog.Category.SESSION, "用户请求继续展示当前会话")
        return "已请求继续展示"
    }

    fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
