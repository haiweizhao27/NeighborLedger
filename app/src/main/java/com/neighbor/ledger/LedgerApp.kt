package com.neighbor.ledger

import android.app.Application
import com.neighbor.ledger.chart.ChartArchiveManager
import com.neighbor.ledger.config.ConfigManager
import com.neighbor.ledger.data.AppDatabase
import com.neighbor.ledger.data.BillRepository
import com.neighbor.ledger.data.WalletPrefs
import com.neighbor.ledger.log.DailyLogWriter
import com.neighbor.ledger.parser.NotificationParseManager
import com.neighbor.ledger.service.WorkScheduler

/**
 * Application 容器：只做依赖装配，不含业务逻辑。
 * 全程离线本地运行：不申请相册 / 外部存储写入权限，所有数据落 App 私有内部存储。
 */
class LedgerApp : Application() {

    lateinit var config: ConfigManager
    lateinit var prefs: WalletPrefs
    lateinit var logWriter: DailyLogWriter
    lateinit var db: AppDatabase
    lateinit var repository: BillRepository
    lateinit var parseManager: NotificationParseManager
    lateinit var chartArchive: ChartArchiveManager

    override fun onCreate() {
        super.onCreate()

        config = ConfigManager(this)
        config.load()                       // 私有副本优先，缺失则从 assets 播种

        prefs = WalletPrefs(this)
        logWriter = DailyLogWriter(this)
        db = AppDatabase.get(this)

        repository = BillRepository(this, db.billDao(), prefs, logWriter, config)
        repository.init()

        parseManager = NotificationParseManager(config)
        chartArchive = ChartArchiveManager(this)

        // 月度初始化 + 服务保活兜底（幂等，重复调度无副作用）。
        WorkScheduler.schedule(this)
    }
}