package com.wirelesspet.pet

import com.wirelesspet.core.MonoClock
import com.wirelesspet.policy.EnergyPlan

/** 宠物动作，与文档 §8 的动作表对应。 */
enum class PetAction(val label: String) {
    WELCOME("欢迎"),
    IDLE("待机"),
    SLEEP("休眠"),
    INTERACT("互动"),
    FULL("充满"),
    LEAVE("离场"),
}

data class PetScene(
    val action: PetAction,
    val actionStartedAtMs: Long,
    val sinceInteractionMs: Long,
)

/**
 * 宠物动作控制器：只管理"现在该演什么"，不接触 Android 窗口与资源。
 *
 * 时间全部来自单调时钟，因此系统时间变化、页面重建都不会打乱节奏。
 */
class PetSceneController(private val clock: MonoClock) {

    private var action: PetAction = PetAction.WELCOME
    private var actionStartedAtMs: Long = 0L
    private var idleSinceMs: Long = 0L
    private var interactionUntilMs: Long = 0L
    private var fullUntilMs: Long = 0L
    private var running: Boolean = false

    fun start(plan: EnergyPlan) {
        val now = clock.elapsedRealtimeMs()
        running = true
        action = PetAction.WELCOME
        actionStartedAtMs = now
        idleSinceMs = now + WELCOME_MS
        interactionUntilMs = 0L
        fullUntilMs = 0L
    }

    fun stop() {
        running = false
        action = PetAction.LEAVE
        actionStartedAtMs = clock.elapsedRealtimeMs()
    }

    fun onTap(plan: EnergyPlan) {
        val now = clock.elapsedRealtimeMs()
        if (!running || action == PetAction.LEAVE) return
        action = PetAction.INTERACT
        actionStartedAtMs = now
        interactionUntilMs = now + INTERACT_MS
        idleSinceMs = interactionUntilMs
    }

    fun onFullyCharged() {
        val now = clock.elapsedRealtimeMs()
        if (!running) return
        action = PetAction.FULL
        actionStartedAtMs = now
        fullUntilMs = now + FULL_MS
        idleSinceMs = fullUntilMs
    }

    /** 每帧（或每次可见状态检查）调用，按经过时间推进动作。 */
    fun advance(plan: EnergyPlan): PetScene {
        val now = clock.elapsedRealtimeMs()
        if (running) {
            when (action) {
                PetAction.WELCOME -> if (now - actionStartedAtMs >= WELCOME_MS) {
                    action = PetAction.IDLE
                    actionStartedAtMs = now
                }

                PetAction.INTERACT -> if (now >= interactionUntilMs) {
                    action = PetAction.IDLE
                    actionStartedAtMs = now
                }

                PetAction.FULL -> if (now >= fullUntilMs) {
                    action = PetAction.IDLE
                    actionStartedAtMs = now
                }

                PetAction.IDLE -> {
                    val idleFor = now - idleSinceMs
                    if (idleFor >= plan.idleAfterMs + plan.sleepAfterMs) {
                        action = PetAction.SLEEP
                        actionStartedAtMs = now
                    }
                }

                PetAction.SLEEP, PetAction.LEAVE -> Unit
            }
        }
        return PetScene(
            action = action,
            actionStartedAtMs = actionStartedAtMs,
            sinceInteractionMs = if (interactionUntilMs == 0L) Long.MAX_VALUE else now - (interactionUntilMs - INTERACT_MS),
        )
    }

    fun currentAction(): PetAction = action

    companion object {
        const val WELCOME_MS = 7_000L
        const val INTERACT_MS = 2_500L
        const val FULL_MS = 4_000L
    }
}
