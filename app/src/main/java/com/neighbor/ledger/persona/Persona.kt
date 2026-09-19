package com.neighbor.ledger.persona

import com.neighbor.ledger.config.AppConfig

/**
 * 邻家二次元少女人设文案选择器。
 *
 * 全部文案来自外置 JSON（config.persona），本类只做「档位 + 关键词」路由，绝不内置任何文案。
 * 人设：同龄邻家少女，话不多，温柔略带毒舌，偶尔金句，不鸡汤不油腻。
 */
object Persona {

    fun pickLine(
        cfg: AppConfig,
        todayExpense: Double,
        todayLimit: Double,
        todayNotes: String
    ): String {
        val p = cfg.persona
        val ratio = if (todayLimit <= 0.0) {
            if (todayExpense > 0) 2.0 else 0.0
        } else todayExpense / todayLimit

        val pool: List<String> = when {
            ratio < 0.8 -> p.verySaving                                   // 极度节约
            ratio <= 1.0 -> p.normal                                      // 正常平稳
            else -> {                                                     // 超支
                val cat = matchCategory(cfg, todayNotes)
                when {
                    cat == "shopping" -> p.shoppingOver
                    cat == "food" -> p.foodOver
                    ratio > 1.1 -> p.severeOver                           // 严重
                    else -> p.edgeWarn + p.mildOver                       // 100-110% 踩线 / 轻度
                }
            }
        }
        return if (pool.isNotEmpty()) pool.random() else ""
    }

    private fun matchCategory(cfg: AppConfig, notes: String): String? {
        if (cfg.shoppingKeywords.any { notes.contains(it) }) return "shopping"
        if (cfg.foodKeywords.any { notes.contains(it) }) return "food"
        return null
    }

    fun future(cfg: AppConfig): String = cfg.persona.future.randomOrNull() ?: ""

    fun superIslandLine(cfg: AppConfig): String = cfg.persona.superIsland.randomOrNull() ?: ""

    private fun <T> List<T>.randomOrNull(): T? = if (isEmpty()) null else random()
}