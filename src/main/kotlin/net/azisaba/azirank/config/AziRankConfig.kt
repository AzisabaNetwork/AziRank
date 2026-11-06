package net.azisaba.azirank.config

import net.azisaba.azirank.rank.Requirement
import net.azisaba.azirank.util.Durations
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import java.util.logging.Logger

data class DatabaseSettings(
    val host: String,
    val port: Int,
    val name: String,
    val username: String,
    val password: String,
    val tablePrefix: String,
    val poolSize: Int,
    val properties: Map<String, String>,
)

data class Rankup(
    val group: String,
    val requirements: List<Requirement>,
    val commands: List<String>,
)

enum class RankupMode { RUN, DRY_RUN, OFF }

class AziRankConfig(
    val database: DatabaseSettings,
    val rankupMode: RankupMode,
    val flushIntervalSeconds: Long,
    val checkIntervalSeconds: Long,
    val rankupPendingMillis: Long,
    val treatNoRankAsFirst: Boolean,
    val luckPermsServer: String,
    val leaderboardPageSize: Int,
    val leaderboardCacheMillis: Long,
    val ranks: List<String>,
    val rankups: Map<String, Rankup>,
    val messages: Map<String, String>,
) {
    fun message(key: String): String = messages[key] ?: key

    companion object {
        fun load(config: FileConfiguration, logger: Logger): AziRankConfig {
            val db = config.getConfigurationSection("database") ?: error("database section is missing")
            val database = DatabaseSettings(
                host = db.getString("host", "localhost")!!,
                port = db.getInt("port", 3306),
                name = db.getString("name", "life_userdata")!!,
                username = db.getString("username", "")!!,
                password = db.getString("password", "")!!,
                tablePrefix = db.getString("table-prefix", "azirank_")!!,
                poolSize = db.getInt("pool-size", 4),
                properties = db.getConfigurationSection("properties")
                    ?.getValues(false)?.mapValues { it.value.toString() } ?: emptyMap(),
            )
            require(database.tablePrefix.matches(Regex("[A-Za-z0-9_]*"))) { "table-prefix must match [A-Za-z0-9_]*" }

            val ranks = config.getStringList("ranks").map { it.lowercase() }
            require(ranks.isNotEmpty()) { "ranks is empty" }

            val rankups = mutableMapOf<String, Rankup>()
            config.getConfigurationSection("rankups")?.let { section ->
                for (key in section.getKeys(false)) {
                    val group = key.lowercase()
                    if (group !in ranks) {
                        logger.warning("rankups.$key is not listed in ranks; ignored")
                        continue
                    }
                    if (ranks.indexOf(group) == 0) {
                        logger.warning("rankups.$key is the first rank; ignored")
                        continue
                    }
                    val s = section.getConfigurationSection(key)!!
                    rankups[group] = Rankup(
                        group = group,
                        requirements = parseRequirements(s.getConfigurationSection("requirements"), "rankups.$key", logger),
                        commands = s.getStringList("commands"),
                    )
                }
            }

            val messageKeys = (config.defaults?.getConfigurationSection("messages")?.getKeys(false).orEmpty()) +
                (config.getConfigurationSection("messages")?.getKeys(false).orEmpty())
            val messages = messageKeys.associateWith { config.getString("messages.$it") ?: it }

            val modeRaw = config.getString("rankup-mode", "dry-run")!!
            val rankupMode = RankupMode.entries.firstOrNull { it.name.replace('_', '-').equals(modeRaw, true) }
                ?: throw IllegalArgumentException("rankup-mode must be run, dry-run or off: $modeRaw")

            return AziRankConfig(
                database = database,
                rankupMode = rankupMode,
                flushIntervalSeconds = config.getLong("flush-interval-seconds", 60).coerceAtLeast(5),
                checkIntervalSeconds = config.getLong("check-interval-seconds", 60).coerceAtLeast(5),
                rankupPendingMillis = config.getLong("rankup-pending-seconds", 300) * 1000,
                treatNoRankAsFirst = config.getBoolean("treat-no-rank-as-first", true),
                luckPermsServer = config.getString("luckperms-server", "life")!!,
                leaderboardPageSize = config.getInt("leaderboard-page-size", 10).coerceIn(1, 50),
                leaderboardCacheMillis = config.getLong("leaderboard-cache-seconds", 300) * 1000,
                ranks = ranks,
                rankups = rankups,
                messages = messages,
            )
        }

        private fun parseRequirements(section: ConfigurationSection?, path: String, logger: Logger): List<Requirement> {
            if (section == null) return emptyList()
            return section.getKeys(false).mapNotNull { key ->
                val raw = section.get(key).toString()
                try {
                    when (key.lowercase()) {
                        "playtime" -> Requirement.Playtime(Durations.parseToSeconds(raw))
                        "mcmmo-power-level" -> Requirement.McMMOPowerLevel(raw.trim().toLong())
                        "taxoffice" -> Requirement.TaxOffice(raw.trim().toLong())
                        else -> {
                            logger.warning("$path.requirements.$key: unknown requirement; ignored")
                            null
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("$path.requirements.$key: invalid value '$raw'", e)
                }
            }
        }
    }
}
