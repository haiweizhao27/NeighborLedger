package com.neighbor.ledger.config

import android.content.Context
import com.google.gson.Gson
import java.io.File

/**
 * 外置 JSON 配置模型。
 *
 * 硬性约束（第一章第 3 条、第六章）：导入 JSON 只改「参数」，绝不改程序源码、UI 骨架、数据库表结构。
 * 渠道开关、正则、人设文案库、版本号全部外置；开启新渠道无需重编译 APK。
 *
 * 字段名与 assets/default_config.json 严格一一对应（Gson 反射直接映照，无需注解）。
 */
data class AppConfig(
    val version: Int = 1,
    val initialBalance: Double = 0.0,
    val monthlyDeposit: Double = 300.0,
    val channels: Map<String, ChannelConfig> = emptyMap(),
    val shoppingKeywords: List<String> = emptyList(),
    val foodKeywords: List<String> = emptyList(),
    val persona: PersonaConfig = PersonaConfig()
)

data class ChannelConfig(
    val enabled: Boolean = false,
    val name: String = "",
    val packageName: String = "",
    val amountPattern: String = "",
    val incomeKeywords: List<String> = emptyList(),
    val expenseKeywords: List<String> = emptyList(),
    val balanceKeywords: List<String> = emptyList(),
    val incomePatterns: List<String> = emptyList(),
    val expensePatterns: List<String> = emptyList(),
    val balancePatterns: List<String> = emptyList()
)

data class PersonaConfig(
    val future: List<String> = emptyList(),
    val superIsland: List<String> = emptyList(),
    val shoppingOver: List<String> = emptyList(),
    val foodOver: List<String> = emptyList(),
    val mildOver: List<String> = emptyList(),
    val severeOver: List<String> = emptyList(),
    val normal: List<String> = emptyList(),
    val verySaving: List<String> = emptyList(),
    val edgeWarn: List<String> = emptyList()
)

/** 渠道稳定标识常量（数据库 channel 字段、配置 key 共用）。 */
object ChannelKey {
    const val WECHAT = "wechat"
    const val ALIPAY = "alipay"
    const val WECHAT_FUND = "wechat_fund"
    const val OTHER = "other"
}

/**
 * 配置读写器。
 *
 * - 内置规则：始终读取 assets/default_config.json —— 覆盖安装新 APK 即自动获得最新解析规则，
 *   无需用户重新导入。
 * - 用户覆盖：仅在「更新解析规则」导入过 JSON 后才写入私有文件 app_config_user.json 并从此优先；
 *   导入只改参数、不丢数据，卸载重装则回到最新内置规则。
 */
class ConfigManager(private val context: Context) {

    private val gson = Gson()
    private val userConfigFile: File get() = File(context.filesDir, "app_config_user.json")

    /** 当前生效配置（内存缓存）。 */
    @Volatile
    var config: AppConfig = AppConfig()
        private set

    fun load(): AppConfig {
        val loaded = try {
            if (userConfigFile.exists()) parse(userConfigFile.readText()) else parse(assetsText())
        } catch (e: Exception) {
            try { parse(assetsText()) } catch (e2: Exception) { AppConfig() }
        }
        config = loaded
        return loaded
    }

    /** 导入外部 JSON 配置（仅参数）：格式非法则保留旧配置并返回失败。 */
    fun importJson(jsonText: String): Result<AppConfig> = runCatching {
        val parsed = parse(jsonText)
        userConfigFile.writeText(jsonText)
        config = parsed
        parsed
    }

    private fun assetsText(): String =
        context.assets.open("default_config.json").bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun parse(text: String): AppConfig {
        val parsed = gson.fromJson(text, AppConfig::class.java)
        validate(parsed)
        return parsed
    }

    /** 最小合法性校验：必须存在且至少一个渠道定义，否则视为坏配置。 */
    private fun validate(cfg: AppConfig) {
        require(cfg.channels.isNotEmpty()) { "配置缺少渠道定义" }
        require(cfg.monthlyDeposit >= 0.0) { "monthlyDeposit 不能为负" }
    }

    fun channel(key: String): ChannelConfig? = config.channels[key]
}