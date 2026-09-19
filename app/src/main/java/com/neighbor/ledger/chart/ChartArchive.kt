package com.neighbor.ledger.chart

import android.content.Context
import com.google.gson.Gson
import java.io.File

/**
 * 单日数据点：day(1..31)、expense(当日真实消费)、limit(当日限额，用于灰色虚线)。
 */
data class DayPoint(val day: Int, val expense: Double, val limit: Double)

/**
 * 某月折线图结构化序列（只存数据，不生成 PNG，第二章 / 第三章第 3 条）。
 */
data class ChartSeries(
    val year: Int,
    val month: Int,
    val points: List<DayPoint>
)

/**
 * 月度归档管理器（第三章第 3 条）：
 * - 不输出图片 PNG，只序列化结构化数据到 App 私有目录；
 * - 文件命名：`yyyy-MM-monthly_chart.data`；
 * - 点击历史日期时优先读归档，无归档才实时查 Room 渲染。
 */
class ChartArchiveManager(private val context: Context) {

    private val gson = Gson()
    private val dir: File get() = File(context.filesDir, "monthly_chart").apply { mkdirs() }

    fun fileName(year: Int, month: Int): String =
        "%04d-%02d-monthly_chart.data".format(year, month)

    fun save(series: ChartSeries) {
        try {
            File(dir, fileName(series.year, series.month)).writeText(gson.toJson(series), Charsets.UTF_8)
        } catch (_: Exception) {
            // 归档写失败静默忽略（不影响实时渲染）。
        }
    }

    fun load(year: Int, month: Int): ChartSeries? {
        return try {
            val f = File(dir, fileName(year, month))
            if (!f.exists()) null else gson.fromJson(f.readText(Charsets.UTF_8), ChartSeries::class.java)
        } catch (_: Exception) {
            null
        }
    }
}