package com.neighbor.ledger.data

import android.content.Context
import com.neighbor.ledger.budget.BudgetEngine
import com.neighbor.ledger.config.ConfigManager
import com.neighbor.ledger.log.DailyLogWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/** 一次解析产生的交易/余额输入（数据层中性模型，与解析器解耦）。 */
data class TxRecord(
    val channel: String,
    val type: String,          // "income" | "expense" | "balance"
    val amount: Double,
    val note: String,
    val rawText: String,
    val timestamp: Long
)

/**
 * 账单仓库：统一落地「入库 Room + 写日志 + 更新钱包状态 + 刷新版本号」。
 * 变更顺序：确保月度初始化 → 确保当日限额 → 变更余额 → 写库 → 写日志 → 数据版本 +1。
 */
class BillRepository(
    private val context: Context,
    private val dao: BillDao,
    private val prefs: WalletPrefs,
    private val logWriter: DailyLogWriter,
    private val config: ConfigManager
) {

    /** 数据版本号：任何账单 / 余额 / 备注变更都 +1，用于失效折线图缓存、刷新 UI。 */
    val dataVersion = MutableStateFlow(0L)

    fun init() {
        ensureSeed()
    }

    fun observeBills(): Flow<List<Bill>> = dao.observeAll()

    suspend fun billsBetween(start: Long, end: Long): List<Bill> =
        withContext(Dispatchers.IO) { dao.between(start, end) }

    suspend fun todayExpense(date: LocalDate): Double = withContext(Dispatchers.IO) {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.atTime(LocalTime.MAX).atZone(zone).toInstant().toEpochMilli()
        dao.between(start, end).filter { it.type == "expense" && !it.isDeposit }.sumOf { it.amount }
    }

    /** 首次运行播种初始余额（来自 default_config.json / 导入配置，之后可由余额通知校准）。 */
    private fun ensureSeed() {
        if (!prefs.seeded) {
            prefs.totalBalance = config.config.initialBalance
            prefs.budgetBase = prefs.totalBalance
            prefs.seeded = true
        }
    }

    /**
     * 每次进入主流程（监听收到通知 / 定时任务 / 启动）调用一次：
     * 1) 若今天是 1 号且已过 12 点且本月未初始化 → 扣 300 定期存款；
     * 2) 若日期已翻篇 → 固定当日限额。
     */
    suspend fun onTick(now: LocalDateTime) {
        val ym = YearMonth.from(now)
        // 月度初始化：本月尚未执行，且已过「1 号 12:00」即执行（离线 / 晚开可自动补齐当月）。
        val noonDay1 = ym.atDay(1).atTime(12, 0)
        if (prefs.depositMonth != ym.toString() && !now.isBefore(noonDay1)) {
            applyMonthlyDeposit(ym)
        }
        ensureDayRollover(now.toLocalDate())
    }

    /**
     * 每月 1 号 12 点：扣 300 定期存款 → 记特殊转账记录（不计入消费）→ 固定当月预算基准。
     * 幂等：同一月只执行一次。
     */
    private suspend fun applyMonthlyDeposit(ym: YearMonth) = withContext(Dispatchers.IO) {
        val deposit = config.config.monthlyDeposit
        prefs.totalBalance -= deposit
        prefs.budgetBase = prefs.totalBalance
        prefs.depositMonth = ym.toString()

        // 月初扣款是本月第一笔余额变更：立刻重算当日限额，保证 1 号限额基于扣款后余额。
        recomputeTodayLimit(ym.atDay(1))

        val ts = ym.atDay(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val bill = Bill(
            channel = "system",
            type = "expense",
            amount = deposit,
            note = "定期存款转出",
            timestamp = ts,
            rawText = "每月 1 号自动转出定期存款",
            userRemark = "",
            isDeposit = true
        )
        dao.insert(bill)
        dataVersion.value += 1
        logWriter.append(ym.atDay(1), "system", "deposit", deposit, "每月 1 号自动转出定期存款", "", ts)
    }

    /** 当日限额每天固定一次：日期变化时用「当前余额 ÷ 当月剩余天数」重算并写入历史。 */
    private fun ensureDayRollover(now: LocalDate) {
        if (prefs.limitDate != now.toString()) {
            recomputeTodayLimit(now)
        }
    }

    private fun recomputeTodayLimit(today: LocalDate) {
        val limit = BudgetEngine.computeDailyLimit(prefs.totalBalance, today)
        prefs.limitDate = today.toString()
        prefs.todayLimit = limit
        val hist = prefs.limitHistory.toMutableMap()
        hist[today.toString()] = limit
        prefs.limitHistory = hist
    }

    /** 防抽风去重：30 秒内同一关键指纹只记第一笔（synchronized 原子 check-and-set，规避并发重复入账）。
     *  支出按「同数额」去重（用户规则）；收入按「同数额+同原文」去重（红包/转账各条含义不同，需文本区分避免漏记）。 */
    private val dedupLock = Any()
    private val lastRecordAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun recordDuplicate(key: String): Boolean = synchronized(dedupLock) {
        val now = System.currentTimeMillis()
        val last = lastRecordAt[key] ?: 0L
        if (now - last < 30_000L) true
        else {
            lastRecordAt[key] = now
            false
        }
    }

    suspend fun applyExpense(tx: TxRecord) = withContext(Dispatchers.IO) {
        if (recordDuplicate("expense|${tx.amount}")) return@withContext
        prefs.totalBalance -= tx.amount
        val bill = Bill(
            channel = tx.channel, type = "expense", amount = tx.amount,
            note = tx.note, timestamp = tx.timestamp, rawText = tx.rawText
        )
        dao.insert(bill)
        dataVersion.value += 1
        logWriter.append(
            BudgetEngine.toDate(tx.timestamp), tx.channel, "expense",
            tx.amount, tx.rawText, "", tx.timestamp
        )
    }

    suspend fun applyIncome(tx: TxRecord) = withContext(Dispatchers.IO) {
        if (recordDuplicate("income|${tx.amount}|${tx.rawText}")) return@withContext
        prefs.totalBalance += tx.amount
        val bill = Bill(
            channel = tx.channel, type = "income", amount = tx.amount,
            note = tx.note, timestamp = tx.timestamp, rawText = tx.rawText
        )
        dao.insert(bill)
        dataVersion.value += 1
        logWriter.append(
            BudgetEngine.toDate(tx.timestamp), tx.channel, "income",
            tx.amount, tx.rawText, "", tx.timestamp
        )
    }

    /** 余额读数：直接校准当前可用总余额；不改变当月预算基准（基准仍只由月初初始化决定）。 */
    suspend fun applyBalance(channel: String, balance: Double, rawText: String, timestamp: Long) =
        withContext(Dispatchers.IO) {
            prefs.totalBalance = balance
            prefs.hasBalanceRead = true
            logWriter.append(
                BudgetEngine.toDate(timestamp), channel, "balance",
                balance, rawText, "", timestamp
            )
            dataVersion.value += 1
        }

    /** 手动校准余额（种子）：直接覆盖当前余额与本月预算基准，并立刻重算当日限额。 */
    suspend fun setBalance(value: Double) = withContext(Dispatchers.IO) {
        prefs.totalBalance = value
        prefs.budgetBase = value
        prefs.hasBalanceRead = true
        recomputeTodayLimit(LocalDate.now())
        logWriter.append(LocalDate.now(), "system", "seed", value, "手动校准余额", "", System.currentTimeMillis())
        dataVersion.value += 1
    }

    /** 批量删除所选账单：按类型回滚当前余额、写删除日志，并重算今日限额。 */
    suspend fun deleteBills(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        for (id in ids) {
            val bill = dao.getById(id) ?: continue
            when (bill.type) {
                "expense" -> prefs.totalBalance += bill.amount
                "income" -> prefs.totalBalance -= bill.amount
            }
            logWriter.append(
                BudgetEngine.toDate(bill.timestamp), bill.channel, "deleted",
                bill.amount, bill.rawText, bill.userRemark, bill.timestamp
            )
        }
        dao.deleteByIds(ids)
        recomputeTodayLimit(LocalDate.now())
        dataVersion.value += 1
    }

    /** 手动入账：直接写一条收支记录（渠道 manual），并按类型调整余额、重算今日限额。 */
    suspend fun applyManual(type: String, amount: Double, note: String) = withContext(Dispatchers.IO) {
        val t = if (type == "income") "income" else "expense"
        if (t == "expense") prefs.totalBalance -= amount else prefs.totalBalance += amount
        val ts = System.currentTimeMillis()
        val bill = Bill(
            channel = "manual", type = t, amount = amount,
            note = note.ifBlank { "手动入账" }, timestamp = ts, rawText = "手动入账"
        )
        dao.insert(bill)
        recomputeTodayLimit(LocalDate.now())
        logWriter.append(LocalDate.now(), "manual", t, amount, "手动入账", note, ts)
        dataVersion.value += 1
    }

    /** 编辑条目：回滚旧记录对余额的影响，再按新类型/金额应用，并更新备注。 */
    suspend fun updateBill(id: Long, type: String, amount: Double, note: String) = withContext(Dispatchers.IO) {
        val old = dao.getById(id) ?: return@withContext
        when (old.type) {
            "expense" -> prefs.totalBalance += old.amount
            "income" -> prefs.totalBalance -= old.amount
        }
        val t = if (type == "income") "income" else "expense"
        if (t == "expense") prefs.totalBalance -= amount else prefs.totalBalance += amount
        dao.update(old.copy(type = t, amount = amount, userRemark = note))
        recomputeTodayLimit(LocalDate.now())
        logWriter.append(BudgetEngine.toDate(old.timestamp), old.channel, "edited", amount, old.rawText, note, old.timestamp)
        dataVersion.value += 1
    }

    /** 双击重命名备注：同时更新 Room 与当日日志文件。 */
    suspend fun rename(id: Long, remark: String) = withContext(Dispatchers.IO) {
        dao.updateRemark(id, remark)
        val bill = dao.getById(id)
        if (bill != null) {
            logWriter.append(
                BudgetEngine.toDate(bill.timestamp), bill.channel, "remark",
                0.0, bill.rawText, remark, bill.timestamp
            )
        }
        dataVersion.value += 1
    }

    private val zone: ZoneId get() = ZoneId.systemDefault()

    @Suppress("unused")
    private fun nowLdt(): LocalDateTime = LocalDateTime.now()
}