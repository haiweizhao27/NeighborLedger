package com.neighbor.ledger.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.neighbor.ledger.R
import com.neighbor.ledger.budget.BudgetEngine
import com.neighbor.ledger.budget.ConsumeLevel
import com.neighbor.ledger.chart.ChartSeries
import com.neighbor.ledger.chart.DayPoint
import com.neighbor.ledger.chart.LineChartView
import com.neighbor.ledger.chart.MonthCalendarView
import com.neighbor.ledger.config.ChannelKey
import com.neighbor.ledger.data.Bill
import com.neighbor.ledger.persona.Persona
import com.neighbor.ledger.service.WorkScheduler
import com.neighbor.ledger.service.ServiceGuardWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 全局唯一主页面（MIUI8 扁平设计语言，深浅色跟随系统）。
 * 布局从上至下：顶部横向信息卡片 → 滑块过滤 → 日历/折线图切换 → 拟人总结 → 当日账目列表 → 说明区。
 */
class MainActivity : AppCompatActivity() {

    private val vm: MainViewModel by viewModels()

    private lateinit var topCardRow: LinearLayout
    private lateinit var warningBanner: TextView
    private lateinit var filterAll: TextView
    private lateinit var filterExpense: TextView
    private lateinit var filterIncome: TextView
    private lateinit var monthTitle: TextView
    private lateinit var btnCalendar: TextView
    private lateinit var btnChart: TextView
    private lateinit var calendarView: MonthCalendarView
    private lateinit var lineChart: LineChartView
    private lateinit var personaText: TextView
    private lateinit var billList: RecyclerView
    private lateinit var manualText: TextView
    private lateinit var selectionBar: LinearLayout
    private lateinit var selCount: TextView
    private lateinit var selDelete: TextView

    private val adapter = BillAdapter(
        onDoubleTap = { bill -> showRenameDialog(bill) },
        onSwipeDay = { delta -> vm.shiftDay(delta) },
        onLongPress = { bill -> showBillActions(bill) }
    )

    // 折线图内存缓存：只有数据版本或月份变化才重算；只有内容变化才重绘。
    private var cacheVersion = -1L
    private var cacheMonth: YearMonth? = null
    private var cacheSeries: ChartSeries? = null
    private var shownSeries: ChartSeries? = null

    private var latestBills: List<Bill> = emptyList()
    private var latestVersion: Long = 0

