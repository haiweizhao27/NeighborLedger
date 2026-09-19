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
 * 小米 HyperOS 超级岛（胶囊通知）本地提醒。
 *
 * 实现方式：使用 Android 本地 Notification 体系 + 高优先级频道，HyperOS 会将其呈现为「胶囊」悬浮提醒。
 * 硬性约束：
 * - 仅本地弹窗，不联网、不上传；
 * - 不使用 SYSTEM_ALERT_WINDOW 悬浮窗（避免新增越界权限）；
 * - 不滥用通知：仅当「当日已判定超额后」每一笔新支出才触发；收入入账不触发；非超额不触发。
 */
object SuperIslandNotifier {

    private const val CHANNEL_ID = "super_island_capsule"
    private const val NOTIFY_ID = 0x5A11

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "超级岛超额提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "当日超额后每笔新支出的轻度胶囊提醒"
                    // 低打扰：不响铃、不震动，只做胶囊悬浮文案。
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    /** 弹出一条胶囊提醒（文案来自人设库，见 config.persona.superIsland）。 */
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