package com.wirelesspet.pet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import android.widget.TextView
import com.wirelesspet.WirelessPetApplication
import com.wirelesspet.data.EventLog
import com.wirelesspet.policy.EnergyPlan
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** 离线 Live2D 宿主：仅加载打包资产，不允许访问网络或任意文件。 */
@SuppressLint("SetJavaScriptEnabled")
class PetRenderer @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, characterId: String = (context.applicationContext as? WirelessPetApplication)?.graph?.settings?.value?.selectedCharacterId ?: CharacterCatalog.DEFAULT_ID, immersive: Boolean = true, enableSpectrum: Boolean = true) : FrameLayout(context, attrs) {
    private val spectrum = if (immersive && enableSpectrum) AudioSpectrumView(context) { log(it) } else null
    private var character = CharacterCatalog.find(characterId)
    private var ready = false
    private var running = false
    private var paused = false
    private var released = false
    private var attached = false
    private var plan: EnergyPlan? = null
    private var fullyCharged = false
    private var sentState: String? = null
    private var badgeSpectrum: Boolean? = null
    private var safeTop = 0
    private var safeRight = 0
    private var safeBottom = 0
    private val badge = TextView(context).apply {
        setTextColor(Color.parseColor("#CFC8E9")); textSize = 12f
        setPadding(24, 12, 24, 12); gravity = Gravity.CENTER; visibility = View.GONE
    }
    private val feedback = TextView(context).apply {
        text = "正在加载 ${character.name}…"; textSize = 13f
        setTextColor(Color.parseColor("#BDB3D1")); gravity = Gravity.CENTER
    }
    private val web = WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.setGeolocationEnabled(false)
        settings.blockNetworkLoads = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.mediaPlaybackRequiresUserGesture = true
        isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
        addJavascriptInterface(Bridge(), "PetBridge")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val path = assetPath(request.url) ?: return blocked()
                return try {
                    if (path == "live2d/character.json") {
                        val bytes = JSONObject().put("id", character.id).put("kind", character.kind)
                            .put("clockStyle", (context.applicationContext as WirelessPetApplication).graph.settings.value.clockStyle.cssName)
            .put("landscapeLayout", (context.applicationContext as WirelessPetApplication).graph.settings.value.landscapeLayout.cssName)
                            .put("url", character.file).toString().toByteArray(Charsets.UTF_8)
                        return WebResourceResponse("application/json", "UTF-8", ByteArrayInputStream(bytes))
                    }
                    if (path.startsWith("live2d/user/")) {
                        val stream = (context.applicationContext as WirelessPetApplication).graph.characterStore
                            .open(path.removePrefix("live2d/user/")) ?: return blocked()
                        return WebResourceResponse(mime(path), null, stream)
                    }
                    WebResourceResponse(mime(path), "UTF-8", context.assets.open(path))
                } catch (_: Exception) {
                    blocked()
                }
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) log("Live2D: ${message.message().take(240)}")
                return true
            }
        }
    }
    var clockHeaderHeightDp = 0f
        set(value) { field = value; sync() }
    var imagePlacementOverride: ImagePlacement? = null
        set(value) { field = value; sync() }
    var onPetTapped: (() -> Unit)? = null
    var onCharacterChanged: ((String) -> Unit)? = null

    private fun stageUrl(immersive: Boolean): String {
        val settings = (context.applicationContext as WirelessPetApplication).graph.settings.value
        return Uri.parse("https://local.wirelesspet/live2d/index.html").buildUpon()
            .appendQueryParameter("character", character.id)
            .appendQueryParameter("immersive", immersive.toString())
            .appendQueryParameter("clockTopInset", (safeTop / resources.displayMetrics.density + clockHeaderHeightDp).toString())
            .appendQueryParameter("clockStyle", settings.clockStyle.cssName)
            .appendQueryParameter("landscapeLayout", settings.landscapeLayout.cssName)
            .build().toString()
    }

    private fun switchCharacter(direction: Int) {
        if (released || !running || paused) return
        val characters = CharacterCatalog.all
        if (characters.size < 2) return
        val index = characters.indexOfFirst { it.id == character.id }.coerceAtLeast(0)
        character = characters[Math.floorMod(index + direction, characters.size)]
        ready = false; sentState = null
        feedback.text = "正在加载 ${character.name}…"; feedback.visibility = View.VISIBLE
        (context.applicationContext as WirelessPetApplication).graph.updateSettings {
            it.copy(selectedCharacterId = character.id)
        }
        onCharacterChanged?.invoke(character.name)
        web.loadUrl(stageUrl(true))
    }

    init {
        addView(web, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        spectrum?.let { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        addView(feedback, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (16 * resources.displayMetrics.density).toInt()
        })
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val safe = insets.getInsetsIgnoringVisibility(
                androidx.core.view.WindowInsetsCompat.Type.displayCutout() or androidx.core.view.WindowInsetsCompat.Type.systemBars())
            safeTop = safe.top; safeRight = safe.right; safeBottom = safe.bottom
            // Keep artwork and clock centered on the physical display. Insetting the
            // WebView here shifts the entire composition away from a side camera.
            // Only the battery label needs the safe area; the spectrum stays at the edge.
            updateBadgePosition(width > height)
            sync()
            insets
        }
        feedback.setOnClickListener { if (!released) { feedback.text = "正在重新加载…"; web.reload() } }
        web.loadUrl(stageUrl(immersive))
    }

    private inner class Bridge {
        @JavascriptInterface fun ready(value: String) { post {
            if (released || value != character.id) return@post
            ready = true; sentState = null; feedback.visibility = View.GONE; sync()
            log("Live2D 角色 ${character.id} 已加载")
        } }
        @JavascriptInterface fun failed(message: String) { post {
            if (released) return@post
            ready = false; feedback.visibility = View.VISIBLE
            feedback.text = "角色加载暂时失败\n轻触重试"
            log("Live2D 加载失败：${message.take(240)}")
        } }
        @JavascriptInterface fun performance(value: String) { post { if (!released) log("Live2D 播放采样：${value.take(200)}") } }
        @JavascriptInterface fun tapped(value: String) { post { if (!released) onPetTapped?.invoke() } }
        @JavascriptInterface fun swiped(value: String) { post {
            if (value == "next") switchCharacter(1) else if (value == "previous") switchCharacter(-1)
        } }
    }

    fun start(newPlan: EnergyPlan) { plan = newPlan; running = true; paused = false; web.onResume(); sync() }
    fun pause() { if (released) return; paused = true; sync(); web.onPause() }
    fun resume() { if (released) return; paused = false; web.onResume(); sync() }
    fun stop() { if (released) return; running = false; sync(); web.onPause() }
    fun updatePlan(newPlan: EnergyPlan) { plan = newPlan; sync() }
    fun updateBatteryLabel(power: com.wirelesspet.power.PowerSnapshot?) {
        val text = power?.let(com.wirelesspet.power.BatteryCaption::badge).orEmpty()
        if (badge.text.toString() != text) badge.text = text
        badge.contentDescription = power?.describe().orEmpty()
        badge.visibility = if (power == null) View.GONE else View.VISIBLE
    }
    fun showStatusLabel(label: String) { contentDescription = "${character.name}，角色显示。$label" }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateBadgePosition(w > h)
        sync()
    }
    private fun updateBadgePosition(landscape: Boolean) {
        badge.layoutParams = (badge.layoutParams as LayoutParams).apply {
            gravity = Gravity.BOTTOM or if (landscape) Gravity.RIGHT else Gravity.CENTER_HORIZONTAL
            rightMargin = if (landscape) (24 * resources.displayMetrics.density).toInt() + safeRight else 0
            bottomMargin = (16 * resources.displayMetrics.density).toInt() + safeBottom
        }
    }
    fun notifyFullyCharged() {
        if (fullyCharged) return
        fullyCharged = true
        if (ready && !released) web.evaluateJavascript("window.pet && window.pet.celebrate()", null)
    }
    private fun sync() {
        val energy = plan ?: return
        val spectrumEnabled = (context.applicationContext as WirelessPetApplication).graph.settings.value.audioSpectrumEnabled
        if (badgeSpectrum != spectrumEnabled) {
            badgeSpectrum = spectrumEnabled
            badge.background = if (spectrumEnabled) android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#E60B0D14")); cornerRadius = 8 * resources.displayMetrics.density
            } else null
        }
        spectrum?.configure(!released && running && !paused && attached && isShown && !energy.staticOnly && energy.activeFps > 0 && energy.idleFps > 0 &&
            (context.applicationContext as WirelessPetApplication).graph.settings.value.audioSpectrumEnabled,
            minOf(energy.activeFps, energy.idleFps))
        if (!ready || released) return
        val placement = imagePlacementOverride ?: ImagePlacement.read(context, character.id, width > height)
        val data = JSONObject().put("clockTopInset", safeTop / resources.displayMetrics.density + clockHeaderHeightDp).put("imagePlacement", placement.json()).put("running", running && !paused && attached && isShown)
            .put("clockStyle", (context.applicationContext as WirelessPetApplication).graph.settings.value.clockStyle.cssName)
            .put("landscapeLayout", (context.applicationContext as WirelessPetApplication).graph.settings.value.landscapeLayout.cssName)
            .put("fps", energy.activeFps).put("staticOnly", energy.staticOnly)
            .put("idleFps", energy.idleFps).put("idleAfterMs", energy.idleAfterMs)
            .put("sleepAfterMs", energy.sleepAfterMs)
            .put("interactionEnabled", (context.applicationContext as? WirelessPetApplication)
                ?.graph?.settings?.value?.interactionEnabled ?: true)
        val state = data.toString()
        if (state != sentState) {
            sentState = state
            web.evaluateJavascript("window.pet && window.pet.state($state)", null)
        }
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (attached) sync()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; sync() }
    override fun onDetachedFromWindow() { if (!released) pause(); attached = false; super.onDetachedFromWindow() }

    fun release() {
        if (released) return
        spectrum?.release()
        stop(); web.evaluateJavascript("window.pet && window.pet.dispose()", null)
        released = true
        web.removeJavascriptInterface("PetBridge"); removeView(web)
        web.stopLoading(); web.destroy()
    }
    private fun log(message: String) {
        (context.applicationContext as? WirelessPetApplication)?.graph?.eventLog?.log(EventLog.Category.PRESENTATION, message)
    }
    companion object {
        internal fun assetPath(uri: Uri): String? = OfflineAssetPolicy.path(uri.scheme, uri.host, uri.path)
        private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
        private fun mime(path: String) = when (path.substringAfterLast('.')) {
            "html" -> "text/html"; "js" -> "application/javascript"; "json" -> "application/json"
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "svg" -> "image/svg+xml"; "wav" -> "audio/wav"; else -> "application/octet-stream"
        }
    }
}
