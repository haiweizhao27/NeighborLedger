package com.neighbor.ledger.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 轻量钱包状态存储（SharedPreferences，位于 App 私有内部存储目录）。
 *
 * 只保存少量标量状态与每日限额历史，账单明细一律走 Room。
 * 金额一律以字符串保存，避免 Double/Float 的二进制精度误差累积。
 */
class WalletPrefs(context: Context) {

    private val sp = context.getSharedPreferences("wallet_state", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ---- 余额 / 预算 ----

    /** 当前可用总余额（当日限额的分子）。首次启动用 default_config.json 里的 initialBalance 播种。 */
    var totalBalance: Double
        get() = sp.getString(KEY_BALANCE, null)?.toDoubleOrNull() ?: 0.0
        set(value) = sp.edit().putString(KEY_BALANCE, value.toString()).apply()

    /** 当月真实可支配总预算 = 每月 1 号 12 点扣完 300 定期存款后的余额（当月内固定不变）。 */
    var budgetBase: Double
        get() = sp.getString(KEY_BUDGET_BASE, null)?.toDoubleOrNull() ?: 0.0
        set(value) = sp.edit().putString(KEY_BUDGET_BASE, value.toString()).apply()

    /** 已完成月度初始化（扣 300 存款）的月份，格式 yyyy-MM。 */
    var depositMonth: String?
        get() = sp.getString(KEY_DEPOSIT_MONTH, null)
        set(value) = sp.edit().putString(KEY_DEPOSIT_MONTH, value).apply()

    // ---- 当日限额（每天开始时固定一次）----

    /** 当日限额所属日期，格式 yyyy-MM-dd。 */
    var limitDate: String?
        get() = sp.getString(KEY_LIMIT_DATE, null)
        set(value) = sp.edit().putString(KEY_LIMIT_DATE, value).apply()

    /** 当日限额 = 当日开始时 可用余额 ÷ 当月剩余天数（保留 2 位小数，向下取整）。 */
    var todayLimit: Double
        get() = sp.getString(KEY_TODAY_LIMIT, null)?.toDoubleOrNull() ?: 0.0
        set(value) = sp.edit().putString(KEY_TODAY_LIMIT, value.toString()).apply()

    /** 每日限额历史 { "yyyy-MM-dd": 限额 }，用于日历 / 折线图回放。 */
    var limitHistory: Map<String, Double>
        get() {
            val raw = sp.getString(KEY_LIMIT_HISTORY, null) ?: return emptyMap()
            return try {
                val type = object : TypeToken<Map<String, Double>>() {}.type
                gson.fromJson<Map<String, Double>>(raw, type) ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
        }
        set(value) =
            sp.edit().putString(KEY_LIMIT_HISTORY, gson.toJson(value)).apply()

    // ---- 解析健康状态（用于顶部容错提示）----

    /** 该渠道连续解析失败次数（超阈值 → 「通知格式更新」提示）。 */
    fun parseFailCount(channel: String): Int = sp.getInt("parse_fail_$channel", 0)

    fun incParseFail(channel: String) {
        val c = parseFailCount(channel)
        sp.edit().putInt("parse_fail_$channel", c + 1).apply()
    }

    fun resetParseFail(channel: String) {
        sp.edit().remove("parse_fail_$channel").apply()
    }

    /** 是否成功读到过任一已启用渠道的余额（否 → 「未获取余额数据」提示）。 */
    var hasBalanceRead: Boolean
        get() = sp.getBoolean(KEY_BALANCE_READ, false)
        set(value) = sp.edit().putBoolean(KEY_BALANCE_READ, value).apply()

    /** 是否已完成首次配置播种（balance / budgetBase 初始化）。 */
    var seeded: Boolean
        get() = sp.getBoolean(KEY_SEEDED, false)
        set(value) = sp.edit().putBoolean(KEY_SEEDED, value).apply()

    private companion object {
        const val KEY_BALANCE = "total_balance"
        const val KEY_BUDGET_BASE = "budget_base"
        const val KEY_DEPOSIT_MONTH = "deposit_month"
        const val KEY_LIMIT_DATE = "limit_date"
        const val KEY_TODAY_LIMIT = "today_limit"
        const val KEY_LIMIT_HISTORY = "limit_history"
        const val KEY_BALANCE_READ = "has_balance_read"
        const val KEY_SEEDED = "seeded"
    }
}