package net.azisaba.azirank

import net.azisaba.azirank.command.AziRankCommand
import net.azisaba.azirank.config.AziRankConfig
import net.azisaba.azirank.db.Database
import net.azisaba.azirank.hook.McMMOHook
import net.azisaba.azirank.hook.TaxOfficeHook
import net.azisaba.azirank.listener.PlayerListener
import net.azisaba.azirank.playtime.PlaytimeTracker
import net.azisaba.azirank.rank.RankManager
import net.azisaba.azirank.util.NameCache
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.logging.Level

class AziRank : JavaPlugin() {
    lateinit var cfg: AziRankConfig
        private set
    lateinit var db: Database
        private set
    val tracker = PlaytimeTracker({ db }, logger)
    val mcmmo = McMMOHook(logger)
    val taxOffice = TaxOfficeHook(logger)
    val rankManager = RankManager(this)
    val names = NameCache()

    private val tasks = mutableListOf<BukkitTask>()

    override fun onEnable() {
        saveDefaultConfig()
        cfg = AziRankConfig.load(config, logger)
        try {
            db = Database(cfg.database)
        } catch (e: Exception) {
            logger.log(
                Level.SEVERE,
                "Failed to connect to database ${cfg.database.host}:${cfg.database.port}/${cfg.database.name}; disabling AziRank",
                e,
            )
            server.pluginManager.disablePlugin(this)
            return
        }
        Bukkit.getScheduler().runTask(this, Runnable {
            mcmmo.init()
            taxOffice.init()
        })

        server.pluginManager.registerEvents(PlayerListener(this), this)
        val command = AziRankCommand(this)
        getCommand("azirank")!!.apply {
            setExecutor(command)
            tabCompleter = command
        }

        for (p in Bukkit.getOnlinePlayers()) {
            tracker.start(p.uniqueId, p.name)
            loadAsync(p.uniqueId)
        }
        scheduleTasks()
        logger.info("Loaded ${cfg.ranks.size} ranks and ${cfg.rankups.size} rankups")
    }

    fun loadAsync(uuid: java.util.UUID) {
        Bukkit.getScheduler().runTaskAsynchronously(this, Runnable {
            try {
                tracker.load(uuid)
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Failed to load playtime of $uuid (will retry)", e)
            }
        })
    }

    private fun scheduleTasks() {
        tasks.forEach { it.cancel() }
        tasks.clear()
        val flush = cfg.flushIntervalSeconds * 20
        val check = cfg.checkIntervalSeconds * 20
        tasks += Bukkit.getScheduler().runTaskTimerAsynchronously(this, Runnable { tracker.flushAll() }, flush, flush)
        tasks += Bukkit.getScheduler().runTaskTimer(this, Runnable { rankManager.checkAll() }, check, check)
        tasks += Bukkit.getScheduler().runTaskTimerAsynchronously(this, Runnable {
            try {
                names.replace(db.allNames() + Bukkit.getOnlinePlayers().map { it.name })
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Failed to load player names", e)
            }
        }, 0L, 20L * 60 * 5)
    }

    fun reload() {
        reloadConfig()
        val newCfg = AziRankConfig.load(config, logger)
        if (newCfg.database != cfg.database) {
            tracker.flushAll()
            val old = db
            db = Database(newCfg.database)
            old.close()
        }
        cfg = newCfg
        mcmmo.init()
        taxOffice.init()
        scheduleTasks()
    }

    override fun onDisable() {
        tasks.forEach { it.cancel() }
        if (::db.isInitialized) {
            tracker.save(tracker.stopAll())
            db.close()
        }
    }

    fun message(key: String, vararg resolvers: TagResolver): Component =
        MiniMessage.miniMessage().deserialize(cfg.message(key), *resolvers)

    fun send(sender: CommandSender, key: String, vararg resolvers: TagResolver) {
        sender.sendMessage(message(key, *resolvers))
    }
}
