package net.azisaba.azirank.hook

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.lang.reflect.Method
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

class McMMOHook(private val logger: Logger) {
    private var online: Method? = null
    private var offline: Method? = null

    val available get() = online != null

    fun init() {
        online = null
        offline = null
        if (Bukkit.getPluginManager().getPlugin("mcMMO")?.isEnabled != true) {
            logger.warning("mcMMO not found; mcmmo-power-level requirements will never be met")
            return
        }
        try {
            val api = Class.forName("com.gmail.nossr50.api.ExperienceAPI")
            online = api.getMethod("getPowerLevel", Player::class.java)
            offline = runCatching { api.getMethod("getPowerLevelOffline", UUID::class.java) }.getOrNull()
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to hook into mcMMO", e)
        }
    }

    fun getPowerLevel(player: Player): Long? = try {
        (online?.invoke(null, player) as? Number)?.toLong()
    } catch (e: Exception) {
        null
    }

    fun getPowerLevelOffline(uuid: UUID): Long? = try {
        (offline?.invoke(null, uuid) as? Number)?.toLong()
    } catch (e: Exception) {
        null
    }
}

class TaxOfficeHook(private val logger: Logger) {
    private var manager: Any? = null
    private var getPoints: Method? = null

    val available get() = getPoints != null

    fun init() {
        manager = null
        getPoints = null
        val plugin = Bukkit.getPluginManager().getPlugin("TaxOffice")
        if (plugin?.isEnabled != true) {
            logger.warning("TaxOffice not found; taxoffice requirements will never be met")
            return
        }
        try {
            val m = plugin.javaClass.getMethod("getPointsManager").invoke(plugin)
            getPoints = m.javaClass.getMethod("getPoints", UUID::class.java)
            manager = m
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to hook into TaxOffice", e)
        }
    }

    fun getPoints(uuid: UUID): Long? = try {
        (getPoints?.invoke(manager, uuid) as? Number)?.toLong()
    } catch (e: Exception) {
        logger.log(Level.WARNING, "Failed to get TaxOffice points of $uuid", e)
        null
    }
}
