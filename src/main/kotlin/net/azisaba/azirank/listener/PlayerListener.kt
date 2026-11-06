package net.azisaba.azirank.listener

import net.azisaba.azirank.AziRank
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class PlayerListener(private val plugin: AziRank) : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(e: PlayerJoinEvent) {
        plugin.tracker.start(e.player.uniqueId, e.player.name)
        plugin.names.add(e.player.name)
        plugin.loadAsync(e.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(e: PlayerQuitEvent) {
        val entry = plugin.tracker.stop(e.player.uniqueId) ?: return
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable { plugin.tracker.save(listOf(entry)) })
    }
}
