package com.neighbor.ledger.parser

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.neighbor.ledger.config.ChannelConfig
import com.neighbor.ledger.config.ChannelKey
import com.neighbor.ledger.config.ConfigManager

/**
 * 统一抽象通知解析层（第一章第 3 条、第五章第 2 条）。
 *
 * 设计目标：
 * - 渠道开关、正则、文案全部外置 JSON；开启新渠道不重编译 APK、不改程序骨架。
 * - 未启用渠道的代码分支（[enabled]==false）在 [parse] 入口即短路退出，不消耗性能（空闲休眠要求）。
 */

/** 一次解析产物（与 UI / 数据层解耦的中性模型）。 */
data class ParsedNotification(
    val channel: String,
    val type: String,        // "income" | "expense" | "balance"
    val amount: Double,
    val note: String,
    val rawText: String,
    val timestamp: Long
)

/**
 * 通知解析抽象基类。
 *
 * 扩展点说明：子类只需重写 [customDirection] 即可为某渠道叠加特殊判定逻辑，
 * 默认逻辑 = 金额正则 + 收支关键词 + 余额关键词（全部来自外置 JSON）。
 */
abstract class PaymentNotificationParser(
    val key: String,
    private val cfgProvider: () -> ChannelConfig?
) {
    protected val cfg: ChannelConfig? get() = cfgProvider()

    val enabled: Boolean get() = cfg?.enabled == true
    val name: String get() = cfg?.name?.ifBlank { key } ?: key
    val packageName: String get() = cfg?.packageName ?: ""

    /** 统一入口：未启用即短路；packageName 不匹配即短路。 */
    fun parse(sbn: StatusBarNotification): ParsedNotification? {
        val c = cfg ?: return null
        if (!c.enabled) return null
        if (c.packageName.isNotBlank() && sbn.packageName != c.packageName) return null
        val text = notificationText(sbn)
        if (text.isBlank()) return null
        return parseText(text, sbn.postTime)
    }

    /** 解析正文。默认实现覆盖「余额 → 收入 → 支出」判定；子类可重写。 */
    protected open fun parseText(text: String, timestamp: Long): ParsedNotification? {
        val c = cfg ?: return null
        val amount = amountIn(text)

        // 余额读数优先：余额通知可能不含明确收支方向。
        val isBalance = matchesAny(text, c.balancePatterns) || hasAny(text, c.balanceKeywords)
        if (isBalance && amount != null) {
            return ParsedNotification(key, "balance", amount, name, text, timestamp)
        }

        val custom = customDirection(text)
        val direction = custom ?: when {
            matchesAny(text, c.incomePatterns) || hasAny(text, c.incomeKeywords) -> "income"
            matchesAny(text, c.expensePatterns) || hasAny(text, c.expenseKeywords) -> "expense"
            else -> null
        } ?: return null

        // 金额兜底：收入通知未带金额（如微信红包）时仍生成“空条目”（金额 0），便于手动补录；支出无金额则丢弃。
        val resolved = amount ?: if (direction == "income") 0.0 else return null
        val note = if (amount == null) "（待补金额）${summary(text)}" else summary(text)

        return ParsedNotification(key, direction, resolved, note, text, timestamp)
    }

    /** 预留：渠道自定义方向判定的扩展点（默认 null）。 */
    protected open fun customDirection(text: String): String? = null

    protected fun amountIn(text: String): Double? {
        val patterns = buildList {
            cfg?.amountPattern?.takeIf { it.isNotBlank() }?.let(::add)
            add("[¥￥]\\s*(\\d+(?:\\.\\d{1,2})?)")
            add("(\\d+(?:\\.\\d{1,2})?)\\s*元")
            // 兜底：无 ¥/元 但出现明显收支语境时，识别“25.00”这类纯小数金额。
            if (Regex("(支付|收款|付款|消费|到账|入账|转入|转出|提现|余额|退款|红包|扣款|扣费)").containsMatchIn(text)) {
                add("(\\d{1,9}\\.\\d{2})")
            }
        }
        for (p in patterns) {
            try {
                val m = Regex(p).find(text) ?: continue
                val v = m.groupValues.getOrNull(1)?.toDoubleOrNull()
                if (v != null) return v
            } catch (_: Exception) {
            }
        }
        return null
    }

    protected fun hasAny(text: String, keywords: List<String>): Boolean =
        keywords.any { it.isNotBlank() && text.contains(it) }

    protected fun matchesAny(text: String, patterns: List<String>): Boolean =
        patterns.any { p ->
            if (p.isBlank()) false
            else try {
                Regex(p).containsMatchIn(text)
            } catch (_: Exception) {
                false
            }
        }

    protected fun summary(text: String): String =
        text.replace(Regex("\\s+"), " ").trim().take(40).ifBlank { name }

    private fun extractText(sbn: StatusBarNotification): String = notificationText(sbn)
}

