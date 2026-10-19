package com.wirelesspet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wirelesspet.data.EventLog
import com.wirelesspet.data.UserSettings
import com.wirelesspet.platform.CapabilityDetector
import com.wirelesspet.presentation.ActiveHost
import com.wirelesspet.session.WirelessSessionState
import com.wirelesspet.ui.theme.PetOnSurfaceSoft

/**
 * 诊断页（设计文档 §7.4、§12.2）。
 *
 * 把"已请求启动"与"宿主实际可见"分开显示，并把每项能力的缺失影响写清楚；
 * 导出前说明包含内容，导出动作由用户主动触发。
 */
@Composable
fun DiagnosticsScreen(
    report: CapabilityDetector.Report,
    events: List<EventLog.Entry>,
    settings: UserSettings,
    session: WirelessSessionState,
    automationRunning: Boolean,
    host: ActiveHost?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCapabilityAction: (CapabilityDetector.Action) -> Unit,
    onExport: () -> Unit,
    onClearLog: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 20.dp),
    ) {
        item {
            ScreenHeader(
                title = "运行诊断",
                subtitle = "只在本机保存，由你主动导出",
                onBack = onBack,
            )
        }

        item {
            SectionCard(title = "设备与系统") {
                val device = report.device
                StatusRow("机型", "${device.manufacturer} ${device.model}")
                StatusRow("Android", "${device.androidRelease}（SDK ${device.sdkInt}）")
                StatusRow("ROM", device.romLabel)
                StatusRow("构建", device.display, detail = device.incremental)
                StatusRow("应用", device.appVersion, detail = "${device.packageName} · targetSdk ${device.targetSdk}")
            }
        }

        item {
            SectionCard(title = "运行状态") {
                StatusRow(
                    label = "自动化",
                    value = if (settings.automationEnabled) "已开启" else "已关闭",
                    tone = if (settings.automationEnabled) Tone.GOOD else Tone.NEUTRAL,
                    detail = "模式：${settings.automationMode.label}",
                )
                StatusRow(
                    label = "监听服务",
                    value = if (automationRunning) "运行中" else "未运行",
                    tone = if (automationRunning) Tone.GOOD else Tone.NEUTRAL,
                )
                StatusRow("会话", session.describe(), detail = "session=${session.sessionId}")
                StatusRow(
                    label = "展示抑制",
                    value = session.suppression.label,
                    tone = if (session.isSuppressed) Tone.WARN else Tone.NEUTRAL,
                )
                StatusRow(
                    label = "宿主",
                    value = when {
                        host == null -> "无"
                        host.visible -> "已可见（${host.source.label}）"
                        else -> "已请求但未确认可见"
                    },
                    tone = if (host?.visible == true) Tone.GOOD else Tone.NEUTRAL,
                )
            }
        }

        item {
            SectionCard(
                title = "能力检测",
                subtitle = "五种能力分开判断：监听、启动服务、启动页面、锁屏展示、点亮屏幕",
            ) {
                report.items.forEach { item ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        StatusRow(
                            label = item.title,
                            value = item.state.label,
                            tone = when (item.state) {
                                CapabilityDetector.State.OK -> Tone.GOOD
                                CapabilityDetector.State.MISSING -> Tone.WARN
                                CapabilityDetector.State.UNKNOWN -> Tone.NEUTRAL
                                CapabilityDetector.State.INFO -> Tone.NEUTRAL
                                CapabilityDetector.State.NOT_APPLICABLE -> Tone.NEUTRAL
                            },
                            detail = item.detail,
                        )
                        item.manualPath?.let {
                            Text(
                                text = "手动路径：$it",
                                style = MaterialTheme.typography.bodySmall,
                                color = PetOnSurfaceSoft,
                            )
                        }
                        item.action?.let { action ->
                            TextButton(onClick = { onCapabilityAction(action) }) { Text("去处理") }
                        }
                    }
                }
                OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                    Text("重新检测")
                }
            }
        }

        item {
            SectionCard(
                title = "最近事件",
                subtitle = "共 ${events.size} 条，超出上限自动覆盖旧记录",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                        Text("导出诊断")
                    }
                    OutlinedButton(onClick = onClearLog, modifier = Modifier.weight(1f)) {
                        Text("清空日志")
                    }
                }
            }
        }

        if (events.isEmpty()) {
            item {
                Text(
                    text = "暂无事件记录。开启自动化并放上无线充电器后会在这里出现电源、会话与策略记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = PetOnSurfaceSoft,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        } else {
            val ordered = events.reversed()
            items(ordered) { entry ->
                Text(
                    text = entry.format(),
                    style = MaterialTheme.typography.labelSmall,
                    color = PetOnSurfaceSoft,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}
