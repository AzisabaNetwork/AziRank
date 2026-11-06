package net.azisaba.azirank.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.azisaba.azirank.config.DatabaseSettings
import java.sql.Connection
import java.util.UUID

data class PlaytimeEntry(val uuid: UUID, val name: String?, val seconds: Long)

class Database(settings: DatabaseSettings) : AutoCloseable {
    private val dataSource: HikariDataSource
    private val playtimeTable = "`${settings.tablePrefix}playtime`"
    private val logTable = "`${settings.tablePrefix}rankup_log`"

    init {
        val config = HikariConfig().apply {
            driverClassName = org.mariadb.jdbc.Driver::class.java.name
            jdbcUrl = "jdbc:mariadb://${settings.host}:${settings.port}/${settings.name}"
            username = settings.username
            password = settings.password
            maximumPoolSize = settings.poolSize
            poolName = "AziRank"
            connectionTimeout = 10_000
            settings.properties.forEach { (k, v) -> addDataSourceProperty(k, v) }
        }
        dataSource = HikariDataSource(config)
        createTables()
    }

    private inline fun <T> use(block: (Connection) -> T): T = dataSource.connection.use(block)

    private fun createTables() = use { c ->
        c.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS $playtimeTable (
                  `uuid` CHAR(36) NOT NULL PRIMARY KEY,
                  `name` VARCHAR(16) NULL,
                  `playtime` BIGINT NOT NULL DEFAULT 0 COMMENT 'seconds',
                  `last_seen` TIMESTAMP NULL,
                  INDEX `idx_playtime` (`playtime`),
                  INDEX `idx_name` (`name`)
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS $logTable (
                  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  `uuid` CHAR(36) NOT NULL,
                  `name` VARCHAR(16) NULL,
                  `from_group` VARCHAR(64) NULL,
                  `to_group` VARCHAR(64) NOT NULL,
                  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  INDEX `idx_uuid` (`uuid`)
                )
                """.trimIndent()
            )
        }
    }

    fun getPlaytime(uuid: UUID): Long = use { c ->
        c.prepareStatement("SELECT `playtime` FROM $playtimeTable WHERE `uuid` = ?").use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    fun findByName(name: String): PlaytimeEntry? = use { c ->
        c.prepareStatement("SELECT `uuid`, `name`, `playtime` FROM $playtimeTable WHERE `name` = ? ORDER BY `last_seen` DESC LIMIT 1").use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs ->
                if (rs.next()) PlaytimeEntry(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)) else null
            }
        }
    }

    fun addPlaytime(entries: Collection<PlaytimeEntry>) {
        if (entries.isEmpty()) return
        use { c ->
            c.prepareStatement(
                "INSERT INTO $playtimeTable (`uuid`, `name`, `playtime`, `last_seen`) VALUES (?, ?, ?, CURRENT_TIMESTAMP) " +
                    "ON DUPLICATE KEY UPDATE `playtime` = `playtime` + VALUES(`playtime`), " +
                    "`name` = COALESCE(VALUES(`name`), `name`), `last_seen` = CURRENT_TIMESTAMP"
            ).use { ps ->
                for (e in entries) {
                    ps.setString(1, e.uuid.toString())
                    ps.setString(2, e.name)
                    ps.setLong(3, e.seconds)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    fun setPlaytime(uuid: UUID, name: String?, seconds: Long) = use { c ->
        c.prepareStatement(
            "INSERT INTO $playtimeTable (`uuid`, `name`, `playtime`) VALUES (?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE `playtime` = VALUES(`playtime`), `name` = COALESCE(VALUES(`name`), `name`)"
        ).use { ps ->
            ps.setString(1, uuid.toString())
            ps.setString(2, name)
            ps.setLong(3, seconds)
            ps.executeUpdate()
        }
    }

    fun importPlaytimeMax(entries: List<PlaytimeEntry>) = use { c ->
        c.autoCommit = false
        try {
            c.prepareStatement(
                "INSERT INTO $playtimeTable (`uuid`, `name`, `playtime`) VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE `playtime` = GREATEST(`playtime`, VALUES(`playtime`)), " +
                    "`name` = COALESCE(`name`, VALUES(`name`))"
            ).use { ps ->
                entries.chunked(1000).forEach { chunk ->
                    for (e in chunk) {
                        ps.setString(1, e.uuid.toString())
                        ps.setString(2, e.name)
                        ps.setLong(3, e.seconds)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
            c.commit()
        } catch (e: Exception) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    fun importFromAutorankTable(table: TableInfo): Int = use { c ->
        val ident = Regex("[A-Za-z0-9_-]+")
        require(table.schema.matches(ident) && table.name.matches(ident)) { "invalid table name: ${table.qualified}" }
        val src = "`${table.schema}`.`${table.name}`"
        val count = c.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM $src").use { rs -> rs.next(); rs.getInt(1) }
        }
        c.createStatement().use { st ->
            st.executeUpdate(
                "INSERT INTO $playtimeTable (`uuid`, `playtime`) " +
                    "SELECT LOWER(`uuid`), `time` * 60 FROM $src WHERE CHAR_LENGTH(`uuid`) = 36 " +
                    "ON DUPLICATE KEY UPDATE `playtime` = GREATEST($playtimeTable.`playtime`, VALUES(`playtime`))"
            )
        }
        count
    }

    data class TableInfo(val schema: String, val name: String, val rows: Long, val inCurrentSchema: Boolean) {
        val qualified get() = "$schema.$name"
    }

    fun findUuidTimeTables(): List<TableInfo> = use { c ->
        c.prepareStatement(
            "SELECT c.TABLE_SCHEMA, c.TABLE_NAME, MAX(t.TABLE_ROWS), MAX(c.TABLE_SCHEMA = DATABASE()) " +
                "FROM information_schema.COLUMNS c JOIN information_schema.TABLES t " +
                "ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME " +
                "WHERE LOWER(c.COLUMN_NAME) IN ('uuid', 'time') " +
                "AND c.TABLE_SCHEMA NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys') " +
                "GROUP BY c.TABLE_SCHEMA, c.TABLE_NAME HAVING COUNT(DISTINCT LOWER(c.COLUMN_NAME)) = 2 " +
                "ORDER BY c.TABLE_SCHEMA, c.TABLE_NAME"
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(TableInfo(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4) == 1))
                }
            }
        }
    }

    fun fillNames(names: Map<UUID, String>) {
        if (names.isEmpty()) return
        use { c ->
            c.prepareStatement("UPDATE $playtimeTable SET `name` = ? WHERE `uuid` = ? AND `name` IS NULL").use { ps ->
                names.entries.chunked(1000).forEach { chunk ->
                    for ((uuid, name) in chunk) {
                        ps.setString(1, name)
                        ps.setString(2, uuid.toString())
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }
    }

    fun allNames(): List<String> = use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT DISTINCT `name` FROM $playtimeTable WHERE `name` IS NOT NULL").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
    }

    fun countPlayers(): Int = use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM $playtimeTable").use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    fun top(limit: Int, offset: Int): List<PlaytimeEntry> = use { c ->
        c.prepareStatement("SELECT `uuid`, `name`, `playtime` FROM $playtimeTable ORDER BY `playtime` DESC LIMIT ? OFFSET ?").use { ps ->
            ps.setInt(1, limit)
            ps.setInt(2, offset)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(PlaytimeEntry(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)))
                }
            }
        }
    }

    fun logRankup(uuid: UUID, name: String, from: String?, to: String) = use { c ->
        c.prepareStatement("INSERT INTO $logTable (`uuid`, `name`, `from_group`, `to_group`) VALUES (?, ?, ?, ?)").use { ps ->
            ps.setString(1, uuid.toString())
            ps.setString(2, name)
            ps.setString(3, from)
            ps.setString(4, to)
            ps.executeUpdate()
        }
    }

    override fun close() = dataSource.close()
}
