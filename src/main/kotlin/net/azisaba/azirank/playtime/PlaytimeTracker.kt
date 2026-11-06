package net.azisaba.azirank.playtime

import net.azisaba.azirank.db.Database
import net.azisaba.azirank.db.PlaytimeEntry
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Level
import java.util.logging.Logger

class PlaytimeTracker(private val db: () -> Database, private val logger: Logger) {
    private class Session(val name: String, var lastFlushNanos: Long) {
        var stored: Long? = null
    }

    private val sessions = ConcurrentHashMap<UUID, Session>()

    fun start(uuid: UUID, name: String) {
        sessions[uuid] = Session(name, System.nanoTime())
    }

    fun load(uuid: UUID) {
        val value = db().getPlaytime(uuid)
        sessions[uuid]?.let { s -> synchronized(s) { if (s.stored == null) s.stored = value } }
    }

    fun stop(uuid: UUID): PlaytimeEntry? {
        val s = sessions.remove(uuid) ?: return null
        return synchronized(s) { drain(uuid, s) }
    }

    private fun drain(uuid: UUID, s: Session): PlaytimeEntry? {
        val now = System.nanoTime()
        val delta = (now - s.lastFlushNanos) / 1_000_000_000L
        if (delta <= 0) return null
        s.lastFlushNanos += delta * 1_000_000_000L // 端数は次回へ持ち越す
        s.stored = s.stored?.plus(delta)
        return PlaytimeEntry(uuid, s.name, delta)
    }

    fun flushAll() {
        val entries = sessions.mapNotNull { (uuid, s) -> synchronized(s) { drain(uuid, s) } }
        save(entries)
        for ((uuid, s) in sessions) {
            if (s.stored == null) {
                try {
                    load(uuid)
                } catch (e: Exception) {
                    logger.log(Level.WARNING, "Failed to load playtime of $uuid", e)
                }
            }
        }
    }

    private val retry = ConcurrentLinkedQueue<PlaytimeEntry>()

    fun save(entries: Collection<PlaytimeEntry>) {
        val all = ArrayList(entries)
        while (true) all.add(retry.poll() ?: break)
        if (all.isEmpty()) return
        val merged = all.groupBy { it.uuid }.map { (uuid, list) ->
            PlaytimeEntry(uuid, list.firstNotNullOfOrNull { it.name }, list.sumOf { it.seconds })
        }
        try {
            db().addPlaytime(merged)
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to save playtime of ${merged.size} players; will retry", e)
            retry.addAll(merged)
        }
    }

    fun getOnline(uuid: UUID): Long? {
        val s = sessions[uuid] ?: return null
        return synchronized(s) {
            s.stored?.plus((System.nanoTime() - s.lastFlushNanos) / 1_000_000_000L)
        }
    }

    fun isTracking(uuid: UUID) = sessions.containsKey(uuid)

    fun overwrite(uuid: UUID, seconds: Long) {
        retry.removeIf { it.uuid == uuid }
        sessions[uuid]?.let { s ->
            synchronized(s) {
                s.stored = seconds
                s.lastFlushNanos = System.nanoTime()
            }
        }
    }

    fun get(uuid: UUID): Long = getOnline(uuid) ?: db().getPlaytime(uuid)

    fun stopAll(): List<PlaytimeEntry> = sessions.keys.toList().mapNotNull { stop(it) }
}
