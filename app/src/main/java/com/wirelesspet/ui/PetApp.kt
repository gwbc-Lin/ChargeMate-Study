package com.wirelesspet.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.wirelesspet.WirelessPetApplication
import com.wirelesspet.data.UserSettings
import com.wirelesspet.platform.CapabilityDetector
import com.wirelesspet.presentation.PetHostIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用根界面：用最简单的状态机在首页、设置、诊断、向导之间切换，
 * 首版不引入导航库，减少依赖与重建风险。
 */
@Composable
fun PetApp() {
    val context = LocalContext.current
    val graph = remember(context) { (context.applicationContext as WirelessPetApplication).graph }
    val scope = rememberCoroutineScope()

    val settings by graph.settings.collectAsState()
    val settingsLoaded by graph.settingsLoaded.collectAsState()
    val session by graph.sessionController.state.collectAsState()
    val power by graph.powerMonitor.snapshot.collectAsState()
    val automationRunning by graph.automationRunning.collectAsState()
    val host by graph.coordinator.host.collectAsState()
    val wirelessEverSeen by graph.wirelessEverSeen.collectAsState()
    val events by graph.eventLog.entries.collectAsState()
    val thermal by graph.thermal.collectAsState()
    val launchBlockedAt by graph.lastLaunchBlockedAt.collectAsState()

    var screenName by rememberSaveable { mutableStateOf<String?>(null) }
    var refreshToken by remember { mutableIntStateOf(0) }
    var pendingEnable by remember { mutableStateOf(false) }

    val current = when {
        !settingsLoaded -> "LOADING"
        com.wirelesspet.BuildConfig.SPINE_ENABLED && !settings.spineLicenseAccepted -> "LICENSE"
        else -> screenName ?: if (settings.onboardingCompleted) "HOME" else "ONBOARDING"
    }

    val report = remember(current, refreshToken, wirelessEverSeen) {
        graph.capabilityDetector.inspect(wirelessEverSeen)
    }

    // 屏保入口是否存在决定"闲置屏保"模式是否可用：文档要求不展示无效开关。
    val dreamAvailable = remember(current, refreshToken) {
        graph.capabilityDetector.dreamServiceVisible()
    }

    val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        if (pendingEnable) {
            pendingEnable = false
            scope.launch {
                AutomationActions.toast(context, AutomationActions.toggle(context, graph, enable = true))
            }
        }
        refreshToken += 1
    }

    val onCapabilityAction: (CapabilityDetector.Action) -> Unit = { action ->
        when (action) {
            CapabilityDetector.Action.RequestNotificationPermission -> {
                if (needsNotificationPermission) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    graph.capabilityDetector.openNotificationSettings()
                }
            }

            CapabilityDetector.Action.OpenNotificationSettings ->
                graph.capabilityDetector.openNotificationSettings()

            CapabilityDetector.Action.OpenBatteryOptimization ->
                graph.capabilityDetector.requestIgnoreBatteryOptimizations()

            CapabilityDetector.Action.OpenOverlaySettings -> {
                if (!graph.capabilityDetector.openOverlaySettings()) {
                    AutomationActions.toast(context, "无法打开系统悬浮窗设置，请在设置中搜索「显示在其他应用上层」")
                    graph.oemAdapter.openAppDetails()
                }
            }

            CapabilityDetector.Action.OpenDreamSettings ->
                graph.capabilityDetector.openDreamSettings()

            CapabilityDetector.Action.OpenAppDetails ->
                graph.oemAdapter.openAppDetails()

            is CapabilityDetector.Action.OpenOemPage -> {
                val page = graph.oemAdapter.pages().firstOrNull { it.id == action.pageId }
                val opened = page != null && graph.oemAdapter.open(page)
                if (!opened) {
                    AutomationActions.toast(
                        context,
                        "无法打开厂商页面，请手动前往：${page?.manualPath ?: "设置 → 应用 → 应用管理"}",
                    )
                    graph.oemAdapter.openAppDetails()
                }
            }
        }
        refreshToken += 1
    }

    val toggleAutomation: (Boolean) -> Unit = { enable ->
        if (enable && needsNotificationPermission) {
            pendingEnable = true
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            scope.launch {
                AutomationActions.toast(context, AutomationActions.toggle(context, graph, enable))
                refreshToken += 1
            }
        }
    }

    BackHandler(enabled = current !in setOf("HOME", "LOADING", "LICENSE")) {
        screenName = "HOME"
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            when (current) {
            "LICENSE" -> SpineLicenseScreen(onAccepted = {
                scope.launch {
                    graph.settingsRepository.update { it.copy(spineLicenseAccepted = true) }
                    val latest = graph.settingsRepository.settings.first()
                    if (latest.automationEnabled && latest.automationMode == com.wirelesspet.data.AutomationMode.IMMEDIATE) {
                        runCatching { com.wirelesspet.automation.AutomationService.start(context) }
                    }
                }
            })
            "LOADING" -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("正在读取配置…", style = MaterialTheme.typography.bodyMedium)
            }

            "SETTINGS" -> SettingsScreen(
                settings = settings,
                dreamAvailable = dreamAvailable,
                onModeChange = { mode ->
                    scope.launch {
                        AutomationActions.toast(context, AutomationActions.applyMode(context, graph, mode))
                        refreshToken += 1
                    }
                },
                onUpdate = { transform -> graph.updateSettings(transform) },
                onBack = { screenName = "HOME" },
                onReset = {
                    graph.updateSettings { UserSettings.DEFAULT }
                    AutomationActions.toast(context, "已恢复默认设置")
                },
            )

            "DIAGNOSTICS" -> DiagnosticsScreen(
                report = report,
                events = events,
                settings = settings,
                session = session,
                automationRunning = automationRunning,
                host = host,
                onBack = { screenName = "HOME" },
                onRefresh = { refreshToken += 1 },
                onCapabilityAction = onCapabilityAction,
                onExport = {
                    scope.launch {
                        val exporter = graph.diagnosticsExporter
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val text = exporter.buildReport(
                                    wirelessEverSeen = wirelessEverSeen,
                                    automationRunning = automationRunning,
                                    settings = settings,
                                    session = session,
                                )
                                exporter.writeToCache(text)
                            }
                        }
                        val file = result.getOrNull()
                        if (file == null) {
                            AutomationActions.toast(context, "导出失败，请稍后重试")
                        } else {
                            runCatching {
                                context.startActivity(exporter.shareIntent(file))
                            }.onFailure {
                                AutomationActions.toast(context, "已生成文件：${file.absolutePath}")
                            }
                        }
                    }
                },
                onClearLog = {
                    graph.eventLog.clearAll()
                    graph.eventLog.log(
                        com.wirelesspet.data.EventLog.Category.SERVICE,
                        "用户清空了诊断日志",
                    )
                    AutomationActions.toast(context, "已清空日志")
                },
            )

            "ONBOARDING" -> OnboardingScreen(
                settings = settings,
                report = report,
                onUpdate = { transform -> graph.updateSettings(transform) },
                onCapabilityAction = onCapabilityAction,
                onFinished = {
                    graph.updateSettings { it.copy(onboardingCompleted = true) }
                    scope.launch {
                        AutomationActions.toast(context, AutomationActions.start(context, graph))
                    }
                    screenName = "HOME"
                },
                onSkip = {
                    graph.updateSettings { it.copy(onboardingCompleted = true) }
                    screenName = "HOME"
                },
            )

            else -> HomeScreen(
                onCharacterChange = { id -> graph.updateSettings { it.copy(selectedCharacterId = id) } },
                settings = settings,
                session = session,
                power = power,
                automationRunning = automationRunning,
                host = host,
                report = report,
                dreamAvailable = dreamAvailable,
                thermal = thermal,
                launchBlockedAt = launchBlockedAt,
                onOpenBackgroundPopup = {
                    val page = graph.oemAdapter.pages().firstOrNull { it.id == "xiaomi_background_popup" }
                    val opened = page?.let { graph.oemAdapter.open(it) } == true
                    if (!opened) {
                        AutomationActions.toast(context, "无法直达权限页，已尝试打开应用详情，请查找「其他权限」中的「后台弹出界面」和「锁屏显示」")
                        graph.oemAdapter.openAppDetails()
                    }
                },
                onDismissLaunchBlocked = { graph.acknowledgeLaunchBlocked() },
                onToggleAutomation = toggleAutomation,
                onModeChange = { mode ->
                    scope.launch {
                        AutomationActions.toast(context, AutomationActions.applyMode(context, graph, mode))
                        refreshToken += 1
                    }
                },
                onPreview = {
                    val started = runCatching {
                        context.startActivity(PetHostIntents.preview(context))
                    }.isSuccess
                    if (!started) AutomationActions.toast(context, "无法打开预览页面")
                },
                onTestAutoStart = {
                    scope.launch {
                        AutomationActions.toast(context, AutomationActions.testAutoStart(context, graph))
                        refreshToken += 1
                    }
                },
                onResume = {
                    scope.launch {
                        AutomationActions.toast(context, AutomationActions.resume(context, graph))
                        refreshToken += 1
                    }
                },
                onOpenSettings = { screenName = "SETTINGS" },
                onOpenDiagnostics = { screenName = "DIAGNOSTICS" },
                onOpenOnboarding = { screenName = "ONBOARDING" },
                onCapabilityAction = onCapabilityAction,
            )
            }
        }
    }
}
