package com.wirelesspet.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wirelesspet.WirelessPetApplication
import com.wirelesspet.pet.CharacterCatalog
import com.wirelesspet.power.ThermalLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun CharacterPage(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
    }
}

@Composable
fun CharacterPicker(selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit, initialPreview: String = selected) {
    val context = LocalContext.current
    val graph = remember(context) { (context.applicationContext as WirelessPetApplication).graph }
    val thermal by graph.thermal.collectAsState()
    val imported by CharacterCatalog.imported.collectAsState()
    val characters = remember(imported) { CharacterCatalog.all }
    var candidate by rememberSaveable { mutableStateOf(initialPreview) }
    var editing by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("全部") }
    val current = CharacterCatalog.find(candidate)
    val visible = characters.filter { item ->
        (category == "全部" || (item.id.startsWith("user_") == (category == "已导入"))) &&
            (item.name.contains(query, true) || item.style.contains(query, true))
    }
    val configuration = LocalConfiguration.current
    val wide = configuration.screenWidthDp >= 640 && configuration.screenHeightDp >= 320
    if (editing) ImagePlacementEditor(current.id, thermal) { editing = false }
    else CharacterPage(onDismiss) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("选择角色", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = { onSelect(current.id) }) { Text("使用此角色") }
            }
            @Composable fun Preview(modifier: Modifier) {
                Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 3.dp) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CharacterStage(Modifier.weight(1f).fillMaxWidth(), thermal, current.id)
                        if (current.kind == "png" && current.id.startsWith("user_")) {
                            TextButton(onClick = { editing = true }) { Text("调整图片位置与大小") }
                        }
                        Text(current.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(10.dp))
                    }
                }
            }
            @Composable fun Library(modifier: Modifier) {
                Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("搜索角色或作品") })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("全部", "内置", "已导入").forEach { label ->
                            FilterChip(selected = category == label, onClick = { category = label }, label = { Text(label) })
                        }
                        Text("${visible.size} 位", modifier = Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelSmall)
                    }
                    if (visible.isEmpty()) {
                        Text(if (category == "已导入" && query.isBlank()) "还没有导入角色，可从首页的「导入素材」添加。" else "没有匹配的角色", modifier = Modifier.padding(16.dp))
                    }
                    LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(visible, key = { it.id }) { item ->
                            val active = candidate == item.id
                            Surface(onClick = { candidate = item.id }, shape = MaterialTheme.shapes.medium,
                                color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(item.name, style = MaterialTheme.typography.titleSmall)
                                    Text(when(item.kind) { "png" -> "PNG · 静态"; "spine" -> "Spine · 动态"; else -> "Live2D · 动态" }, style = MaterialTheme.typography.labelSmall)
                                    Text(if (active) "✓ 预览中" else if (selected == item.id) "当前使用" else if (item.id.startsWith("user_")) "本地导入" else "内置角色", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            if (wide) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Preview(Modifier.weight(.38f).fillMaxHeight())
                    Library(Modifier.weight(.62f).fillMaxHeight())
                }
            } else {
                Preview(Modifier.fillMaxWidth().height(190.dp))
                Library(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
fun CharacterImport(onImported: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { (context.applicationContext as WirelessPetApplication).graph.characterStore }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun importFile(uri: android.net.Uri?, image: Boolean) {
        if (uri == null || busy) return
        busy = true; error = null
        scope.launch {
            try {
                val character = withContext(Dispatchers.IO) { store.import(uri, image) }
                Toast.makeText(context, "已添加 ${character.name}", Toast.LENGTH_SHORT).show()
                onImported(character.id)
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                error = failure.message ?: "导入失败，请检查素材文件"
            } finally { busy = false }
        }
    }
    val pngPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importFile(it, true) }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importFile(it, false) }
    val configuration = LocalConfiguration.current
    val wide = configuration.screenWidthDp >= 640 && configuration.screenHeightDp >= 320
    CharacterPage({ if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("导入素材", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, enabled = !busy) { Text("返回") }
            }
            Text("将素材保存到本机，导入完成后可在角色库中选择。", style = MaterialTheme.typography.bodyMedium)
            @Composable fun ImageCard(modifier: Modifier = Modifier) {
            SectionCard(modifier = modifier, title = "静态立绘", subtitle = "PNG 图片") {
                Text("保留透明背景，显示静态图片。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { pngPicker.launch(arrayOf("image/png")) }, enabled = !busy) { Text("选择 PNG 文件") }
            }
            }
            @Composable fun ModelCard(modifier: Modifier = Modifier) {
            SectionCard(modifier = modifier, title = "动态角色", subtitle = "Live2D / Spine ZIP") {
                Text(if (CharacterCatalog.modelsEnabled) "每个 ZIP 只包含一个完整模型。" else "公开构建未启用动态模型运行库，请使用 PNG。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, enabled = !busy && CharacterCatalog.modelsEnabled) { Text("选择模型 ZIP") }
            }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ImageCard(Modifier.weight(1f)); ModelCard(Modifier.weight(1f))
                }
            } else { ImageCard(); ModelCard() }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在检查并导入，请稍候…") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("打包说明", style = MaterialTheme.typography.titleSmall)
            if (CharacterCatalog.modelsEnabled) Text("Live2D：模型配置和引用资源。Spine：骨骼、图集和贴图。导入说明与运行库许可见 README。", style = MaterialTheme.typography.bodySmall)
            Text("请使用你有权使用的素材。", style = MaterialTheme.typography.labelSmall)
        }
    }
}
