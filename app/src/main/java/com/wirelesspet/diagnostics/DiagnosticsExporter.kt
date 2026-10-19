package com.wirelesspet.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.wirelesspet.data.EventLog
import com.wirelesspet.data.UserSettings
import com.wirelesspet.platform.CapabilityDetector
import com.wirelesspet.session.WirelessSessionState
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 诊断导出（设计文档 §12.2）。
 *
 * 导出前明确说明包含哪些信息：设备型号、系统版本、ROM 版本、应用版本、
 * 授权状态、最近的电源与策略事件。不包含任何稳定设备标识、通知正文或其他应用内容。
 */
class DiagnosticsExporter(
    private val context: Context,
    private val eventLog: EventLog,
    private val capabilityDetector: CapabilityDetector,
) {

    fun buildReport(
        wirelessEverSeen: Boolean,
        automationRunning: Boolean,
        settings: UserSettings,
        session: WirelessSessionState,
    ): String {
        val report = capabilityDetector.inspect(wirelessEverSeen)
        val device = report.device
        val header = buildString {
            appendLine("ChargeMate · 诊断导出")
            appendLine("导出时间：${FORMATTER.format(Instant.now().atZone(ZoneId.systemDefault()))}")
            appendLine("说明：本文件仅包含应用运行所需的系统与授权信息，不含账号、通知正文、其他应用内容或设备唯一标识。")
            appendLine()
            appendLine("--- 设备与系统 ---")
            appendLine("机型：${device.manufacturer} ${device.model}（品牌 ${device.brand}）")
            appendLine("Android：${device.androidRelease}（SDK ${device.sdkInt}，应用 targetSdk ${device.targetSdk}）")
            appendLine("ROM：${device.romLabel}")
            appendLine("构建：${device.display} / ${device.incremental}")
            appendLine("应用：${device.packageName} ${device.appVersion}")
            appendLine()
            appendLine("--- 当前配置 ---")
            appendLine("自动化开关：${if (settings.automationEnabled) "开启" else "关闭"}")
            appendLine("模式：${settings.automationMode.label}")
            appendLine("打断其他应用：${if (settings.allowInterruptOtherApps) "允许" else "仅通知"}")
            appendLine("息屏亮屏：${if (settings.wakeScreenWhenOff) "允许" else "不亮屏"}")
            appendLine("锁屏展示：${if (settings.showOnLockScreen) "允许" else "不允许"}")
            appendLine("节能档位：${settings.energyProfile.label}")
            appendLine("最长展示：不限制（极高温保护除外）")
            appendLine()
            appendLine("--- 运行状态 ---")
            appendLine("监听服务：${if (automationRunning) "运行中" else "未运行"}")
            appendLine("会话：${session.describe()}（session=${session.sessionId}）")
            appendLine("抑制：${session.suppression.label}")
            appendLine()
            appendLine("--- 能力检测 ---")
            report.items.forEach { item ->
                appendLine("[${item.state.label}] ${item.title}：${item.detail}")
                item.manualPath?.let { appendLine("        手动路径：$it") }
            }
            appendLine()
            appendLine("--- 最近事件 ---")
        }
        return eventLog.exportText(header)
    }

    /** 写入缓存目录并返回文件；通过 FileProvider 分享，不落地到公共目录。 */
    fun writeToCache(report: String): File {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = FILE_FORMATTER.format(Instant.now().atZone(ZoneId.systemDefault()))
        val file = File(directory, "wirelesspet-diagnostics-$stamp.txt")
        file.writeText(report)
        return file
    }

    fun shareIntent(file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ChargeMate诊断导出")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(intent, "导出诊断信息").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private companion object {
        val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val FILE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
