package net.azisaba.azirank.command

import net.azisaba.azirank.AziRank
import net.azisaba.azirank.db.PlaytimeEntry
import net.azisaba.azirank.migration.AutorankImporter
import net.azisaba.azirank.util.Durations
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import java.io.File
import java.util.UUID
import java.util.logging.Level

class AziRankCommand(private val plugin: AziRank) : TabExecutor {
    private data class Target(val uuid: UUID, val name: String)

    private val usage = listOf(
        "/azirank [check [player]]",
        "/azirank top [page]",
        "/azirank admin reload",
        "/azirank admin setplaytime <player> <1d 2h 30m>",
        "/azirank admin migrate playtime-mysql [table]",
        "/azirank admin migrate playtime-file [path]",
        "/azirank admin migrate audit-ranks [path]",
        "/azirank admin migrate apply-ranks <lower|higher|all> [confirm]",
    )

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (args.getOrNull(0)?.lowercase()) {
            null, "check", "times" -> {
                val name = args.getOrNull(1)
                if (name != null && name != sender.name) {
                    if (!sender.hasPermission("azirank.check.others")) return noPermission(sender)
                    check(sender, name)
                } else if (sender is Player) {
                    check(sender, sender.name)
                } else {
                    sender.sendMessage("Usage: /$label check <player>")
                }
            }
            "top", "leaderboard", "lb" -> {
                if (!sender.hasPermission("azirank.top")) return noPermission(sender)
                top(sender, args.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1)
            }
            "admin" -> {
                if (!sender.hasPermission("azirank.admin")) return noPermission(sender)
                admin(sender, args.drop(1))
            }
            else -> usage.forEach(sender::sendMessage)
        }
        return true
    }

    private fun noPermission(sender: CommandSender): Boolean {
        plugin.send(sender, "no-permission")
        return true
    }

    private fun admin(sender: CommandSender, args: List<String>) {
        when (args.getOrNull(0)?.lowercase()) {
            "reload" -> try {
                plugin.reload()
                plugin.send(sender, "reloaded")
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "Failed to reload", e)
                plugin.send(sender, "error")
            }
            "setplaytime" -> {
                if (args.size < 3) return sender.sendMessage(usage[3])
                setPlaytime(sender, args[1], args.drop(2).joinToString(" "))
            }
            "migrate" -> {
                val rest = args.drop(2).joinToString(" ").ifBlank { null }
                when (args.getOrNull(1)?.lowercase()) {
                    "playtime-mysql" -> migrateMySQL(sender, rest)
                    "playtime-file" -> migrateFile(sender, rest ?: "Autorank/data/Total_time.yml")
                    "audit-ranks" -> audit(sender, rest ?: "Autorank/playerdata/PlayerData.yml")
                    "apply-ranks" -> {
                        val scope = args.getOrNull(2)?.lowercase()
                        if (scope !in setOf("lower", "higher", "all")) return sender.sendMessage(usage.last())
                        applyRanks(sender, scope!!, args.getOrNull(3).equals("confirm", true))
                    }
                    else -> usage.drop(4).forEach(sender::sendMessage)
                }
            }
            else -> usage.drop(2).forEach(sender::sendMessage)
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        val players = {
            val prefix = args.last()
            (Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(prefix, true) } +
                plugin.names.startsWith(prefix, 100)).distinctBy { it.lowercase() }
        }
        val candidates: List<String> = when (args.size) {
            1 -> buildList {
                add("check")
                if (sender.hasPermission("azirank.top")) add("top")
                if (sender.hasPermission("azirank.admin")) add("admin")
            }
            2 -> when (args[0].lowercase()) {
                "check", "times" -> if (sender.hasPermission("azirank.check.others")) players() else emptyList()
                "admin" -> if (sender.hasPermission("azirank.admin")) listOf("reload", "setplaytime", "migrate") else emptyList()
                else -> emptyList()
            }
            3 -> if (args[0].equals("admin", true) && sender.hasPermission("azirank.admin")) {
                when (args[1].lowercase()) {
                    "setplaytime" -> players()
                    "migrate" -> listOf("playtime-mysql", "playtime-file", "audit-ranks", "apply-ranks")
                    else -> emptyList()
                }
            } else emptyList()
            4 -> if (args[0].equals("admin", true) && args[2].equals("apply-ranks", true) && sender.hasPermission("azirank.admin")) {
                listOf("lower", "higher", "all")
            } else emptyList()
            5 -> if (args[0].equals("admin", true) && args[2].equals("apply-ranks", true) && sender.hasPermission("azirank.admin")) {
                listOf("confirm")
            } else emptyList()
            else -> emptyList()
        }
        return candidates.filter { it.startsWith(args.last(), ignoreCase = true) }
    }


    private fun async(sender: CommandSender, block: () -> Unit) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                block()
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "Command failed", e)
                plugin.send(sender, "error")
            }
        })
    }

    private fun resolve(name: String): Target? {
        Bukkit.getPlayerExact(name)?.let { return Target(it.uniqueId, it.name) }
        plugin.db.findByName(name)?.let { return Target(it.uuid, it.name ?: name) }
        Bukkit.getOfflinePlayerIfCached(name)?.let { return Target(it.uniqueId, it.name ?: name) }
        return null
    }

    private fun check(sender: CommandSender, name: String) {
        // mcMMO はメインスレッドで取得
        val online = Bukkit.getPlayerExact(name)
        val mcmmo = online?.let { plugin.mcmmo.getPowerLevel(it) }
        async(sender) {
            val target = resolve(name) ?: return@async plugin.send(sender, "player-not-found", Placeholder.unparsed("player", name))
            val status = plugin.rankManager.status(target.uuid, mcmmo, online != null)
            val p = Placeholder.unparsed("player", target.name)
            val lines = buildList {
                add(plugin.message("check-header", p))
                add(plugin.message("check-rank", Placeholder.unparsed("rank", status.rankName ?: "-")))
                add(plugin.message("check-playtime", Placeholder.unparsed("playtime", status.stats.playtimeSeconds?.let(Durations::format) ?: "?")))
                val next = status.next
                if (next == null) {
                    add(plugin.message("check-max"))
                } else {
                    add(plugin.message("check-next", Placeholder.unparsed("next", next.group)))
                    for (req in next.requirements) {
                        val cur = req.current(status.stats)
                        add(
                            plugin.message(
                                "requirement-line",
                                Placeholder.component("status", plugin.message(if (req.isMet(status.stats)) "status-done" else "status-not-done")),
                                Placeholder.unparsed("description", plugin.cfg.message(req.descriptionKey)),
                                Placeholder.unparsed("current", cur?.let(req::format) ?: "?"),
                                Placeholder.unparsed("required", req.format(req.required)),
                            )
                        )
                    }
                }
            }
            lines.forEach(sender::sendMessage)
        }
    }

    @Volatile
    private var topCache: Triple<Long, Int, Map<Int, List<PlaytimeEntry>>> = Triple(0L, 0, emptyMap())

    private fun top(sender: CommandSender, page: Int) {
        async(sender) {
            val size = plugin.cfg.leaderboardPageSize
            var (at, count, pages) = topCache
            if (System.currentTimeMillis() - at > plugin.cfg.leaderboardCacheMillis) {
                at = System.currentTimeMillis()
                count = plugin.db.countPlayers()
                pages = emptyMap()
            }
            val totalPages = maxOf(1, (count + size - 1) / size)
            val p = page.coerceAtMost(totalPages)
            val entries = pages[p] ?: plugin.db.top(size, (p - 1) * size).also { pages = pages + (p to it) }
            topCache = Triple(at, count, pages)

            sender.sendMessage(
                plugin.message("top-header", Placeholder.unparsed("page", p.toString()), Placeholder.unparsed("pages", totalPages.toString()))
            )
            entries.forEachIndexed { i, e ->
                val name = e.name ?: Bukkit.getOfflinePlayer(e.uuid).name ?: e.uuid.toString()
                sender.sendMessage(
                    plugin.message(
                        "top-line",
                        Placeholder.unparsed("rank", ((p - 1) * size + i + 1).toString()),
                        Placeholder.unparsed("player", name),
                        Placeholder.unparsed("playtime", Durations.format(e.seconds)),
                    )
                )
            }
        }
    }

    private fun setPlaytime(sender: CommandSender, name: String, duration: String) {
        val seconds = try {
            Durations.parseToSeconds(duration)
        } catch (e: IllegalArgumentException) {
            sender.sendMessage("Invalid duration: $duration (例: 1d 2h 30m)")
            return
        }
        async(sender) {
            val target = resolve(name) ?: return@async plugin.send(sender, "player-not-found", Placeholder.unparsed("player", name))
            plugin.db.setPlaytime(target.uuid, target.name, seconds)
            plugin.tracker.overwrite(target.uuid, seconds)
            plugin.send(
                sender, "playtime-set",
                Placeholder.unparsed("player", target.name),
                Placeholder.unparsed("playtime", Durations.format(seconds)),
            )
        }
    }

    private fun pluginFile(sender: CommandSender, path: String): File? {
        val pluginsDir = plugin.dataFolder.parentFile
        val file = File(pluginsDir, path).canonicalFile
        if (!file.path.startsWith(pluginsDir.canonicalPath) || !file.isFile) {
            sender.sendMessage("File not found under plugins/: $path")
            return null
        }
        return file
    }

    private fun migratePlaytime(sender: CommandSender, source: String, block: (AutorankImporter) -> Pair<Int, Int>) {
        plugin.send(sender, "import-started", Placeholder.unparsed("source", source))
        async(sender) {
            plugin.tracker.flushAll()
            val (count, names) = try {
                block(AutorankImporter(plugin))
            } catch (e: AutorankImporter.TableNotFoundException) {
                sender.sendMessage(
                    if (e.requested != null) "Autorank のテーブル `${e.requested}` が見つかりません (uuid, time 列が必要)。"
                    else "Autorank の totalTime テーブルを自動検出できませんでした。テーブル名を指定してください。"
                )
                sender.sendMessage("uuid と time の列を持つテーブル (全DB): " + e.candidates.ifEmpty { listOf("なし") }.joinToString(", "))
                return@async
            }
            Bukkit.getOnlinePlayers().forEach { p -> plugin.tracker.overwrite(p.uniqueId, plugin.db.getPlaytime(p.uniqueId)) }
            plugin.send(
                sender, "import-done",
                Placeholder.unparsed("count", count.toString()),
                Placeholder.unparsed("names", names.toString()),
            )
        }
    }

    private fun migrateMySQL(sender: CommandSender, table: String?) =
        migratePlaytime(sender, "MySQL `${table ?: "(自動検出)"}`") { it.importFromMySQL(table) { t -> sender.sendMessage("取り込み元: $t") } }

    private fun migrateFile(sender: CommandSender, path: String) {
        val file = pluginFile(sender, path) ?: return
        migratePlaytime(sender, file.path) { it.importFromFile(file) }
    }

    private fun audit(sender: CommandSender, path: String) {
        val file = pluginFile(sender, path) ?: return
        plugin.send(sender, "audit-started", Placeholder.unparsed("source", file.path))
        async(sender) {
            val importer = AutorankImporter(plugin)
            val r = importer.audit(file)
            val report = importer.writeReport("autorank-audit", r.mismatches)
            plugin.send(
                sender, "audit-done",
                Placeholder.unparsed("total", r.total.toString()),
                Placeholder.unparsed("same", r.same.toString()),
                Placeholder.unparsed("lower", r.lpLower.toString()),
                Placeholder.unparsed("higher", r.lpHigher.toString()),
                Placeholder.unparsed("file", report.path),
            )
        }
    }

    private val applying = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun applyRanks(sender: CommandSender, scope: String, confirm: Boolean) {
        val file = pluginFile(sender, "Autorank/playerdata/PlayerData.yml") ?: return
        if (!applying.compareAndSet(false, true)) return sender.sendMessage("apply-ranks は実行中です。")
        plugin.send(sender, "audit-started", Placeholder.unparsed("source", file.path))
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                val importer = AutorankImporter(plugin)
                val targets = importer.audit(file).mismatches.filter {
                    when (scope) {
                        "lower" -> it.luckPerms < it.autorank
                        "higher" -> it.luckPerms > it.autorank
                        else -> true
                    }
                }
                if (!confirm) {
                    val report = importer.writeReport("autorank-apply-preview", targets) { "PREVIEW" }
                    sender.sendMessage("[プレビュー] LuckPerms を Autorank に合わせる対象: ${targets.size} 人 (scope=$scope)")
                    sender.sendMessage("詳細: ${report.path}")
                    sender.sendMessage("実行するには: /azirank admin migrate apply-ranks $scope confirm")
                    return@Runnable
                }
                sender.sendMessage("LuckPerms の書き換えを開始します: ${targets.size} 人 (scope=$scope)")
                val results = HashMap<UUID, String>()
                var failed = 0
                for (m in targets) {
                    results[m.uuid] = try {
                        importer.applyAutorankRank(m)
                    } catch (e: Exception) {
                        failed++
                        plugin.logger.log(Level.WARNING, "[apply-ranks] failed: ${m.uuid}", e)
                        "FAILED: ${e.message?.replace(',', ' ')}"
                    }
                }
                val report = importer.writeReport("autorank-apply-result", targets) { results[it.uuid] ?: "" }
                sender.sendMessage("完了: 成功 ${targets.size - failed} / 失敗 $failed")
                sender.sendMessage("詳細: ${report.path}")
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "apply-ranks failed", e)
                plugin.send(sender, "error")
            } finally {
                applying.set(false)
            }
        })
    }
}
