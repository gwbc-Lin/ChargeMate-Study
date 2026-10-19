package com.wirelesspet.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wirelesspet.MainActivity
import com.wirelesspet.R
import com.wirelesspet.presentation.PetHostIntents
import com.wirelesspet.policy.PresentationSource
/**
 * 通知渠道与通知内容。
 *
 * 只承担两类用途：
 * 1. 前台监听服务的持续运行状态（低优先级、不可绕过用户）；
 * 2. 无法直接展示时的可点击入口（普通优先级，不使用全屏 Intent 伪装来电/闹钟）。
 */
class AutomationNotifications(private val context: Context) {

    private val manager: NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        val nm = manager ?: return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_STATUS,
                    "自动化运行状态",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "显示ChargeMate的监听状态与手动入口"
                    setShowBadge(false)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_READY,
                    "充电显示入口",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "无法直接展示时的可点击提示"
                    setShowBadge(false)
                },
            )
        }
    }

    fun statusNotification(state: StatusState): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification_charge)
            .setColor(ContextCompat.getColor(context, R.color.pet_mint))
            .setContentTitle(state.title)
            .setContentText(state.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(state.detail.ifBlank { state.text }))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(mainActivityIntent())

        builder.addAction(
            0,
            "停止自动化",
            serviceIntent(AutomationService.ACTION_STOP),
        )
        if (state.canResume) {
            builder.addAction(0, "继续展示", serviceIntent(AutomationService.ACTION_RESUME))
        }
        if (state.showPreview) {
            builder.addAction(
                0,
                "预览",
                PendingIntent.getActivity(
                    context,
                    REQUEST_PREVIEW,
                    PetHostIntents.preview(context),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        return builder.build()
    }

    fun updateStatus(state: StatusState) {
        val nm = manager ?: return
        runCatching { nm.notify(NOTIFICATION_STATUS, statusNotification(state)) }
    }

    fun cancelStatus() {
        runCatching { manager?.cancel(NOTIFICATION_STATUS) }
    }

    /**
     * 后台启动被拦截 / 只提示不打断时的降级入口。
     *
     * `onlyAlertOnce`：同一个通知 id 反复投递时不重复响铃与震动，
     * 对应设计文档"不刷通知"的约束。
     */
    fun postChargingReady(message: String, sessionId: Long?) {
        val nm = manager ?: return
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_READY,
            PetHostIntents.fromUserResume(context, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_READY)
            .setSmallIcon(R.drawable.ic_notification_charge)
            .setColor(ContextCompat.getColor(context, R.color.pet_mint))
            .setContentTitle("ChargeMate")
            .setContentText(message)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .build()
        runCatching { nm.notify(NOTIFICATION_READY, notification) }
    }

    fun cancelChargingReady() {
        runCatching { manager?.cancel(NOTIFICATION_READY) }
    }

    /**
     * 通知此刻是否真的能显示。
     *
     * 只查 POST_NOTIFICATIONS 不够：HyperOS 等 ROM 会在系统层单独关闭应用的
     * 通知 uid 开关，权限是 granted 但通知仍然发不出去（M1 真机实测到该状态）。
     */
    fun isUsable(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val nm = manager ?: return false
        val channel = runCatching { nm.getNotificationChannel(CHANNEL_READY) }.getOrNull()
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun mainActivityIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_MAIN,
        Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun serviceIntent(action: String): PendingIntent = PendingIntent.getService(
        context,
        action.hashCode(),
        Intent(context, AutomationService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    data class StatusState(
        val title: String,
        val text: String,
        val detail: String = "",
        val canResume: Boolean = false,
        val showPreview: Boolean = false,
        val source: PresentationSource = PresentationSource.CHARGING_SESSION,
    )

    companion object {
        const val CHANNEL_STATUS = "automation_status"
        const val CHANNEL_READY = "charging_ready"
        const val NOTIFICATION_STATUS = 1001
        const val NOTIFICATION_READY = 1002
        const val REQUEST_MAIN = 2001
        const val REQUEST_READY = 2002
        const val REQUEST_PREVIEW = 2003
    }
}
