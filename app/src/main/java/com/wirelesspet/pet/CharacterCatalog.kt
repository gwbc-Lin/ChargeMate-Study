package com.wirelesspet.pet

import com.wirelesspet.BuildConfig

data class CompanionCharacter(
    val id: String, val name: String, val style: String,
    val kind: String = "png", val file: String = "$id/image.png",
)

/**
 * 内置角色为项目原创 SVG 图形（MIT 许可）。
 *
 * 仓库不附带任何第三方角色模型：Spine / Live2D 角色由使用者自行导入，
 * 导入与使用前需自行确认原作者授权与运行库许可。
 */
object CharacterCatalog {
    const val DEFAULT_ID = "ChargeBot"

    val imported = kotlinx.coroutines.flow.MutableStateFlow<List<CompanionCharacter>>(emptyList())

    private val builtIn = listOf(
        CompanionCharacter(
            id = "ChargeBot", name = "充电机器人", style = "ChargeBot · 原创 SVG",
            kind = "png", file = "ChargeBot/character.svg",
        ),
        CompanionCharacter(
            id = "ChargeCat", name = "充电猫", style = "ChargeCat · 原创 SVG",
            kind = "png", file = "ChargeCat/character.svg",
        ),
    )

    fun supports(kind: String): Boolean = when (kind) {
        "png" -> true
        "cubism2", "cubism4" -> BuildConfig.LIVE2D_ENABLED
        "spine" -> BuildConfig.SPINE_ENABLED
        else -> false
    }
    val modelsEnabled: Boolean get() = BuildConfig.LIVE2D_ENABLED || BuildConfig.SPINE_ENABLED
    val all: List<CompanionCharacter> get() = (builtIn + imported.value).filter { supports(it.kind) }
    fun find(id: String) = all.firstOrNull { it.id == id } ?: all.firstOrNull() ?: builtIn.first()
}