/** 微信零钱解析（默认启用）。 */
class WeChatChangeParser(cfgProvider: () -> ChannelConfig?) :
    PaymentNotificationParser(ChannelKey.WECHAT, cfgProvider) {
    override fun customDirection(text: String): String? =
        // 提现/转出属支出：即使文案里带“到账/转账”等词，也优先判支出，避免把提现误判成收入。
        if (Regex("(提现|转出)").containsMatchIn(text)) "expense" else null
}

/** 预留扩展 1：支付宝消费 / 余额解析链路（由外置 JSON 开关激活，默认封存关闭）。 */
class AliPayParser(cfgProvider: () -> ChannelConfig?) :
    PaymentNotificationParser(ChannelKey.ALIPAY, cfgProvider) {
    override fun customDirection(text: String): String? {
        // 预留扩展点：可在此叠加支付宝专有文案判定。默认沿用基类关键词。
        return null
    }
}

/** 预留扩展 2：微信零钱通余额读取链路（默认封存关闭）。 */
class WeChatMoneyFundParser(cfgProvider: () -> ChannelConfig?) :
    PaymentNotificationParser(ChannelKey.WECHAT_FUND, cfgProvider) {
    override fun customDirection(text: String): String? {
        return null
    }
}

/** 预留扩展 3：通用其他支付渠道插槽（默认封存关闭）。 */
class OtherChannelParser(cfgProvider: () -> ChannelConfig?) :
    PaymentNotificationParser(ChannelKey.OTHER, cfgProvider) {
    override fun customDirection(text: String): String? {
        return null
    }
}

/**
 * 解析编排器：按序尝试所有解析器，返回第一个成功结果。
 * 渠道开关读取自 [ConfigManager] 动态配置，导入 JSON 后即时生效。
 */
class NotificationParseManager(private val config: ConfigManager) {

    private val parsers: List<PaymentNotificationParser> = listOf(
        WeChatChangeParser { config.channel(ChannelKey.WECHAT) },
        AliPayParser { config.channel(ChannelKey.ALIPAY) },
        WeChatMoneyFundParser { config.channel(ChannelKey.WECHAT_FUND) },
        OtherChannelParser { config.channel(ChannelKey.OTHER) }
    )

    fun parse(sbn: StatusBarNotification): ParsedNotification? {
        for (p in parsers) {
            val r = p.parse(sbn)
            if (r != null) return r
        }
        return null
    }

    /** 已启用且 packageName 匹配的解析器（用于「通知来自已启用渠道却解析失败」计数）。 */
    fun enabledParserForPackage(pkg: String): PaymentNotificationParser? =
        parsers.firstOrNull { it.enabled && it.packageName.isNotBlank() && it.packageName == pkg }

    /** 原始通知文本（诊断用：解析失败时写入日志，便于核对微信实际文案）。 */
    fun rawText(sbn: StatusBarNotification): String = notificationText(sbn)
}

/**
 * 从一条系统通知里尽量完整地挖文本：普通 extras + 锁屏公开版 publicVersion.extras + tickerText，
 * 覆盖微信 8.0.x 自定义 / InboxStyle 多行通知。
 */
fun notificationText(sbn: StatusBarNotification): String {
    val parts = ArrayList<String>()
    fun grab(src: android.os.Bundle?) {
        if (src == null) return
        listOf(
            src.getCharSequence(Notification.EXTRA_TITLE),
            src.getCharSequence(Notification.EXTRA_TITLE_BIG),
            src.getCharSequence(Notification.EXTRA_TEXT),
            src.getCharSequence(Notification.EXTRA_BIG_TEXT),
            src.getCharSequence(Notification.EXTRA_SUB_TEXT),
            src.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" ") ?: ""
        ).forEach { s ->
            val v = s?.toString()?.trim()
            if (!v.isNullOrBlank()) parts.add(v)
        }
    }
    val n = sbn.notification
    grab(n?.extras)
    grab(n?.publicVersion?.extras)
    n?.tickerText?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(parts::add)
    return parts.distinct().joinToString(" ")
}