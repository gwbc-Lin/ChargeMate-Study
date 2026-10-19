package com.wirelesspet.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wirelesspet.pet.ImagePlacement
import com.wirelesspet.power.ThermalLevel

@Composable
fun ImagePlacementEditor(id: String, thermal: ThermalLevel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var landscape by remember { mutableStateOf(false) }
    var portrait by remember(id) { mutableStateOf(ImagePlacement.read(context, id, false)) }
    var horizontal by remember(id) { mutableStateOf(ImagePlacement.read(context, id, true)) }
    val current = if (landscape) horizontal else portrait
    val latest by rememberUpdatedState(current)
    fun update(value: ImagePlacement) { if (landscape) horizontal = value.bounded() else portrait = value.bounded() }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = {
                        ImagePlacement.save(context, id, false, portrait)
                        ImagePlacement.save(context, id, true, horizontal)
                        onDismiss()
                    }) { Text("保存") }
                }
                Text("调整图片", style = MaterialTheme.typography.titleLarge)
                Text("拖动移动，双指缩放；横竖屏分别保存。放大或移动后可能超出屏幕。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!landscape, { landscape = false }, label = { Text("竖屏") })
                    FilterChip(landscape, { landscape = true }, label = { Text("横屏") })
                }
                // The overlay owns editing gestures, keeping them separate from character switching.
                Box(Modifier.fillMaxWidth().height(if (landscape) 180.dp else 280.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Box(Modifier.fillMaxHeight().aspectRatio(if (landscape) 16f / 9f else 9f / 16f).onSizeChanged { previewSize = it }) {
                        CharacterStage(Modifier.fillMaxSize(), thermal, id, imagePlacement = current, immersivePreview = true)
                        Box(Modifier.fillMaxSize().pointerInput(id, landscape) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val pan = event.calculatePan()
                                    val zoom = event.calculateZoom()
                                    if (previewSize.width > 0 && previewSize.height > 0) update(latest.copy(
                                        scale = latest.scale * zoom,
                                        x = latest.x + pan.x / previewSize.width,
                                        y = latest.y + pan.y / previewSize.height,
                                    ))
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                        })
                    }
                }
                Text("缩放 ${ (current.scale * 100).toInt() }%")
                Slider(current.scale, { update(current.copy(scale = it)) }, valueRange = .3f..3f)
                Text("左右位置")
                Slider(current.x, { update(current.copy(x = it)) }, valueRange = -.75f.. .75f)
                Text("上下位置")
                Slider(current.y, { update(current.copy(y = it)) }, valueRange = -.75f.. .75f)
                TextButton(onClick = { update(ImagePlacement()) }) { Text("重置当前方向") }
            }
        }
    }
}
