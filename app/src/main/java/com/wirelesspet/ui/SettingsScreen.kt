package com.wirelesspet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wirelesspet.data.AutomationMode
import com.wirelesspet.data.EnergyProfile
import com.wirelesspet.data.LandscapeLayout
import com.wirelesspet.data.UserSettings
import com.wirelesspet.ui.theme.PetOnSurfaceSoft

/**
 * 设置页（设计文档 §7.4）。
 *
 * 只放当前版本真实生效的项目：没有实现的开关不出现，避免"看起来能设置、实际没作用"。
 */
@Composable
fun SettingsScreen(
    settings: UserSettings,
    dreamAvailable: Boolean,
    onModeChange: (AutomationMode) -> Unit,
    onUpdate: ((UserSettings) -> UserSettings) -> Unit,
    onBack: () -> Unit,
    onReset: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val requestAudio = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        onUpdate { it.copy(audioSpectrumEnabled = granted) }
        if (!granted) android.widget.Toast.makeText(context, "未授权音频权限，频谱保持关闭", android.widget.Toast.LENGTH_LONG).show()
    }
    var showCredits by remember { mutableStateOf(false) }
    if (showCredits) CharacterCredits(onDismiss = { showCredits = false })
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(title = "设置", onBack = onBack)

        SectionCard(title = "时钟风格") {
            Text("竖屏置于角色上方，横屏按所选布局显示。点选即可保存。", style = MaterialTheme.typography.bodySmall, color = PetOnSurfaceSoft)
            ClockStylePicker(settings.clockStyle) { style -> onUpdate { it.copy(clockStyle = style) } }
        }

        SectionCard(title = "横屏布局") {
            Text("半身分栏：左侧放大展示角色腰部以上，右侧以超大数字上下显示小时和分钟。仅在横屏时生效。", style = MaterialTheme.typography.bodySmall, color = PetOnSurfaceSoft)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LandscapeLayout.entries.forEach { layout ->
                    OutlinedButton(onClick = { onUpdate { it.copy(landscapeLayout = layout) } }, modifier = Modifier.weight(1f)) {
                        Text((if (settings.landscapeLayout == layout) "✓ " else "") + layout.label)
                    }
                }
            }
        }

        SectionCard(title = "自动化") {
            Text("无线充电默认触发；亮屏和锁屏显示仍需系统允许。", style = MaterialTheme.typography.bodySmall, color = PetOnSurfaceSoft)
            ToggleRow("有线充电时也自动显示", settings.wiredChargingEnabled,
                onCheckedChange = { value -> onUpdate { it.copy(wiredChargingEnabled = value) } },
                subtitle = "默认关闭，开启后支持充电器、USB 与供电底座")
            Text(
                text = "当前模式：${settings.automationMode.label}",
                style = MaterialTheme.typography.bodySmall,
                color = PetOnSurfaceSoft,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AutomationMode.entries.forEach { mode ->
                    val selected = settings.automationMode == mode
                    val available = mode != AutomationMode.DREAM || dreamAvailable
                    if (selected) {
                        androidx.compose.material3.Button(
                            onClick = { onModeChange(mode) },
                            enabled = available,
                            modifier = Modifier.weight(1f),
                        ) { Text(mode.label) }
                    } else {
                        OutlinedButton(
                            onClick = { onModeChange(mode) },
                            enabled = available,
                            modifier = Modifier.weight(1f),
                        ) { Text(mode.label) }
                    }
                }
            }
            if (!dreamAvailable) {
                Text(
                    text = "当前系统没有提供屏保入口，闲置屏保模式不可用",
                    style = MaterialTheme.typography.bodySmall,
                    color = PetOnSurfaceSoft,
                )
            }
            Divider()
            ToggleRow(
                title = "允许打断其他应用",
                subtitle = "关闭时：屏幕亮着只用通知提示，不抢占前台（推荐）",
                checked = settings.allowInterruptOtherApps,
                onCheckedChange = { value -> onUpdate { it.copy(allowInterruptOtherApps = value) } },
            )
            ToggleRow(
                title = "息屏时点亮屏幕",
                subtitle = "能否真正亮屏取决于系统是否允许后台启动页面，需真机验证",
                checked = settings.wakeScreenWhenOff,
                onCheckedChange = { value -> onUpdate { it.copy(wakeScreenWhenOff = value) } },
            )
            ToggleRow(
                title = "允许在锁屏上展示",
                subtitle = "只显示在锁屏之上，不会解除安全锁屏",
                checked = settings.showOnLockScreen,
                onCheckedChange = { value -> onUpdate { it.copy(showOnLockScreen = value) } },
            )
        }

        SectionCard(title = "展示") {
            ToggleRow(
                title = "显示电量与充电状态",
                subtitle = "按事件更新，不每帧刷新",
                checked = settings.showBatteryInfo,
                onCheckedChange = { value -> onUpdate { it.copy(showBatteryInfo = value) } },
            )
            ToggleRow(
                title = "允许点击互动",
                checked = settings.interactionEnabled,
                onCheckedChange = { value -> onUpdate { it.copy(interactionEnabled = value) } },
            )
        }

        SectionCard(title = "音频频谱") {
            ToggleRow(title = "两侧动态频谱", subtitle = "跟随手机正在播放的音频，静音时收起", checked = settings.audioSpectrumEnabled,
                onCheckedChange = { enabled ->
                    if (enabled && context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        requestAudio.launch(android.Manifest.permission.RECORD_AUDIO)
                    else onUpdate { it.copy(audioSpectrumEnabled = enabled) }
                })
            Text("系统要求授予录音权限；仅分析播放频谱，不使用麦克风、不保存音频。部分应用或设备可能限制音频读取。", style = MaterialTheme.typography.bodySmall, color = PetOnSurfaceSoft)
        }

        SectionCard(title = "节能与温度") {
            Text(
                text = "节能档位",
                style = MaterialTheme.typography.bodySmall,
                color = PetOnSurfaceSoft,
            )
            EnergyProfile.entries.forEach { profile ->
                val selected = settings.energyProfile == profile
                if (selected) {
                    androidx.compose.material3.Button(
                        onClick = { onUpdate { it.copy(energyProfile = profile) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(profile.label) }
                } else {
                    OutlinedButton(
                        onClick = { onUpdate { it.copy(energyProfile = profile) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(profile.label) }
                }
            }
            Text(
                text = when (settings.energyProfile) {
                    EnergyProfile.BATTERY_SAVER -> "24 帧欢迎、15 帧待机，允许系统正常息屏"
                    EnergyProfile.COMPANION -> "30 帧互动、24 帧待机，低亮度持续显示"
                    EnergyProfile.SMOOTH_PREVIEW -> "30 帧持续展示，不限时；升温时降载"
                },
                style = MaterialTheme.typography.bodySmall,
                color = PetOnSurfaceSoft,
            )
            Divider()
            Text(
                text = "展示不限时，直到手动关闭或充电会话结束；极高温保护仍生效。",
                style = MaterialTheme.typography.bodySmall,
                color = PetOnSurfaceSoft,
            )
            ToggleRow(
                title = "充满后自动退出",
                subtitle = "关闭时：充满仍继续休眠展示，直到拿离充电器",
                checked = settings.fullChargeExit,
                onCheckedChange = { value -> onUpdate { it.copy(fullChargeExit = value) } },
            )
        }

        SectionCard(title = "不展示时段") {
            ToggleRow(
                title = "启用不展示时段",
                subtitle = "时段内不自动展示（跨零点按本地时间判断）",
                checked = settings.quietHoursEnabled,
                onCheckedChange = { value -> onUpdate { it.copy(quietHoursEnabled = value) } },
            )
            if (settings.quietHoursEnabled) {
                QuietHourEditor(
                    startMinute = settings.quietStartMinuteOfDay,
                    endMinute = settings.quietEndMinuteOfDay,
                    onChange = { start, end ->
                        onUpdate { it.copy(quietStartMinuteOfDay = start, quietEndMinuteOfDay = end) }
                    },
                )
            }
        }

        SectionCard(title = "关于角色") {
            Text("角色与运行库", color = MaterialTheme.colorScheme.primary)
            Text("呼吸、眨眼与轻触回应，都在本机发生。", style = MaterialTheme.typography.bodySmall, color = PetOnSurfaceSoft)
            TextButton(onClick = { showCredits = true }) { Text("角色署名与开源许可  ›") }
        }
        SectionCard(title = "数据") {
            TextButton(onClick = onReset) { Text("恢复默认设置") }
            Text(
                text = "诊断日志可在「诊断与日志」页导出，导出内容不包含账号、通知正文或其他应用内容。",
                style = MaterialTheme.typography.bodySmall,
                color = PetOnSurfaceSoft,
            )
        }
    }
}

@Composable
private fun QuietHourEditor(
    startMinute: Int,
    endMinute: Int,
    onChange: (Int, Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TimeStepper(
            label = "开始",
            minute = startMinute,
            onMinuteChange = { onChange(it, endMinute) },
            modifier = Modifier.weight(1f),
        )
        TimeStepper(
            label = "结束",
            minute = endMinute,
            onMinuteChange = { onChange(startMinute, it) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TimeStepper(
    label: String,
    minute: Int,
    onMinuteChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "$label ${formatMinute(minute)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { onMinuteChange((minute - 30 + 1440) % 1440) }) {
                Text("-30")
            }
            OutlinedButton(onClick = { onMinuteChange((minute + 30) % 1440) }) {
                Text("+30")
            }
        }
    }
}

private fun formatMinute(minute: Int): String {
    val normalized = ((minute % 1440) + 1440) % 1440
    return String.format("%02d:%02d", normalized / 60, normalized % 60)
}
