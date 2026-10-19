package com.wirelesspet.pet

import android.content.Context
import org.json.JSONObject

/** Offsets are fractions of the display area, so they survive density changes. */
data class ImagePlacement(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    fun bounded() = ImagePlacement(scale.coerceIn(.3f, 3f), x.coerceIn(-.75f, .75f), y.coerceIn(-.75f, .75f))
    fun json(): JSONObject = bounded().let { JSONObject().put("scale", it.scale).put("x", it.x).put("y", it.y) }
    companion object {
        private fun prefs(context: Context) = context.getSharedPreferences("image_placement", Context.MODE_PRIVATE)
        fun read(context: Context, id: String, landscape: Boolean): ImagePlacement {
            val key = "$id:${if (landscape) "landscape" else "portrait"}"
            return runCatching {
                val data = JSONObject(prefs(context).getString(key, "{}") ?: "{}")
                ImagePlacement(data.optDouble("scale", 1.0).toFloat(), data.optDouble("x", 0.0).toFloat(), data.optDouble("y", 0.0).toFloat()).bounded()
            }.getOrDefault(ImagePlacement())
        }
        fun save(context: Context, id: String, landscape: Boolean, value: ImagePlacement) {
            prefs(context).edit().putString("$id:${if (landscape) "landscape" else "portrait"}", value.json().toString()).apply()
        }
    }
}
