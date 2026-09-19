package com.neighbor.ledger.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.neighbor.ledger.LedgerApp
import com.neighbor.ledger.R
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * 月度初始化任务（第二章第 1 条）：每日后台检查，命中「1 号且已过 12 点」才执行。
 * 由于 WorkManager 周期任务不做精确时点调度，App 启动 / 服务收到通知也会各对齐一次，保证不遗漏。
 */
class MonthlyInitWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            (applicationContext as LedgerApp).repository.onTick(LocalDateTime.now())
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/**
 * 服务保活兜底：检测通知监听权限，被系统杀掉 / 关闭时发引导通知跳转权限页（第七章第 3 条）。
 */
class ServiceGuardWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!isListenerEnabled(ctx)) {
            postGuidance(ctx, ctx.getString(R.string.listener_killed_hint))
        } else {
            // 服务已恢复或正在运行：确保前台保活由服务自身 onListenerConnected 完成。
            (applicationContext as? LedgerApp)?.let { app ->
                try {
                    app.repository.onTick(LocalDateTime.now())
                } catch (_: Exception) {
                }
            }
            // 尝试重新拉起监听服务（系统会在授权后自行绑定；此处 re-bind 冲突时静默忽略）。
            runCatching { requestRebindStatic(ctx) }
        }
        return Result.success()
    }

    companion object {
        fun isListenerEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)

        /** 通过发送一条隐式绑定为空的启动，无法可靠重绑；仅作为尽力而为。 */
        private fun requestRebindStatic(context: Context) {
            // 无静态 API 可跨进程触发 rebind；授权校验交给通知监听设置页。
        }

        fun postGuidance(context: Context, text: String) {
            val channelId = "diagnostics"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(channelId) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(channelId, "运行诊断", NotificationManager.IMPORTANCE_DEFAULT)
                    )
                }
            }
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_stat_ledger)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(2001, n) }
        }
    }
}

/** 开机 / 应用升级广播：重新调度后台任务（月度初始化 + 服务保活兜底）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WorkScheduler.schedule(context)
    }
}

/** 后台任务调度入口。 */
object WorkScheduler {
    fun schedule(context: Context) {
        val app = context.applicationContext
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            "monthly_init",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MonthlyInitWorker>(1, TimeUnit.DAYS).build()
        )
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            "service_guard",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ServiceGuardWorker>(1, TimeUnit.DAYS).build()
        )
    }
}