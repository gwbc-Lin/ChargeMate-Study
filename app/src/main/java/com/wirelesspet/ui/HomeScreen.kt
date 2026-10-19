package com.wirelesspet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.wirelesspet.ui.theme.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wirelesspet.data.AutomationMode
import com.wirelesspet.data.UserSettings
import com.wirelesspet.platform.CapabilityDetector
import com.wirelesspet.policy.PresentationSource
import com.wirelesspet.power.ChargingStatus
import com.wirelesspet.power.PowerSnapshot
import com.wirelesspet.power.PowerType
import com.wirelesspet.power.ThermalLevel
import com.wirelesspet.presentation.ActiveHost
import com.wirelesspet.session.SuppressionReason
import com.wirelesspet.session.WirelessSessionState

/**
 * 首页（设计文档 §7.1）。
 *
 * 状态文案必须反映真实结果，而不是"设置已保存"：
 * 电源类型、会话阶段、本次是否被抑制、监听服务与宿主是否真的可见都在这里分别显示。
 */
@Composable
fun HomeScreen(
    settings: UserSettings,
    session: WirelessSessionState,
    power: PowerSnapshot?,
    automationRunning: Boolean,
    host: ActiveHost?,
    report: CapabilityDetector.Report,
    dreamAvailable: Boolean,
    thermal: ThermalLevel,
    /** 上一次自动展示被系统拦截的墙上时间；非空时显示授权引导。 */
    launchBlockedAt: Long?,
    onToggleAutomation: (Boolean) -> Unit,
    onModeChange: (AutomationMode) -> Unit,
    onPreview: () -> Unit,
    onTestAutoStart: () -> Unit,
    onResume: () -> Unit,
    onCharacterChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenOnboarding: () -> Unit,
    onCapabilityAction: (CapabilityDetector.Action) -> Unit,
    onOpenBackgroundPopup: () -> Unit,
    onDismissLaunchBlocked: () -> Unit,
) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val heroHeight = if (configuration.screenWidthDp > configuration.screenHeightDp)
        (configuration.screenHeightDp * 0.92f).coerceIn(240f, 438f).dp else 438.dp
    val importedCharacters by com.wirelesspet.pet.CharacterCatalog.imported.collectAsState()
    val characters = remember(importedCharacters) { com.wirelesspet.pet.CharacterCatalog.all }
    val character = com.wirelesspet.pet.CharacterCatalog.find(settings.selectedCharacterId)
    var wardrobe by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var previewId by remember { mutableStateOf(character.id) }
    if (wardrobe) CharacterPicker(character.id, { onCharacterChange(it); wardrobe = false }, { wardrobe = false }, initialPreview = previewId)
    if (importing) CharacterImport({ id -> importing = false; previewId = id; wardrobe = true }, { importing = false })
    val wireless = power?.type == PowerType.WIRELESS
    var showDetails by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("充电时刻", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onOpenSettings) { Text("设定  ›", color = PetMint) }
        }

        Box(Modifier.fillMaxWidth().height(heroHeight).clip(RoundedCornerShape(32.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF30243F), Color(0xFF191A2D), Color(0xFF141521))))
            .border(1.dp, Color(0xFF52425E).copy(alpha = 0.5f), RoundedCornerShape(32.dp))) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color(0xFFBE9DEF).copy(alpha = .08f), size.width * .43f, Offset(size.width * .5f, size.height * .44f))
                drawCircle(Color(0xFFF4C3D7).copy(alpha = .045f), size.width * .58f, Offset(size.width * .5f, size.height * .44f))
                listOf(.12f to .28f, .86f to .38f, .18f to .67f, .8f to .72f, .74f to .17f).forEach { (x, y) ->
                    val center = Offset(size.width * x, size.height * y)
                    drawLine(Color(0xFFCDB6EC).copy(alpha = .5f), center - Offset(5f,0f), center + Offset(5f,0f), 1.5f)
                    drawLine(Color(0xFFCDB6EC).copy(alpha = .5f), center - Offset(0f,5f), center + Offset(0f,5f), 1.5f)
                }
            }
            CharacterStage(Modifier.fillMaxSize().padding(top = if (configuration.screenWidthDp > configuration.screenHeightDp) 62.dp else 82.dp,
                bottom = if (configuration.screenWidthDp > configuration.screenHeightDp) 44.dp else 70.dp), thermal, character.id, enabled = !wardrobe && !importing)
            Column(Modifier.align(Alignment.TopStart).padding(22.dp)) {
                Text(character.name, color = Color(0xFFF0E4FF), fontSize = 22.sp, fontWeight = FontWeight.Light, letterSpacing = 2.sp)
                Text(character.style, color = Color(0xFFB7A4C7), fontSize = 11.sp)
            }
            Text(when (character.kind) { "png" -> "PNG"; "spine" -> "SPINE"; else -> "LIVE2D" }, Modifier.align(Alignment.TopEnd).padding(22.dp)
                .background(Color.White.copy(alpha = .07f), RoundedCornerShape(20.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                color = PetPeach, fontSize = 9.sp, letterSpacing = 1.sp)
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF151521))))
                .padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(power?.let(com.wirelesspet.power.BatteryCaption::message) ?: "未连接充电器", fontSize = 13.sp, color = PetOnSurface)
                }
                TextButton(onClick = onPreview) { Text("全屏显示  ↗", color = PetMint, fontSize = 12.sp) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { previewId = character.id; wardrobe = true }, modifier = Modifier.weight(1f)) { Text("选择角色 · ${characters.size}") }
            OutlinedButton(onClick = { importing = true }, modifier = Modifier.weight(1f)) { Text("导入素材") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(modifier = Modifier.weight(1f)) {
                Text(if (wireless) "无线充电中" else "等待无线充电", color = if (wireless) PetGood else PetOnSurfaceSoft, fontSize = 11.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(power?.levelPercent?.takeIf { it >= 0 }?.toString() ?: "—", fontSize = 32.sp,
                        fontWeight = FontWeight.Light, color = PetOnSurface)
                    Text(" %", color = PetOnSurfaceSoft, modifier = Modifier.padding(bottom = 5.dp))
                }
            }
            SectionCard(modifier = Modifier.weight(1f)) {
                Text("运行状态", color = PetOnSurfaceSoft, fontSize = 11.sp)
                Text(when { session.isSuppressed -> "已暂停"; automationRunning -> "自动显示已开启";
                    settings.automationEnabled && settings.automationMode == AutomationMode.DREAM -> "等待闲置"; else -> "手动模式" },
                    fontSize = 18.sp, color = PetMint, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
            }
        }
        SectionCard {
            ToggleRow("充电时自动显示", settings.automationEnabled, onToggleAutomation,
                subtitle = if (settings.wiredChargingEnabled) "无线或有线充电时显示，断开后退出" else "无线充电时显示，断开后退出")
            Divider()
            Text("启动方式：${settings.automationMode.label} · 可在设定中调整", style = MaterialTheme.typography.bodySmall, color = PetOnSurfaceSoft)
            if (session.isActiveSession && session.isSuppressed) {
                Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text("恢复显示") }
            }
        }
        if (launchBlockedAt != null) {
            SectionCard(title = "后台启动受限", subtitle = "上次后台启动未成功，请检查系统授权后再试。") {
                Text("在其他权限中允许「后台弹出界面」；锁屏时展示还需要「锁屏显示」。如仍受限，可选择开启「显示在其他应用上层」。",
                    style = MaterialTheme.typography.bodySmall, color = PetOnSurfaceSoft)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenBackgroundPopup, modifier = Modifier.weight(1f)) { Text("其他权限", fontSize = 12.sp) }
                    OutlinedButton(onClick = { onCapabilityAction(CapabilityDetector.Action.OpenOverlaySettings) }, modifier = Modifier.weight(1f)) { Text("后台启动增强", fontSize = 12.sp) }
                }
                TextButton(onClick = onDismissLaunchBlocked) { Text("收起提示") }
            }
        }
        if (thermal >= ThermalLevel.SEVERE) {
            Text("设备温度较高，已减少动画。", color = PetWarning, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (showDetails) "收起运行详情  ⌃" else "运行详情与自动启动测试  ⌄", color = PetOnSurfaceSoft, fontSize = 11.sp)
        }
        if (showDetails) {
            SectionCard(title = "运行详情") {
                StatusRow("电源", power?.type?.label ?: "未知")
                StatusRow("会话", session.describe())
                StatusRow("本次抑制", session.suppression.label)
                StatusRow("监听", if (automationRunning) "运行中" else "未运行")
                StatusRow("宿主", if (host?.visible == true) "已展示" else "未展示")
                OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("运行诊断与日志") }
                OutlinedButton(onClick = onTestAutoStart, modifier = Modifier.fillMaxWidth()) { Text("测试自动启动") }
                TextButton(onClick = onOpenOnboarding) { Text("重新查看首次引导") }
            }
        }
        Text("离线运行 · 素材与许可见设置。", color = PetOnSurfaceSoft.copy(alpha = .65f),
            fontSize = 10.sp, lineHeight = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
    }
}

/** 供状态行复用的"已充满"判断，避免各页面各写一份。 */
fun PowerSnapshot?.isFullyCharged(): Boolean =
    this?.status == ChargingStatus.FULL || this?.levelPercent == 100

private fun formatBlockedAt(wallMs: Long): String {
    val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    return java.time.Instant.ofEpochMilli(wallMs)
        .atZone(java.time.ZoneId.systemDefault())
        .format(formatter)
}

@Composable
fun PreviewHintRow(source: PresentationSource) {
    Text(
        text = "当前预览来源：${source.label}",
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Normal,
    )
}
