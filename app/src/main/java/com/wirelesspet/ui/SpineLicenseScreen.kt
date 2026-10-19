package com.wirelesspet.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** A declaration, not an online license validation service or grant of rights. */
@Composable
fun SpineLicenseScreen(onAccepted: () -> Unit) {
    val context = LocalContext.current
    var confirmed by rememberSaveable { mutableStateOf(false) }
    val terms = remember {
        context.assets.open("live2d/licenses/Spine-Runtimes-2025-LICENSE.txt").bufferedReader().use { it.readText() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Spine 使用条件", style = MaterialTheme.typography.headlineSmall)
        Text("本版本包含 Spine Runtimes，按每位用户自行持有 Spine Editor 许可的条件提供。所有使用本版本的用户均须取得自己的有效许可；仅导入 PNG 也不免除此条件。")
        Text("本应用不出售、代购或授予 Spine 许可。若未持有许可，请退出，不要使用此版本。导入模型还需另有原作者授权。")
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://esotericsoftware.com/spine-purchase"))) }
        }) { Text("官方许可与购买入口 ↗") }
        Text(terms, style = MaterialTheme.typography.bodySmall)
        Row {
            Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
            Text("我已取得本人有效的 Spine Editor 许可，并接受以上完整条款。", modifier = Modifier.weight(1f))
        }
        Button(onClick = onAccepted, enabled = confirmed, modifier = Modifier.fillMaxWidth()) { Text("确认并继续") }
        TextButton(onClick = { (context as? Activity)?.finish() }, modifier = Modifier.fillMaxWidth()) { Text("退出") }
    }
}
