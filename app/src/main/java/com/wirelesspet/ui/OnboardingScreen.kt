package com.wirelesspet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wirelesspet.data.UserSettings
import com.wirelesspet.platform.CapabilityDetector
import com.wirelesspet.ui.theme.PetOnSurfaceSoft

/**
 * 首次使用向导（设计文档 §7.2）。
 *
 * 目标是一次配置后日常自动运行，而不是安装后零授权接管系统：
 * 每一步只说明当前能做什么、需要什么、做不到什么。
 */
@Composable
fun OnboardingScreen(
    settings: UserSettings,
    report: CapabilityDetector.Report,
    onUpdate: ((UserSettings) -> UserSettings) -> Unit,
    onCapabilityAction: (CapabilityDetector.Action) -> Unit,
    onFinished: () -> Unit,
    onSkip: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val steps = 4

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(
            title = "初始设置",
            subtitle = "第 ${step + 1} / $steps 步",
        )

        when (step) {
            0 -> SectionCard(title = "无线充电自动显示") {
                Text(
                    text = "连接无线充电器后显示角色和时钟，断开后自动退出。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "需要知道的两件事：\n" +
                        "1. 显示期间屏幕是亮的，会消耗电量并产生一定发热；\n" +
                        "2. 普通应用无法保证后台一定启动成功，本版本要在小米 14 上真机验证。",
                    style = MaterialTheme.typography.bodySmall,
                    color = PetOnSurfaceSoft,
                )
            }

            1 -> SectionCard(title = "选择打扰与亮屏策略") {
                ToggleRow(
                    title = "正在用其他应用时允许直接弹出",
                    subtitle = "关闭时只发通知，不抢占当前界面（推荐）",
                    checked = settings.allowInterruptOtherApps,
                    onCheckedChange = { value ->
                        onUpdate { it.copy(allowInterruptOtherApps = value) }
                    },
                )
                Divider()
                ToggleRow(
                    title = "息屏时点亮屏幕",
                    subtitle = "需要系统允许后台启动页面，实际效果以真机测试为准",
                    checked = settings.wakeScreenWhenOff,
                    onCheckedChange = { value ->
                        onUpdate { it.copy(wakeScreenWhenOff = value) }
                    },
                )
                Divider()
                ToggleRow(
                    title = "锁屏时也在锁屏上展示",
                    subtitle = "只显示在锁屏之上，不解除安全锁屏",
                    checked = settings.showOnLockScreen,
                    onCheckedChange = { value ->
                        onUpdate { it.copy(showOnLockScreen = value) }
                    },
                )
            }

            2 -> SectionCard(title = "设备能力与授权") {
                StatusRow(
                    label = "设备",
                    value = "${report.device.manufacturer} ${report.device.model}",
                    detail = "${report.device.romLabel} · Android ${report.device.androidRelease}",
                )
                Divider()
                val important = report.items.filter {
                    it.id in setOf("notifications", "dream", "wireless_history", "oem_autostart") ||
                        it.id.startsWith("xiaomi") || it.id.startsWith("coloros")
                }
                important.forEach { item ->
                    StatusRow(
                        label = item.title,
                        value = item.state.label,
                        tone = when (item.state) {
                            CapabilityDetector.State.OK -> Tone.GOOD
                            CapabilityDetector.State.MISSING -> Tone.WARN
                            else -> Tone.NEUTRAL
                        },
                        detail = item.detail,
                    )
                    item.action?.let { action ->
                        TextButton(onClick = { onCapabilityAction(action) }) { Text("去处理") }
                    }
                }
                Text(
                    text = "这些授权无法由应用静默完成，只能在系统里由你确认一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = PetOnSurfaceSoft,
                )
            }

            else -> SectionCard(title = "完成设置") {
                Text(
                    text = "接下来会开启自动监听（立即展示模式）。请把手机放上无线充电器，观察是否自动打开显示页面；" +
                        "如果没有任何反应，请到「诊断与日志」查看是没收到事件、策略抑制，还是后台启动被拦截。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ToggleRow(
                    title = "开启自动显示",
                    subtitle = "关闭也可以稍后在首页开启",
                    checked = settings.automationEnabled,
                    onCheckedChange = { value ->
                        onUpdate { it.copy(automationEnabled = value) }
                    },
                )
                Text(
                    text = "默认策略：省电优先、用户退出后本次不再弹出、不修改系统全局亮度与充电功率。",
                    style = MaterialTheme.typography.bodySmall,
                    color = PetOnSurfaceSoft,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (step > 0) {
                OutlinedButton(onClick = { step -= 1 }, modifier = Modifier.weight(1f)) {
                    Text("上一步")
                }
            }
            if (step < steps - 1) {
                Button(onClick = { step += 1 }, modifier = Modifier.weight(1f)) {
                    Text("下一步")
                }
            } else {
                Button(onClick = onFinished, modifier = Modifier.weight(1f)) {
                    Text("完成设置")
                }
            }
        }
        TextButton(onClick = onSkip) { Text("跳过向导") }
    }
}
