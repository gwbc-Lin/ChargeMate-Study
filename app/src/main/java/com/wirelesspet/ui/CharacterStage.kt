package com.wirelesspet.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.wirelesspet.data.UserSettings
import com.wirelesspet.pet.CharacterCatalog
import com.wirelesspet.pet.PetRenderer
import com.wirelesspet.policy.EnergyPolicy
import com.wirelesspet.policy.PresentationSource
import com.wirelesspet.power.ThermalLevel

/** 首页使用同一套真实模型，退出首页立即暂停并释放 WebGL。 */
@Composable
fun CharacterStage(modifier: Modifier = Modifier, thermal: ThermalLevel, characterId: String = CharacterCatalog.DEFAULT_ID, enabled: Boolean = true, imagePlacement: com.wirelesspet.pet.ImagePlacement? = null, immersivePreview: Boolean = false, onTap: () -> Unit = {}) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val tap by rememberUpdatedState(onTap)
    val shouldRun by rememberUpdatedState(enabled)
    val renderer = remember(context, characterId, immersivePreview) { PetRenderer(context, characterId = characterId, immersive = immersivePreview, enableSpectrum = false).apply { if (immersivePreview) clockHeaderHeightDp = 84f } }
    val plan = remember(thermal) {
        EnergyPolicy.planFor(
            UserSettings.DEFAULT.copy(energyProfile = com.wirelesspet.data.EnergyProfile.SMOOTH_PREVIEW),
            thermal, PresentationSource.CHARGING_SESSION,
        ).let { it.copy(activeFps = minOf(24, it.activeFps), idleFps = minOf(24, it.idleFps)) }
    }
    key(renderer) { AndroidView(factory = { renderer.apply { onPetTapped = { tap() }; start(plan) } },
        modifier = modifier, update = {
            it.imagePlacementOverride = imagePlacement
            it.updatePlan(plan)
            if (enabled && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) it.resume() else it.pause()
        }) }
    DisposableEffect(owner, renderer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (shouldRun) renderer.resume() else renderer.pause()
                Lifecycle.Event.ON_STOP -> renderer.pause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); renderer.release() }
    }
}
