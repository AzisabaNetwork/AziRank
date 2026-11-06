package net.azisaba.azirank.rank

import net.azisaba.azirank.util.Durations

data class PlayerStats(
    val playtimeSeconds: Long?,
    val mcmmoPowerLevel: Long?,
    val taxPoints: Long?,
)

sealed interface Requirement {
    val descriptionKey: String
    fun current(stats: PlayerStats): Long?
    val required: Long
    fun format(value: Long): String = value.toString()

    fun isMet(stats: PlayerStats): Boolean = current(stats)?.let { it >= required } ?: false

    data class Playtime(override val required: Long) : Requirement {
        override val descriptionKey get() = "requirement-playtime"
        override fun current(stats: PlayerStats) = stats.playtimeSeconds
        override fun format(value: Long) = Durations.format(value)
    }

    data class McMMOPowerLevel(override val required: Long) : Requirement {
        override val descriptionKey get() = "requirement-mcmmo"
        override fun current(stats: PlayerStats) = stats.mcmmoPowerLevel
    }

    data class TaxOffice(override val required: Long) : Requirement {
        override val descriptionKey get() = "requirement-taxoffice"
        override fun current(stats: PlayerStats) = stats.taxPoints
    }
}
