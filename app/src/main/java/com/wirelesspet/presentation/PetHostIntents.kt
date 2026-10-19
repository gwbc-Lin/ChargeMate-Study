package com.wirelesspet.presentation

import android.content.Context
import android.content.Intent
import com.wirelesspet.policy.PresentationSource
import com.wirelesspet.presentation.activity.PetActivity

/** 宠物宿主的启动契约：来源与会话 id 都通过显式 extra 传递，避免隐式广播。 */
object PetHostIntents {

    const val EXTRA_SOURCE = "com.wirelesspet.extra.SOURCE"
    const val EXTRA_SESSION_ID = "com.wirelesspet.extra.SESSION_ID"
    const val EXTRA_USER_INITIATED = "com.wirelesspet.extra.USER_INITIATED"

    fun forSource(
        context: Context,
        source: PresentationSource,
        sessionId: Long?,
        userInitiated: Boolean = false,
    ): Intent = Intent(context, PetActivity::class.java).apply {
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        putExtra(EXTRA_SOURCE, source.name)
        if (sessionId != null) putExtra(EXTRA_SESSION_ID, sessionId)
        if (userInitiated) putExtra(EXTRA_USER_INITIATED, true)
    }

    fun fromChargingSession(context: Context, sessionId: Long?): Intent =
        forSource(context, PresentationSource.CHARGING_SESSION, sessionId)

    /**
     * 用户主动入口（点通知、点首页"继续展示"）。
     *
     * 标记 userInitiated 后，宿主会先清除本次会话抑制再展示，
     * 不会出现"点了继续却被抑制状态立刻关掉"的情况。
     */
    fun fromUserResume(context: Context, sessionId: Long?): Intent =
        forSource(context, PresentationSource.CHARGING_SESSION, sessionId, userInitiated = true)

    fun preview(context: Context): Intent =
        forSource(context, PresentationSource.PREVIEW, sessionId = null)

    fun sourceOf(intent: Intent?): PresentationSource {
        val raw = intent?.getStringExtra(EXTRA_SOURCE) ?: return PresentationSource.CHARGING_SESSION
        return runCatching { PresentationSource.valueOf(raw) }
            .getOrDefault(PresentationSource.CHARGING_SESSION)
    }

    fun sessionIdOf(intent: Intent?): Long? =
        intent?.let { if (it.hasExtra(EXTRA_SESSION_ID)) it.getLongExtra(EXTRA_SESSION_ID, 0L) else null }

    fun isUserInitiated(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_USER_INITIATED, false) == true
}
