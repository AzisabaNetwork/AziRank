package net.azisaba.azirank.util

object Durations {
    private val PART = Regex("(\\d+)\\s*([dhms])", RegexOption.IGNORE_CASE)
    private val FULL = Regex("^(\\s*\\d+\\s*[dhms]\\s*)+$", RegexOption.IGNORE_CASE)

    fun parseToSeconds(input: String): Long {
        val s = input.trim()
        s.toLongOrNull()?.let {
            require(it >= 0) { "negative duration" }
            return it * 60
        }
        require(FULL.matches(s)) { "invalid duration: $input" }
        return PART.findAll(s).sumOf { m ->
            val n = m.groupValues[1].toLong()
            when (m.groupValues[2].lowercase()) {
                "d" -> n * 86400
                "h" -> n * 3600
                "m" -> n * 60
                else -> n
            }
        }
    }

    fun format(seconds: Long): String {
        val total = seconds.coerceAtLeast(0) / 60
        val d = total / 1440
        val h = total % 1440 / 60
        val m = total % 60
        return buildString {
            if (d > 0) append(d).append("日 ")
            if (d > 0 || h > 0) append(h).append("時間 ")
            append(m).append("分")
        }
    }
}
