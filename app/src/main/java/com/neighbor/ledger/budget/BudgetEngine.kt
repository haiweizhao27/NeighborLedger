package com.neighbor.ledger.budget

import com.neighbor.ledger.data.Bill
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * 核心预算数学规则（第二章）——本文件是唯一且不可修改的计算逻辑来源。
 *
 * 规则：
 * 1. 每月 1 号 12 点扣 300 定期存款（由 [BillRepository.applyMonthlyDeposit] 落地），本类只负责纯计算。
 * 2. 当日限额 = 当前可用总余额 ÷ 当月剩余天数；除法结果保留 2 位小数，向下取整。
 * 3. 可用额度 + 当日累计消费 = 当日限额（可用额度 = 当日限额 - 当日累计消费）。
 * 4. 总预测日均 = 当月可支配总预算 ÷ 当月总天数；周期平均消费 = 本月累计支出 ÷ 本月已过天数。
 */
object BudgetEngine {

    /**
     * 保留 2 位小数，向下取整。
     * 严格按规则语句「保留 2 位小数，向下取整」实现：720/19 = 37.8947… → 37.89。
     * （需求文档示例写做 ≈37.80，系按 1 位小数向下取整的示意，与本规则语句不一致；此处以规则语句为准。）
     */
    fun floor2(value: Double): Double {
        if (value.isNaN() || value.isInfinite()) return 0.0
        return Math.floor(value * 100.0 + 1e-9) / 100.0
    }

    /**
     * 当月剩余天数（含当日）。用于「当日限额 = 余额 ÷ 当月剩余天数」。
     * 例：9 月 11 号 → 30 - 11 + 1 = 20 天（11..30），800/20 = 40 ✓。
     */
    fun remainingDaysInMonth(date: LocalDate): Int =
        date.lengthOfMonth() - date.dayOfMonth + 1

    /** 当日限额 = 余额 ÷ 当月剩余天数，2 位小数向下取整。 */
    fun computeDailyLimit(balance: Double, date: LocalDate): Double {
        val rem = remainingDaysInMonth(date)
        if (rem <= 0 || balance < 0) return 0.0
        return floor2(balance / rem)
    }

    /** 总预测日均 = 当月可支配总预算 ÷ 当月总天数。 */
    fun predictedDaily(budgetBase: Double, date: LocalDate): Double {
        val days = date.lengthOfMonth()
        if (days <= 0) return 0.0
        return budgetBase / days
    }

    /** 周期平均消费 = 本月累计支出 ÷ 本月已过天数（含选中日）。 */
    fun periodAverage(bills: List<Bill>, date: LocalDate): Double {
        val spent = monthExpenseUpTo(bills, date)
        val elapsed = date.dayOfMonth.coerceAtLeast(1)
        return spent / elapsed
    }

    fun monthExpenseUpTo(bills: List<Bill>, date: LocalDate): Double {
        val ym = YearMonth.from(date)
        val start = ym.atDay(1)
        return bills.filter { it.type == "expense" && !it.isDeposit }
            .filter { val t = toDate(it.timestamp); !t.isBefore(start) && !t.isAfter(date) }
            .sumOf { it.amount }
    }

    fun dayExpense(bills: List<Bill>, date: LocalDate): Double =
        bills.filter { it.type == "expense" && !it.isDeposit && isSameDay(it.timestamp, date) }
            .sumOf { it.amount }

    fun dayIncome(bills: List<Bill>, date: LocalDate): Double =
        bills.filter { it.type == "income" && isSameDay(it.timestamp, date) }
            .sumOf { it.amount }

    /** 过去日期卡片①的「当日总消费（收入 - 支出净额）」。 */
    fun dayNet(bills: List<Bill>, date: LocalDate): Double =
        dayIncome(bills, date) - dayExpense(bills, date)

    /** 当日期末余额参考值 = 当前余额 - 该日之后的净变动（近似回放；当前余额实时变化则以 prefs 现值为主）。 */
    fun dayEndBalance(bills: List<Bill>, date: LocalDate, currentBalance: Double): Double {
        val after = bills.filter {
            val t = toDate(it.timestamp); t.isAfter(date)
        }
        var bal = currentBalance
        for (b in after) {
            bal += if (b.type == "expense") b.amount else -b.amount
        }
        return bal
    }

    fun isSameDay(timestamp: Long, date: LocalDate): Boolean = toDate(timestamp) == date

    fun toDate(timestamp: Long): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()

    // ---------------- 四色 / 日历色 ----------------

    /** 周期平均消费相对总预测日均的档位（用于字体四色）。 */
    fun consumeLevel(periodAverage: Double, predictedDaily: Double): ConsumeLevel {
        val ratio = if (predictedDaily <= 0.0) {
            if (periodAverage > 0) 2.0 else 0.0
        } else periodAverage / predictedDaily
        return when {
            ratio < 0.9 -> ConsumeLevel.SAVING
            ratio <= 1.0 -> ConsumeLevel.NORMAL
            ratio <= 1.1 -> ConsumeLevel.EDGE
            else -> ConsumeLevel.OVER
        }
    }

    /** 日历格子三色：<=80% 绿、80%-100% 白、>100% 红。 */
    fun calendarLevel(dayExpense: Double, dayLimit: Double): DayLevel {
        if (dayLimit <= 0.0) return if (dayExpense > 0) DayLevel.RED else DayLevel.WHITE
        val ratio = dayExpense / dayLimit
        return when {
            ratio <= 0.8 -> DayLevel.GREEN
            ratio <= 1.0 -> DayLevel.WHITE
            else -> DayLevel.RED
        }
    }
}

/** 周期平均消费四档（字体颜色）。 */
enum class ConsumeLevel { SAVING, NORMAL, EDGE, OVER }

/** 日历 / 折线图三档。 */
enum class DayLevel { GREEN, WHITE, RED }