    private val requestNotifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 结果静默处理 */ }

    private val pickConfigFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importConfig(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        applySystemBarInsets()

        billList.layoutManager = LinearLayoutManager(this)
        billList.isNestedScrollingEnabled = false
        billList.adapter = adapter

        calendarView.onDateSelected = { d -> vm.onSelectDate(d) }
        calendarView.onMonthSwipe = { delta -> vm.shiftMonth(delta) }

        // 日历下方「卡片」：拟人总结卡始终可见，作为左右滑切上/下一日的兜底手势（当天无消费条目也能切日期）。
        installDaySwipe(personaText, consumeDown = true)

        // 多选批量管理
        adapter.onSelectionChanged = { count, mode ->
            selectionBar.visibility = if (mode) View.VISIBLE else View.GONE
            selCount.text = getString(R.string.sel_count, count)
            selDelete.alpha = if (count > 0) 1f else 0.4f
            selDelete.isEnabled = count > 0
        }
        findViewById<View>(R.id.sel_all).setOnClickListener { adapter.selectAll() }
        selDelete.setOnClickListener {
            val ids = adapter.selectedIds.toList()
            if (ids.isNotEmpty()) confirmBatchDelete(ids)
        }
        findViewById<View>(R.id.sel_cancel).setOnClickListener { adapter.exitSelection() }

        filterAll.setOnClickListener { vm.filter.value = ListFilter.ALL }
        filterExpense.setOnClickListener { vm.filter.value = ListFilter.EXPENSE }
        filterIncome.setOnClickListener { vm.filter.value = ListFilter.INCOME }
        btnCalendar.setOnClickListener { vm.showChart.value = false }
        btnChart.setOnClickListener { vm.showChart.value = true }

        findViewById<TextView>(R.id.btn_prev_month).setOnClickListener {
            val ym = YearMonth.from(vm.selectedDate.value).minusMonths(1)
            vm.onSelectDate(ym.atDay(1))
        }
        findViewById<TextView>(R.id.btn_next_month).setOnClickListener {
            val ym = YearMonth.from(vm.selectedDate.value).plusMonths(1)
            vm.onSelectDate(ym.atDay(1))
        }
        findViewById<View>(R.id.btn_view_log).setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }
        findViewById<View>(R.id.btn_update_rules).setOnClickListener {
            pickConfigFile.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        findViewById<View>(R.id.btn_set_balance).setOnClickListener { showSeedDialog() }
        findViewById<View>(R.id.btn_add).setOnClickListener { showManualEntryDialog() }

        // “格式更新”提示条：点一下即关闭（清除连续失败计数）；再次连续失败会重新出现。
        warningBanner.setOnClickListener {
            listOf(ChannelKey.WECHAT, ChannelKey.ALIPAY, ChannelKey.WECHAT_FUND, ChannelKey.OTHER)
                .forEach { vm.prefs.resetParseFail(it) }
            renderAll()
        }

        manualText.text = getString(R.string.manual_text)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    vm.repo.observeBills(),
                    vm.repo.dataVersion,
                    vm.selectedDate,
                    vm.filter,
                    vm.showChart
                ) { bills, version, date, filter, chart ->
                    Model(bills, version, date, filter, chart)
                }.collect { m ->
                    latestBills = m.bills
                    latestVersion = m.version
                    render(m)
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // 多选状态下：点击条目和工具栏以外的区域 → 取消选择
        if (adapter.selectionMode && ev.actionMasked == android.view.MotionEvent.ACTION_DOWN &&
            this::billList.isInitialized && this::selectionBar.isInitialized
        ) {
            val rect = android.graphics.Rect()
            billList.getGlobalVisibleRect(rect)
            val hitItems = rect.contains(ev.rawX.toInt(), ev.rawY.toInt())
            selectionBar.getGlobalVisibleRect(rect)
            val hitBar = rect.contains(ev.rawX.toInt(), ev.rawY.toInt())
            if (!hitItems && !hitBar) {
                adapter.exitSelection()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        maybeRequestNotificationPermission()
        vm.onTick()
        // 监听权限引导 + 容错提示刷新
        if (!ServiceGuardWorker.isListenerEnabled(this)) {
            showListenerGuide()
        }
        renderAll()
    }

    private fun bindViews() {
        topCardRow = findViewById(R.id.top_card_row)
        warningBanner = findViewById(R.id.warning_banner)
        filterAll = findViewById(R.id.filter_all)
        filterExpense = findViewById(R.id.filter_expense)
        filterIncome = findViewById(R.id.filter_income)
        monthTitle = findViewById(R.id.month_title)
        btnCalendar = findViewById(R.id.btn_calendar)
        btnChart = findViewById(R.id.btn_chart)
        calendarView = findViewById(R.id.calendar_view)
        lineChart = findViewById(R.id.line_chart)
        personaText = findViewById(R.id.persona_text)
        billList = findViewById(R.id.bill_list)
        manualText = findViewById(R.id.manual_text)
        selectionBar = findViewById(R.id.selection_bar)
        selCount = findViewById(R.id.sel_count)
        selDelete = findViewById(R.id.sel_delete)
    }

    private fun applySystemBarInsets() {
        // targetSdk 35 强制 edge-to-edge：必须让根容器避开系统状态栏/导航栏，否则内容顶进通知区。
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root_scroll)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /** 给「日历下方卡片」装左右滑动切日手势；consumeDown=true 用于无子交互的卡片（如拟人总结卡）。 */
    private fun installDaySwipe(view: View, consumeDown: Boolean) {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var x0 = 0f
        var y0 = 0f
        var horizontal = false
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    x0 = e.x; y0 = e.y; horizontal = false
                    consumeDown
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - x0
                    val dy = e.y - y0
                    if (!horizontal && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                        horizontal = true
                        (v.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (horizontal) {
                        v.translationX = dx * 0.4f
                        true
                    } else {
                        false
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = e.x - x0
                    if (horizontal) {
                        if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(e.y - y0)) {
                            vm.shiftDay(if (dx < 0) 1 else -1)
                        }
                        horizontal = false
                        (v.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                        v.animate().translationX(0f).setDuration(120L).start()
                        true
                    } else {
                        false
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    horizontal = false
                    (v.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                    v.animate().translationX(0f).setDuration(120L).start()
                    false
                }
                else -> false
            }
        }
    }

    private data class Model(
        val bills: List<Bill>,
        val version: Long,
        val date: LocalDate,
        val filter: ListFilter,
        val chart: Boolean
    )

    private fun renderAll() {
        render(Model(latestBills, latestVersion, vm.selectedDate.value, vm.filter.value, vm.showChart.value))
    }

    private fun render(m: Model) {
        val today = LocalDate.now()
        val date = m.date
        val cfg = vm.config.config
        val prefs = vm.prefs

        renderTopCard(date, today, m.bills, cfg, prefs)
        renderBanners(cfg, prefs)
        renderChips(m.filter, m.chart)

        val ym = YearMonth.from(date)
        monthTitle.text = "%d年%d月".format(ym.year, ym.monthValue)

        // 日历 / 折线图
        if (m.chart) {
            calendarView.visibility = View.GONE
            lineChart.visibility = View.VISIBLE
            val series = chartForMonth(ym, m.bills, m.version, today, prefs)
            if (shownSeries != series) {
                lineChart.setSeries(series)
                shownSeries = series
            }
        } else {
            lineChart.visibility = View.GONE
            calendarView.visibility = View.VISIBLE
            val expenseMap = HashMap<Int, Double>()
            val limitMap = HashMap<Int, Double>()
            for (b in m.bills) {
                val d = BudgetEngine.toDate(b.timestamp)
                if (YearMonth.from(d) == ym && b.type == "expense" && !b.isDeposit) {
                    expenseMap[d.dayOfMonth] = (expenseMap[d.dayOfMonth] ?: 0.0) + b.amount
                }
            }
            prefs.limitHistory.forEach { (k, v) ->
                val parts = k.split("-")
                if (parts.size == 3 && parts[0].toIntOrNull() == ym.year && parts[1].toIntOrNull() == ym.monthValue) {
                    limitMap[parts[2].toIntOrNull() ?: 0] = v
                }
            }
            calendarView.setData(ym, date, today, expenseMap, limitMap, prefs.todayLimit)
        }

        // 拟人总结
        val dayBills = dayBills(m.bills, date)
        val personaLine = when {
            date.isAfter(today) -> Persona.future(cfg)
            else -> {
                val expense = BudgetEngine.dayExpense(m.bills, date)
                val limit = if (date == today) prefs.todayLimit else prefs.limitHistory[date.toString()] ?: 0.0
                val notes = dayBills.joinToString(" ") { it.note + " " + it.rawText }
                Persona.pickLine(cfg, expense, limit, notes)
            }
        }
        personaText.text = personaLine

        // 当日账目（时间升序）
        val shown = when (m.filter) {
            ListFilter.ALL -> dayBills
            ListFilter.EXPENSE -> dayBills.filter { it.type == "expense" && !it.isDeposit }
            ListFilter.INCOME -> dayBills.filter { it.type == "income" }
        }
        adapter.submit(shown)
    }

    private fun dayBills(bills: List<Bill>, date: LocalDate): List<Bill> {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.atTime(LocalTime.MAX).atZone(zone).toInstant().toEpochMilli()
        return bills.filter { it.timestamp in start until end }
    }

    private fun renderTopCard(date: LocalDate, today: LocalDate, bills: List<Bill>, cfg: com.neighbor.ledger.config.AppConfig, prefs: com.neighbor.ledger.data.WalletPrefs) {
        topCardRow.removeAllViews()
        val primary = ContextCompat.getColor(this, R.color.text_primary)

        when {
            date == today -> {
                val expense = BudgetEngine.dayExpense(bills, date)
                val limit = prefs.todayLimit
                val available = BudgetEngine.floor2(limit - expense) // 超额时为负，下方用红字展示
                val predicted = BudgetEngine.predictedDaily(prefs.budgetBase, date)
                val periodAvg = BudgetEngine.periodAverage(bills, date)
                val level = BudgetEngine.consumeLevel(periodAvg, predicted)

                val availableColor = if (available < 0.0) ContextCompat.getColor(this, R.color.over_red) else primary
                topCardRow.addView(topCell(getString(R.string.tc_available), "%.2f".format(available), availableColor))
                topCardRow.addView(topCell(getString(R.string.tc_spent), "%.2f".format(expense), primary))
                topCardRow.addView(topCell(getString(R.string.tc_limit), "%.2f".format(limit), primary))
                topCardRow.addView(topCell(getString(R.string.tc_avg), "%.2f".format(periodAvg), colorFor(level)))
            }
            date.isBefore(today) -> {
                val net = BudgetEngine.dayNet(bills, date)
                val endBal = BudgetEngine.dayEndBalance(bills, date, prefs.totalBalance)
                val predicted = BudgetEngine.predictedDaily(prefs.budgetBase, date)
                val periodAvg = BudgetEngine.periodAverage(bills, date)
                val level = BudgetEngine.consumeLevel(periodAvg, predicted)

                topCardRow.addView(topCell(getString(R.string.tc_net), "%.2f".format(net), primary))
                topCardRow.addView(topCell(getString(R.string.tc_end_bal), "%.2f".format(endBal), primary))
                topCardRow.addView(topCell(getString(R.string.tc_avg), "%.2f".format(periodAvg), colorFor(level)))
            }
            else -> {
                topCardRow.addView(topCell(getString(R.string.tc_plan), Persona.future(cfg), primary))
                topCardRow.addView(topCell(getString(R.string.tc_balance), "%.2f".format(prefs.totalBalance), primary))
            }
        }
    }

    private fun colorFor(level: ConsumeLevel): Int = ContextCompat.getColor(
        this,
        when (level) {
            ConsumeLevel.SAVING -> R.color.saving_green
            ConsumeLevel.NORMAL -> R.color.text_primary
            ConsumeLevel.EDGE -> R.color.warn_yellow
            ConsumeLevel.OVER -> R.color.over_red
        }
    )

    private fun topCell(label: String, value: String, valueColor: Int): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = dp(12)
            setPadding(p, dp(8), p, dp(8))
        }
        val v = TextView(this).apply {
            text = value
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(valueColor)
            gravity = Gravity.CENTER
        }
        val l = TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            gravity = Gravity.CENTER
        }
        box.addView(v)
        box.addView(l)
        return box
    }

    private fun renderBanners(cfg: com.neighbor.ledger.config.AppConfig, prefs: com.neighbor.ledger.data.WalletPrefs) {
        var banner: String? = null
        for (key in listOf(ChannelKey.WECHAT, ChannelKey.ALIPAY, ChannelKey.WECHAT_FUND, ChannelKey.OTHER)) {
            val ch = cfg.channels[key] ?: continue
            if (ch.enabled && prefs.parseFailCount(key) >= 3) {
                banner = getString(R.string.banner_format_changed, ch.name)
                break
            }
        }
        if (banner == null) {
            val anyBalanceChannel = listOf(ChannelKey.WECHAT, ChannelKey.ALIPAY, ChannelKey.WECHAT_FUND, ChannelKey.OTHER)
                .any { cfg.channels[it]?.enabled == true && !cfg.channels[it]!!.balanceKeywords.isNullOrEmpty() }
            if (anyBalanceChannel && prefs.totalBalance <= 0.0) {
                banner = getString(R.string.banner_no_balance)
            }
        }
        if (banner != null) {
            warningBanner.text = banner
            warningBanner.visibility = View.VISIBLE
        } else {
            warningBanner.visibility = View.GONE
        }
    }

    private fun renderChips(filter: ListFilter, chart: Boolean) {
        styleChip(filterAll, filter == ListFilter.ALL)
        styleChip(filterExpense, filter == ListFilter.EXPENSE)
        styleChip(filterIncome, filter == ListFilter.INCOME)
        styleChip(btnCalendar, !chart)
        styleChip(btnChart, chart)
    }

    private fun styleChip(v: TextView, active: Boolean) {
        v.isSelected = active
        v.setBackgroundResource(if (active) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
        v.setTextColor(
            ContextCompat.getColor(
                this,
                if (active) R.color.on_brand else R.color.text_secondary
            )
        )
    }

    private fun chartForMonth(ym: YearMonth, bills: List<Bill>, version: Long, today: LocalDate, prefs: com.neighbor.ledger.data.WalletPrefs): ChartSeries {
        // 缓存复用：同一数据版本、同一月份直接复用，不重算不重绘。
        if (version == cacheVersion && ym == cacheMonth && cacheSeries != null) {
            return cacheSeries!!
        }

        val archiveMonthYear = YearMonth.from(today)
        val series: ChartSeries = if (ym.isBefore(archiveMonthYear)) {
            vm.chartArchive.load(ym.year, ym.monthValue)
                ?: buildSeries(ym, bills, today, prefs).also { vm.chartArchive.save(it) }
        } else {
            buildSeries(ym, bills, today, prefs)
        }

        cacheVersion = version
        cacheMonth = ym
        cacheSeries = series
        return series
    }

    private fun buildSeries(ym: YearMonth, bills: List<Bill>, today: LocalDate, prefs: com.neighbor.ledger.data.WalletPrefs): ChartSeries {
        val expense = HashMap<Int, Double>()
        for (b in bills) {
            val d = BudgetEngine.toDate(b.timestamp)
            if (YearMonth.from(d) == ym && b.type == "expense" && !b.isDeposit) {
                expense[d.dayOfMonth] = (expense[d.dayOfMonth] ?: 0.0) + b.amount
            }
        }
        val points = (1..ym.lengthOfMonth()).map { day ->
            val dateStr = "%04d-%02d-%02d".format(ym.year, ym.monthValue, day)
            val limit = prefs.limitHistory[dateStr] ?: if (dateStr == today.toString()) prefs.todayLimit else 0.0
            DayPoint(day, expense[day] ?: 0.0, limit)
        }
        return ChartSeries(ym.year, ym.monthValue, points)
    }

    // ---------------- 交互 ----------------

    private fun dialogChip(text: String, bgRes: Int, fgRes: Int): TextView =
        TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, fgRes))
            setBackgroundResource(bgRes)
            val h = dp(10)
            val w = dp(16)
            setPadding(w, h, w, h)
        }

    private fun dialogButtonRow(cancel: TextView, ok: TextView): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(cancel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row.addView(ok, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        return row
    }

    private fun showRenameDialog(bill: Bill) {
        val input = EditText(this).apply {
            setText(bill.userRemark.ifBlank { bill.note })
            setSelection(text.length)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        content.addView(input)
        val cancel = dialogChip(getString(R.string.cancel), R.drawable.bg_chip_normal, R.color.text_primary)
        val ok = dialogChip(getString(R.string.confirm), R.drawable.bg_chip_selected, R.color.on_brand)
        content.addView(dialogButtonRow(cancel, ok),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_title)
            .setView(content)
            .show()
        cancel.setOnClickListener { dialog.dismiss() }
        ok.setOnClickListener {
            val text = input.text.toString().trim()
            vm.rename(bill.id, text)
            dialog.dismiss()
            Toast.makeText(this, R.string.rename_done, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSeedDialog() {
        val input = EditText(this).apply {
            setText("%.2f".format(vm.prefs.totalBalance))
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        val hint = TextView(this).apply {
            text = getString(R.string.seed_hint)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 13f
        }
        content.addView(hint)
        content.addView(input,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        val cancel = dialogChip(getString(R.string.cancel), R.drawable.bg_chip_normal, R.color.text_primary)
        val ok = dialogChip(getString(R.string.confirm), R.drawable.bg_chip_selected, R.color.on_brand)
        content.addView(dialogButtonRow(cancel, ok),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.seed_title)
            .setView(content)
            .show()
        cancel.setOnClickListener { dialog.dismiss() }
        ok.setOnClickListener {
            val v = input.text.toString().toDoubleOrNull()
            if (v == null || v < 0.0) {
                Toast.makeText(this, R.string.seed_invalid, Toast.LENGTH_SHORT).show()
            } else {
                vm.setBalance(v)
                dialog.dismiss()
                Toast.makeText(this, R.string.seed_done, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmBatchDelete(ids: List<Long>) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        val msg = TextView(this).apply {
            text = getString(R.string.batch_delete_msg, ids.size)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 14f
        }
        content.addView(msg)
        val cancel = dialogChip(getString(R.string.cancel), R.drawable.bg_chip_normal, R.color.text_primary)
        val del = dialogChip(getString(R.string.sel_delete), R.drawable.bg_chip_danger, R.color.on_brand)
        content.addView(dialogButtonRow(cancel, del),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.batch_delete_title)
            .setView(content)
            .show()
        cancel.setOnClickListener { dialog.dismiss() }
        del.setOnClickListener {
            vm.deleteBills(ids)
            adapter.exitSelection()
            dialog.dismiss()
            Toast.makeText(this, R.string.delete_done, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showManualEntryDialog() {
        var type = "expense"
        val amountInput = EditText(this).apply {
            hint = getString(R.string.manual_amount_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val noteInput = EditText(this).apply {
            hint = getString(R.string.manual_note_hint)
        }
        val expenseChip = dialogChip(getString(R.string.filter_expense), R.drawable.bg_chip_selected, R.color.on_brand)
        val incomeChip = dialogChip(getString(R.string.filter_income), R.drawable.bg_chip_normal, R.color.text_primary)

        fun setType(t: String) {
            type = t
            val isExpense = t == "expense"
            expenseChip.setBackgroundResource(if (isExpense) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
            expenseChip.setTextColor(ContextCompat.getColor(this@MainActivity, if (isExpense) R.color.on_brand else R.color.text_primary))
            incomeChip.setBackgroundResource(if (!isExpense) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
            incomeChip.setTextColor(ContextCompat.getColor(this@MainActivity, if (!isExpense) R.color.on_brand else R.color.text_primary))
        }
        expenseChip.setOnClickListener { setType("expense") }
        incomeChip.setOnClickListener { setType("income") }

        val typeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        typeRow.addView(expenseChip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        typeRow.addView(incomeChip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        content.addView(typeRow)
        content.addView(amountInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        content.addView(noteInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        val cancel = dialogChip(getString(R.string.cancel), R.drawable.bg_chip_normal, R.color.text_primary)
        val ok = dialogChip(getString(R.string.confirm), R.drawable.bg_chip_selected, R.color.on_brand)
        content.addView(dialogButtonRow(cancel, ok),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manual_entry_title)
            .setView(content)
            .show()
        cancel.setOnClickListener { dialog.dismiss() }
        ok.setOnClickListener {
            val amount = amountInput.text.toString().toDoubleOrNull()
            if (amount == null || amount <= 0.0) {
                Toast.makeText(this, R.string.manual_invalid, Toast.LENGTH_SHORT).show()
            } else {
                vm.addManual(type, amount, noteInput.text.toString().trim())
                dialog.dismiss()
                Toast.makeText(this, R.string.manual_done, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showBillActions(bill: Bill) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        val edit = dialogChip(getString(R.string.action_edit), R.drawable.bg_chip_selected, R.color.on_brand)
        val del = dialogChip(getString(R.string.sel_delete), R.drawable.bg_chip_danger, R.color.on_brand)
        val multi = dialogChip(getString(R.string.action_multi), R.drawable.bg_chip_normal, R.color.text_primary)
        content.addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(del, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        content.addView(multi, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_title)
            .setView(content)
            .show()
        edit.setOnClickListener { dialog.dismiss(); showEditBillDialog(bill) }
        del.setOnClickListener { dialog.dismiss(); confirmBatchDelete(listOf(bill.id)) }
        multi.setOnClickListener { dialog.dismiss(); adapter.enterSelection(bill) }
    }

    private fun showEditBillDialog(bill: Bill) {
        var type = if (bill.type == "income") "income" else "expense"
        val amountInput = EditText(this).apply {
            setText("%.2f".format(bill.amount))
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val noteInput = EditText(this).apply {
            hint = getString(R.string.manual_note_hint)
            setText(bill.userRemark.ifBlank { bill.note })
        }
        val expenseChip = dialogChip(getString(R.string.filter_expense), R.drawable.bg_chip_selected, R.color.on_brand)
        val incomeChip = dialogChip(getString(R.string.filter_income), R.drawable.bg_chip_normal, R.color.text_primary)

        fun setType(t: String) {
            type = t
            val isExpense = t == "expense"
            expenseChip.setBackgroundResource(if (isExpense) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
            expenseChip.setTextColor(ContextCompat.getColor(this@MainActivity, if (isExpense) R.color.on_brand else R.color.text_primary))
            incomeChip.setBackgroundResource(if (!isExpense) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
            incomeChip.setTextColor(ContextCompat.getColor(this@MainActivity, if (!isExpense) R.color.on_brand else R.color.text_primary))
        }
        expenseChip.setOnClickListener { setType("expense") }
        incomeChip.setOnClickListener { setType("income") }
        setType(type)

        val typeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        typeRow.addView(expenseChip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        typeRow.addView(incomeChip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        content.addView(typeRow)
        content.addView(amountInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        content.addView(noteInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        val cancel = dialogChip(getString(R.string.cancel), R.drawable.bg_chip_normal, R.color.text_primary)
        val ok = dialogChip(getString(R.string.confirm), R.drawable.bg_chip_selected, R.color.on_brand)
        content.addView(dialogButtonRow(cancel, ok),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_title)
            .setView(content)
            .show()
        cancel.setOnClickListener { dialog.dismiss() }
        ok.setOnClickListener {
            val amount = amountInput.text.toString().toDoubleOrNull()
            if (amount == null || amount <= 0.0) {
                Toast.makeText(this, R.string.manual_invalid, Toast.LENGTH_SHORT).show()
            } else {
                vm.updateBill(bill.id, type, amount, noteInput.text.toString().trim())
                dialog.dismiss()
                Toast.makeText(this, R.string.edit_done, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importConfig(uri: Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            }
            if (text.isBlank()) {
                Toast.makeText(this@MainActivity, R.string.config_import_fail, Toast.LENGTH_LONG).show()
                return@launch
            }
            val result: Result<com.neighbor.ledger.config.AppConfig> = withContext(Dispatchers.IO) {
                vm.config.importJson(text)
            }
            if (result.isSuccess) {
                Toast.makeText(this@MainActivity, R.string.config_import_ok, Toast.LENGTH_SHORT).show()
                renderAll()
            } else {
                Toast.makeText(this@MainActivity, R.string.config_import_fail, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showListenerGuide() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.listener_guide_title)
            .setMessage(R.string.listener_guide_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.go_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .show()
    }

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}