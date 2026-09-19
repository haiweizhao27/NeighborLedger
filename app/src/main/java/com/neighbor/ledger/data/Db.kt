package com.neighbor.ledger.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.Update
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * 账单实体。
 *
 * 字段约束（第一章第 4 条）：每条账单含 `channel` 与 `type(income/expense)`。
 * - [isDeposit]：每月 1 号 12 点自动扣除的 300 元定期存款资金转出（特殊记录，不计入消费统计）。
 * - [rawText]：原始通知文本；[userRemark]：用户双击卡片重命名后的备注。
 *
 * 数据库表结构一经发布即保持稳定；外置 JSON 配置只改参数，绝不触碰表结构。
 */
@Entity(tableName = "bills")
data class Bill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channel: String,            // wechat / alipay / wechat_fund / other
    val type: String,               // "income" | "expense"
    val amount: Double,
    val note: String,
    val timestamp: Long,            // epoch millis
    val rawText: String,
    val userRemark: String = "",
    val isDeposit: Boolean = false  // 定期存款特殊记录标记
)

@Dao
interface BillDao {

    /** 全量账单（时间升序），供 UI 响应式观察。UI 只渲染当前选中月，历史月份按需查询。 */
    @Query("SELECT * FROM bills ORDER BY timestamp ASC")
    fun observeAll(): Flow<List<Bill>>

    @Query("SELECT * FROM bills ORDER BY timestamp ASC")
    suspend fun getAll(): List<Bill>

    /** 按时间区间查询（用于单日 / 单月按需读取）。 */
    @Query("SELECT * FROM bills WHERE timestamp >= :start AND timestamp < :end ORDER BY timestamp ASC")
    suspend fun between(start: Long, end: Long): List<Bill>

    @Insert
    suspend fun insert(bill: Bill): Long

    @Query("UPDATE bills SET userRemark = :remark WHERE id = :id")
    suspend fun updateRemark(id: Long, remark: String)

    @Update
    suspend fun update(bill: Bill)

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun getById(id: Long): Bill?

    @Query("DELETE FROM bills WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

@Database(entities = [Bill::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun billDao(): BillDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "neighbor_ledger.db" // 私有内部存储，独立于日志 / 归档 / 配置，重装不覆盖
                ).build().also { INSTANCE = it }
            }
    }
}