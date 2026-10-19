package com.wirelesspet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.wirelesspet.automation.AutomationService
import com.wirelesspet.data.AutomationMode
import com.wirelesspet.data.EventLog
import com.wirelesspet.ui.PetApp
import com.wirelesspet.ui.theme.WirelessPetTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 应用入口。负责首次向导、首页、设置与诊断的导航；自动化本体在
 * AutomationService / PetDreamService / PetActivity 中，本页不持有任何电源状态副本。
 */
class MainActivity : ComponentActivity() {

    private val graph: AppGraph get() = (application as WirelessPetApplication).graph
    private var monitoring = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WirelessPetTheme {
                PetApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!monitoring) {
            monitoring = true
            graph.acquirePowerMonitor()
        }
        ensureListenerRunning()
    }

    /**
     * 自愈：用户已经开启自动化、又选择了立即展示，但监听服务没在跑时把它拉起来。
     *
     * 典型场景：切过一次屏保模式、安装新版本、被系统或厂商清理。之前只能靠用户自己
     * 再点一次开关，表现就是"连上无线充毫无反应"。
     *
     * 注意必须等 DataStore 真正读出配置：`onStart` 早于首次读取时 `settings.value` 还是默认值
     * （自动化为关），直接判断会永远跳过自愈。
     */
    private fun ensureListenerRunning() {
        lifecycleScope.launch {
            val settings = runCatching { graph.settingsRepository.settings.first() }.getOrNull()
                ?: return@launch
            if (BuildConfig.SPINE_ENABLED && !settings.spineLicenseAccepted) return@launch
            if (!settings.automationEnabled) return@launch
            if (settings.automationMode != AutomationMode.IMMEDIATE) return@launch
            if (graph.automationRunning.value) return@launch
            graph.eventLog.log(
                EventLog.Category.SERVICE,
                "应用回到前台：立即展示模式已开启但监听服务未运行，尝试恢复",
            )
            val started = runCatching { AutomationService.start(this@MainActivity) }
            if (started.isFailure) {
                graph.eventLog.log(
                    EventLog.Category.ERROR,
                    "恢复监听服务失败：${started.exceptionOrNull()?.javaClass?.simpleName}",
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (monitoring) {
            monitoring = false
            graph.releasePowerMonitor()
        }
    }
}
