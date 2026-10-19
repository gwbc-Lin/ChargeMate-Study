package com.wirelesspet.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * 产品视觉是黑底宠物画面，设置界面也固定为深色，避免浅色主题与全屏页面割裂。
 * 不使用动态取色：宠物主色是产品标识的一部分。
 */
private val PetColorScheme = darkColorScheme(
    primary = PetMint,
    onPrimary = PetSurface,
    primaryContainer = PetMintDeep,
    onPrimaryContainer = PetOnSurface,
    secondary = PetPeach,
    onSecondary = PetSurface,
    background = PetSurface,
    onBackground = PetOnSurface,
    surface = PetSurfaceAlt,
    onSurface = PetOnSurface,
    surfaceVariant = PetSurfaceHigh,
    onSurfaceVariant = PetOnSurfaceSoft,
    error = PetDanger,
    outline = PetOnSurfaceSoft,
)

@Composable
fun WirelessPetTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PetColorScheme,
        typography = Typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ),
        content = content,
    )
}
