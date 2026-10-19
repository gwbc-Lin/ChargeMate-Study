package com.wirelesspet.presentation

import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat

/** Art uses the full display; hosts apply safe insets only to controls and labels. */
internal fun Window.useFullDisplay() {
    WindowCompat.setDecorFitsSystemWindows(this, false)
    attributes = attributes.apply {
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
}
