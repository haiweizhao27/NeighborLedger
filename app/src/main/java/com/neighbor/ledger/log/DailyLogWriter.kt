package com.neighbor.ledger.log

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 当日日志写入器。
 *
 * 每解析成功一笔收支 / 余额变动，除写 Room 外，同时追加写入 App 私有目录：`logs/yyyy-MM-dd.log`。
 * 字段：渠道、收支类型、时间、金额、原始通知文本、用户备注。
 * 与 Room 数据库、月度归档、JSON 配置完全隔离。
 */
class DailyLogWriter(private val context: Context) {

    private val gson = Gson()
    private val dir: File get() = File(context.filesDir, "logs").apply { mkdirs() }

    fun fileFor(date: LocalDate): File =
        File(dir, "${date.format(DATE_FMT)}.log")

    fun append(
        date: LocalDate,
        channel: String,
        type: String,
        amount: Double,
        rawText: String,
        userRemark: String = "",
        timestamp: Long = System.currentTimeMillis()
    ) {
        val line = LogLine(
            channel = channel,
            type = type,
            time = timestamp,
            amount = amount,
            rawText = rawText,
            userRemark = userRemark
        )
        val file = fileFor(date)
        try {
            file.appendText(gson.toJson(line) + "\n", Charsets.UTF_8)
        } catch (_: Exception) {
            // 私有目录写失败仅忽略，绝不影响主流程
        }
    }

    /** 读取指定日期原始日志全文（给「查看原始日志」界面）。 */
    fun read(date: LocalDate): String {
        val file = fileFor(date)
        return if (file.exists()) file.readText(Charsets.UTF_8) else ""
    }

    data class LogLine(
        val channel: String,
        val type: String,
        val time: Long,
        val amount: Double,
        val rawText: String,
        val userRemark: String
    )

    private companion object {
        val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}