package com.wirelesspet.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wirelesspet.WirelessPetApplication
import com.wirelesspet.data.AutomationMode
import com.wirelesspet.data.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 开机自检（设计文档 §11）。
 *
 * 开机广播只用于执行平台允许的恢复工作：读取配置、确认自动化是否开启，
 * 满足条件时再尝试启动前台服务。不宣称"重启后一定恢复"，失败会记录原因。
 *
 * 使用 `goAsync()` 保持广播生命周期，避免冷启动进程在配置读取完成前被回收；
 * 同时给读取加超时，不做无界后台工作。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        val application = context.applicationContext as? WirelessPetApplication ?: return
        val graph = application.graph
        graph.eventLog.log(EventLog.Category.RECOVERY, "收到开机广播（$action），开始检查恢复条件")

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                val settings = withTimeoutOrNull(CONFIG_READ_TIMEOUT_MS) {
                    runCatching { graph.settingsRepository.settings.first() }.getOrNull()
                }
                if (settings == null) {
                    graph.eventLog.log(EventLog.Category.ERROR, "开机恢复失败：配置读取超时或失败")
                    return@launch
                }
                if (com.wirelesspet.BuildConfig.SPINE_ENABLED && !settings.spineLicenseAccepted) return@launch
                if (!settings.automationEnabled) {
                    graph.eventLog.log(EventLog.Category.RECOVERY, "开机恢复跳过：自动化未开启")
                    return@launch
                }
                if (settings.automationMode != AutomationMode.IMMEDIATE) {
                    graph.eventLog.log(EventLog.Category.RECOVERY, "开机恢复跳过：当前为屏保模式")
                    return@launch
                }
                val result = runCatching { AutomationService.start(context) }
                if (result.isSuccess) {
                    graph.eventLog.log(EventLog.Category.RECOVERY, "开机恢复：已请求启动前台监听服务")
                } else {
                    graph.eventLog.log(
                        EventLog.Category.ERROR,
                        "开机恢复：启动前台服务失败（${result.exceptionOrNull()?.javaClass?.simpleName}），等待用户手动打开",
                    )
                }
            } finally {
                graph.eventLog.log(EventLog.Category.RECOVERY, "开机广播处理结束")
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val CONFIG_READ_TIMEOUT_MS = 5_000L
    }
}
