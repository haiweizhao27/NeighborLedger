package com.neighbor.ledger.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neighbor.ledger.LedgerApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

enum class ListFilter { ALL, EXPENSE, INCOME }

/**
 * 主页面状态持有者：选中日期、列表过滤、日历/折线图切换。跨旋转 / 配置变更存活。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val led = app as LedgerApp
    val repo get() = led.repository
    val prefs get() = led.prefs
    val config get() = led.config
    val chartArchive get() = led.chartArchive

    val selectedDate = MutableStateFlow(LocalDate.now())
    val filter = MutableStateFlow(ListFilter.ALL)
    val showChart = MutableStateFlow(false)     // false=日历（进 App 先看日历），true=折线图
    val calendarMonth = MutableStateFlow(YearMonth.now())

    fun onSelectDate(d: LocalDate) {
        selectedDate.value = d
    }

    /** 日历左右滑动切月（delta = ±1），保留当月日号、按目标月长度收敛。 */
    fun shiftMonth(delta: Int) {
        val cur = selectedDate.value
        val ym = YearMonth.from(cur).plusMonths(delta.toLong())
        val day = cur.dayOfMonth.coerceAtMost(ym.lengthOfMonth())
        selectedDate.value = ym.atDay(day)
    }

    /** 账目条目左右滑动切日（delta = ±1）。 */
    fun shiftDay(delta: Int) {
        selectedDate.value = selectedDate.value.plusDays(delta.toLong())
    }

    fun onTick() = viewModelScope.launch { repo.onTick(LocalDateTime.now()) }

    fun rename(id: Long, remark: String) = viewModelScope.launch {
        repo.rename(id, remark)
    }

    fun setBalance(value: Double) = viewModelScope.launch {
        repo.setBalance(value)
    }

    fun deleteBills(ids: List<Long>) = viewModelScope.launch {
        repo.deleteBills(ids)
    }

    fun addManual(type: String, amount: Double, note: String) = viewModelScope.launch {
        repo.applyManual(type, amount, note)
    }

    fun updateBill(id: Long, type: String, amount: Double, note: String) = viewModelScope.launch {
        repo.updateBill(id, type, amount, note)
    }
}