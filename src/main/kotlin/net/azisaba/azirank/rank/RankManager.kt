package net.azisaba.azirank.rank

import net.azisaba.azirank.AziRank
import net.azisaba.azirank.config.Rankup
import net.azisaba.azirank.config.RankupMode
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.model.user.User
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

data class RankStatus(
    val rankIndex: Int,
    val rankName: String?,
    val next: Rankup?,
    val stats: PlayerStats,
)

class RankManager(private val plugin: AziRank) {
    private val config get() = plugin.cfg
    private val luckPerms by lazy { LuckPermsProvider.get() }

    private val pending = ConcurrentHashMap<UUID, Pair<Int, Long>>()
    private val running = AtomicBoolean(false)

    fun rankIndexOf(user: User): Int {
        val groups = user.getInheritedGroups(user.queryOptions).mapTo(HashSet()) { it.name.lowercase() }
        val idx = config.ranks.indexOfLast { it in groups }
        return if (idx < 0 && config.treatNoRankAsFirst) 0 else idx
    }

    fun loadRankIndex(uuid: UUID): Int {
        val user = luckPerms.userManager.getUser(uuid) ?: luckPerms.userManager.loadUser(uuid).join()
        return rankIndexOf(user)
    }

    fun nextRankup(rankIndex: Int): Rankup? =
        config.ranks.getOrNull(rankIndex + 1)?.let { config.rankups[it] }

    fun clearPending(uuid: UUID) {
        pending.remove(uuid)
    }

    fun status(uuid: UUID, mcmmo: Long?, online: Boolean): RankStatus {
        val rankIndex = loadRankIndex(uuid)
        val next = nextRankup(rankIndex)
        val stats = collectStats(uuid, mcmmo, online, next)
        return RankStatus(rankIndex, config.ranks.getOrNull(rankIndex), next, stats)
    }

    private fun collectStats(uuid: UUID, mcmmo: Long?, online: Boolean, next: Rankup?): PlayerStats {
        val reqs = next?.requirements.orEmpty()
        return PlayerStats(
            playtimeSeconds = plugin.tracker.getOnline(uuid) ?: plugin.db.getPlaytime(uuid),
            mcmmoPowerLevel = mcmmo ?: if (!online && reqs.any { it is Requirement.McMMOPowerLevel }) plugin.mcmmo.getPowerLevelOffline(uuid) else null,
            taxPoints = if (reqs.any { it is Requirement.TaxOffice }) plugin.taxOffice.getPoints(uuid) else null,
        )
    }

    private data class Snapshot(val uuid: UUID, val name: String, val mcmmo: Long?)

    fun checkAll() {
        if (config.rankupMode == RankupMode.OFF) return
        if (!running.compareAndSet(false, true)) return
        val snapshots = try {
            Bukkit.getOnlinePlayers()
                .map { Snapshot(it.uniqueId, it.name, plugin.mcmmo.getPowerLevel(it)) }
        } catch (e: Throwable) {
            running.set(false)
            throw e
        }
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                try {
                    for (s in snapshots) {
                        try {
                            checkOne(s)
                        } catch (e: Exception) {
                            plugin.logger.log(Level.WARNING, "Failed to check rank of ${s.name}", e)
                        }
                    }
                } finally {
                    running.set(false)
                }
            })
        } catch (e: Throwable) {
            running.set(false)
            throw e
        }
    }

    private fun checkOne(s: Snapshot) {
        val user = luckPerms.userManager.getUser(s.uuid)
            ?: luckPerms.userManager.loadUser(s.uuid).join()
        val rankIndex = rankIndexOf(user)
        if (rankIndex < 0) return

        pending[s.uuid]?.let { (target, at) ->
            val expired = System.currentTimeMillis() - at > config.rankupPendingMillis
            if (rankIndex >= target || expired) pending.remove(s.uuid) else return
        }

        val next = nextRankup(rankIndex) ?: return
        val stats = collectStats(s.uuid, s.mcmmo, true, next)
        if (!next.requirements.all { it.isMet(stats) }) return

        val from = config.ranks[rankIndex]
        Bukkit.getScheduler().runTask(plugin, Runnable { performRankup(s.uuid, from, rankIndex + 1, next) })
    }

    private fun performRankup(uuid: UUID, from: String, targetIndex: Int, rankup: Rankup) {
        val player: Player = Bukkit.getPlayer(uuid) ?: return
        val now = System.currentTimeMillis()
        val prev = pending[uuid]
        if (prev != null && prev.first >= targetIndex && now - prev.second <= config.rankupPendingMillis) return
        pending[uuid] = targetIndex to now

        if (config.rankupMode != RankupMode.RUN) {
            plugin.logger.info("[dry-run] Would rank up: ${player.name} ($uuid) $from -> ${rankup.group}")
            return
        }
        plugin.logger.info("Rankup: ${player.name} ($uuid) $from -> ${rankup.group}")
        for (cmd in rankup.commands) {
            val line = cmd.replace("%player%", player.name).replace("%uuid%", uuid.toString())
            try {
                val ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line)
                if (!ok) plugin.logger.warning("Rankup command failed (not executed): $line")
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "Failed to run rankup command: $line", e)
            }
        }
        val name = player.name
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                plugin.db.logRankup(uuid, name, from, rankup.group)
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "Failed to write rankup log", e)
            }
        })
    }
}
