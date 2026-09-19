package com.neighbor.ledger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.neighbor.ledger.R

/**
 * 超额本地提醒。
 *
 * 实现方式：标准 Android 本地通知 + 高优先级频道，呈现样式完全由系统决定。
 * 不依赖任何厂商专有的「岛」或胶囊形态，也不额外申请权限。
 * 硬性约束：
 * - 仅本地弹窗，不联网、不上传；
 * - 不使用 SYSTEM_ALERT_WINDOW 悬浮窗（避免新增越界权限）；
 * - 不滥用通知：仅当「当日已判定超额后」每一笔新支出才触发；收入入账不触发；非超额不触发。
 */
object OverBudgetNotifier {

    private const val CHANNEL_ID = "over_budget_reminder"
    private const val NOTIFY_ID = 0x5A11

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "超额提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "当日超额后每笔新支出的轻度提醒"
                    // 低打扰：不响铃、不震动，只做轻量文案提示。
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    /** 弹出一条超额提醒（文案来自人设库，见 config.persona.overBudget）。 */
    fun show(context: Context, line: String) {
        if (line.isBlank()) return
        // Android 13+ 未授予通知权限则静默返回（不崩溃、不强弹）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        ensureChannel(context)

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ledger)
            .setContentTitle("邻家账本")
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setColor(context.getColor(R.color.brand))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setDefaults(0)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID, notif)
        }
    }
}