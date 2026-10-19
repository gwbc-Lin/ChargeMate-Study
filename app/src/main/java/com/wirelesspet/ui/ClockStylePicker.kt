package com.wirelesspet.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wirelesspet.data.ClockStyle

@Composable
fun ClockStylePicker(selected: ClockStyle, onSelect: (ClockStyle) -> Unit) {
    ClockStyle.entries.forEach { style ->
        val active = style == selected
        Surface(onClick = { onSelect(style) }, modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = Color(0xFF171A28),
            border = BorderStroke(if (active) 2.dp else 1.dp,
                if (active) MaterialTheme.colorScheme.primary else Color(0xFF36384A))) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("12:48", style = TextStyle(
                    brush = Brush.linearGradient(style.colors.map { Color(it) }),
                    fontSize = 36.sp, fontWeight = FontWeight(style.weight),
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = (-0.5).sp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(style.label, color = Color(0xFFE6E9F4))
                    if (active) Text("✓ 已选", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
