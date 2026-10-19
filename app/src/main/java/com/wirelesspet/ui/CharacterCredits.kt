package com.wirelesspet.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.wirelesspet.BuildConfig

@Composable
fun CharacterCredits(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("素材与许可") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("内置角色为项目原创 SVG 图形，使用 MIT 许可。Spine 运行库版权归 Esoteric Software，" +
                "本版本包含运行库二进制，每位用户都必须取得自己的有效 Spine Editor 许可，完整条款见 licenses 目录。" +
                "Live2D 运行库不在公开仓库中，需要自行按 Live2D 条款准备。用户导入的模型与素材只保存在本机，" +
                "使用和再分发需遵守原作者授权。\n\n" +
                "第三方依赖保留各自许可，项目源码许可不替代第三方条款。完整说明见仓库 THIRD_PARTY_NOTICES.md 和 licenses 目录。")
            if (BuildConfig.LIVE2D_ENABLED) {
                Text("Live2D 为开发集成，发行授权待发布者另行补齐；不能用 Spine 的逐用户许可条件替代。")
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
