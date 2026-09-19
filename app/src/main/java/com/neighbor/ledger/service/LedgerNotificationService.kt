package com.neighbor.ledger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.neighbor.ledger.LedgerApp
import com.neighbor.ledger.R
import com.neighbor.ledger.data.TxRecord
import com.neighbor.ledger.persona.Persona
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * 后台常驻通知监听服务（第五章第 1 条）。
 *
 * 职责：监听系统通知 → 交给统一解析层 → 成功则入库 + 写日志 + 触发超级岛；失败则累计连续失败次数。
 * 保活：promote 为前台低耗通知（specialUse），对抗 HyperOS 杀后台；断开后自动 requestRebind 重试。
 */
class LedgerNotificationService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val repo get() = (application as LedgerApp).repository
    private val parseManager get() = (application as LedgerApp).parseManager
    private val prefs get() = (application as LedgerApp).prefs
    private val config get() = (application as LedgerApp).config

    override fun onCreate() {
        super.onCreate()
        promoteToForeground()
        // 服务起来先对齐一次月度初始化 / 当日限额。
        scope.launch { repo.onTick(LocalDateTime.now()) }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        promoteToForeground()
        scope.launch { repo.onTick(LocalDateTime.now()) }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // 自动重试：重新请求系统绑定；失败则由 ServiceGuardWorker 兜底引导到权限页。
        runCatching { requestRebind(android.content.ComponentName(this, LedgerNotificationService::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        // 空闲休眠要求：仅在收到通知时才真正执行解析 / 写盘 / 入库。
        scope.launch { handle(sbn) }
    }

    private suspend fun handle(sbn: StatusBarNotification) {
        val now = LocalDateTime.now()
        repo.onTick(now)

        val parsed = parseManager.parse(sbn)
        if (parsed != null) {
            when (parsed.type) {
                "balance" -> repo.applyBalance(parsed.channel, parsed.amount, parsed.rawText, parsed.timestamp)
                "expense" -> {
                    // 超级岛触发判定：这笔支出「之前」是否已超额（今日累计消费 > 今日限额）。
                    val today = java.time.LocalDate.now()
                    val alreadyOver = repo.todayExpense(today) > prefs.todayLimit
                    repo.applyExpense(
                        TxRecord(parsed.channel, "expense", parsed.amount, parsed.note, parsed.rawText, parsed.timestamp)
                    )
                    if (alreadyOver) {
                        SuperIslandNotifier.show(this, Persona.superIslandLine(config.config))
                    }
                }
                "income" -> repo.applyIncome(
                    TxRecord(parsed.channel, "income", parsed.amount, parsed.note, parsed.rawText, parsed.timestamp)
                )
            }
            prefs.resetParseFail(parsed.channel)
        } else {
            // 只有「确实像交易通知」才计数+写日志；普通聊天/图片一律静默，避免日志被对话刷屏。
            val p = parseManager.enabledParserForPackage(sbn.packageName ?: "")
            if (p != null && looksLikeTransaction(sbn)) {
                prefs.incParseFail(p.key)
                runCatching {
                    (application as LedgerApp).logWriter.append(
                        java.time.LocalDate.now(), p.key, "unparsed", 0.0, parseManager.rawText(sbn)
                    )
                }
            }
        }
    }

    /** 粗判是否交易通知：含“微信支付”或货币符号，或命中交易语境词；否则视为聊天、不写日志。 */
    private fun looksLikeTransaction(sbn: StatusBarNotification): Boolean {
        val raw = parseManager.rawText(sbn)
        if (raw.isBlank()) return true // 拿不到文本时按交易处理，避免漏掉诊断
        // 日报/周报/月报等汇总类通知不是单笔交易，静默忽略，避免日志刷屏与误计失败。
        if (Regex("(日报|周报|月报|报告|汇总)").containsMatchIn(raw)) return false
        return raw.contains("微信支付") || raw.contains("¥") || raw.contains("￥") ||
            Regex("(收款|付款|支付|到账|入账|转账|扣费|扣款|退款|提现|充值)").containsMatchIn(raw)
    }

    /**
     * 前台低耗常驻通知：让系统降低本服务被杀概率（第五章第 1 条）。
     * 使用 specialUse 类型 + 最低打扰频道，避免额外运行时权限。
     */
    private fun promoteToForeground() {
        val channelId = "service_guard"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "后台监听保活", NotificationManager.IMPORTANCE_MIN).apply {
                        setShowBadge(false)
                        setSound(null, null)
                        enableVibration(false)
                        lockscreenVisibility = Notification.VISIBILITY_SECRET
                    }
                )
            }
        }

        val n = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_ledger)
            .setContentTitle(getString(R.string.notification_listener_label))
            .setContentText(getString(R.string.service_running_hint))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        runCatching {
            startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
    }

    companion object {
        private const val FG_ID = 1001
    }
}