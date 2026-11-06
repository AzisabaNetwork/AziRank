package net.azisaba.azirank.migration

import net.azisaba.azirank.AziRank
import net.azisaba.azirank.db.PlaytimeEntry
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.context.ImmutableContextSet
import net.luckperms.api.node.Node
import net.luckperms.api.node.matcher.NodeMatcher
import net.luckperms.api.node.types.InheritanceNode
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class AutorankImporter(private val plugin: AziRank) {
    private val autorankDir get() = File(plugin.dataFolder.parentFile, "Autorank")

    class TableNotFoundException(val requested: String?, val candidates: List<String>) : Exception()

    fun importFromMySQL(table: String?, onResolved: (String) -> Unit = {}): Pair<Int, Int> {
        val tables = plugin.db.findUuidTimeTables()
        val resolved = if (table != null) {
            val (schema, name) = if ('.' in table) table.substringBefore('.') to table.substringAfter('.') else null to table
            val matches = tables.filter { it.name.equals(name, true) && (schema == null || it.schema.equals(schema, true)) }
            matches.firstOrNull { it.inCurrentSchema } ?: matches.singleOrNull()
        } else {
            val current = tables.filter { it.inCurrentSchema }
            listOf("nulltotaltime", "totaltime").firstNotNullOfOrNull { n -> current.firstOrNull { it.name.equals(n, true) } }
        }
        if (resolved == null) {
            val candidates = tables.filterNot { it.name.contains("backup", true) }
            throw TableNotFoundException(table, candidates.map { "${it.qualified} (${it.rows} rows)" })
        }
        onResolved(resolved.qualified)
        val count = plugin.db.importFromAutorankTable(resolved)
        plugin.logger.info("Imported $count rows from Autorank table `${resolved.qualified}`")
        return count to importNames()
    }

    fun importFromFile(file: File): Pair<Int, Int> {
        val yaml = YamlConfiguration.loadConfiguration(file)
        val entries = yaml.getKeys(false).mapNotNull { key ->
            val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: return@mapNotNull null
            val minutes = yaml.getLong(key, -1)
            if (minutes < 0) return@mapNotNull null
            PlaytimeEntry(uuid, null, minutes * 60)
        }
        plugin.db.importPlaytimeMax(entries)
        plugin.logger.info("Imported ${entries.size} entries from ${file.path}")
        return entries.size to importNames()
    }

    private fun importNames(): Int {
        val names = loadNames(File(autorankDir, "uuids"))
        plugin.db.fillNames(names)
        return names.size
    }

    private fun loadNames(dir: File): Map<UUID, String> {
        if (!dir.isDirectory) return emptyMap()
        val result = HashMap<UUID, Pair<String, Long>>()
        dir.listFiles { f -> f.name.endsWith(".yml") }?.forEach { f ->
            val yaml = YamlConfiguration.loadConfiguration(f)
            for (name in yaml.getKeys(false)) {
                if (name.length > 16) continue
                val uuid = runCatching { UUID.fromString(yaml.getString("$name.uuid")) }.getOrNull() ?: continue
                val updated = yaml.getLong("$name.updateTime")
                val prev = result[uuid]
                if (prev == null || prev.second < updated) result[uuid] = name to updated
            }
        }
        return result.mapValues { it.value.first }
    }

    data class Mismatch(val uuid: UUID, val name: String?, val autorank: Int, val luckPerms: Int)

    data class AuditResult(val total: Int, val same: Int, val lpLower: Int, val lpHigher: Int, val mismatches: List<Mismatch>)

    private val luckPerms get() = LuckPermsProvider.get()

    private fun isTargetContext(node: Node): Boolean {
        val ctx = node.contexts
        return ctx.isEmpty || ctx.contains("server", plugin.cfg.luckPermsServer)
    }

    fun audit(playerData: File): AuditResult {
        val ranks = plugin.cfg.ranks
        val autorank = parseCompletedRanks(playerData, ranks)

        val lp = HashMap<UUID, Int>()
        val userManager = luckPerms.userManager
        ranks.forEachIndexed { index, group ->
            userManager.searchAll(NodeMatcher.key("group.$group")).join().forEach { (uuid, nodes) ->
                if (nodes.any(::isTargetContext)) lp.merge(uuid, index, ::maxOf)
            }
        }
        val noRank = if (plugin.cfg.treatNoRankAsFirst) 0 else -1

        val names = loadNames(File(autorankDir, "uuids"))
        var same = 0
        val mismatches = ArrayList<Mismatch>()
        for ((uuid, arIndex) in autorank) {
            val lpIndex = lp[uuid] ?: noRank
            if (lpIndex == arIndex) same++ else mismatches += Mismatch(uuid, names[uuid], arIndex, lpIndex)
        }
        return AuditResult(
            total = autorank.size,
            same = same,
            lpLower = mismatches.count { it.luckPerms < it.autorank },
            lpHigher = mismatches.count { it.luckPerms > it.autorank },
            mismatches = mismatches,
        )
    }

    fun writeReport(prefix: String, rows: List<Mismatch>, extra: (Mismatch) -> String = { "" }): File {
        val ranks = plugin.cfg.ranks
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val report = File(plugin.dataFolder, "$prefix-$stamp.csv")
        report.bufferedWriter().use { w ->
            w.write("uuid,name,autorank_rank,luckperms_rank,status,result\n")
            for (m in rows) {
                val status = if (m.luckPerms < m.autorank) "LP_LOWER" else "LP_HIGHER"
                w.write("${m.uuid},${m.name ?: ""},${ranks.getOrNull(m.autorank) ?: "-"},${ranks.getOrNull(m.luckPerms) ?: "-"},$status,${extra(m)}\n")
            }
        }
        return report
    }

    fun applyAutorankRank(m: Mismatch): String {
        val ranks = plugin.cfg.ranks
        val server = plugin.cfg.luckPermsServer
        val user = luckPerms.userManager.loadUser(m.uuid).join()
        val data = user.data()
        val removed = data.toCollection()
            .filterIsInstance<InheritanceNode>()
            .filter { it.groupName.lowercase() in ranks && isTargetContext(it) }
        removed.forEach { data.remove(it) }
        val target = ranks.getOrNull(m.autorank)
        val addFirst = !(m.autorank == 0 && plugin.cfg.treatNoRankAsFirst)
        if (target != null && addFirst) {
            data.add(InheritanceNode.builder(target).context(ImmutableContextSet.of("server", server)).build())
        }
        luckPerms.userManager.saveUser(user).join()
        luckPerms.messagingService.ifPresent { it.pushUserUpdate(user) }
        val desc = "removed=[${removed.joinToString(" ") { it.groupName }}] added=${if (target != null && addFirst) target else "-"}"
        plugin.logger.info("[apply-ranks] ${user.username ?: m.uuid} (${m.uuid}): $desc")
        return desc
    }

    private fun parseCompletedRanks(file: File, ranks: List<String>): Map<UUID, Int> {
        val uuidLine = Regex("^'?([0-9a-fA-F-]{36})'?:\\s*$")
        val pathLine = Regex("^ {4}(?! )'?(.+?)'?:\\s*(\\{\\})?$")
        val pathName = Regex("^\\S+\\s+to\\s+(\\S+)$")
        val result = HashMap<UUID, Int>()
        var current: UUID? = null
        var inCompleted = false
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.trimStart().startsWith("#")) continue
                if (!line.startsWith(" ")) {
                    current = uuidLine.find(line)?.groupValues?.get(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    current?.let { result.putIfAbsent(it, 0) }
                    inCompleted = false
                    continue
                }
                if (line.startsWith("  ") && !line.startsWith("   ")) {
                    inCompleted = line.trim().startsWith("completed paths:")
                    continue
                }
                val uuid = current ?: continue
                if (!inCompleted) continue
                val path = pathLine.find(line)?.groupValues?.get(1) ?: continue
                val target = pathName.find(path)?.groupValues?.get(1)?.lowercase() ?: continue
                val idx = ranks.indexOf(target)
                if (idx >= 0) result.merge(uuid, idx, ::maxOf)
            }
        }
        return result
    }
}
