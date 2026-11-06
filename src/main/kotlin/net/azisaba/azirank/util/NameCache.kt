package net.azisaba.azirank.util

class NameCache {
    @Volatile
    private var sorted: List<Pair<String, String>> = emptyList()

    fun replace(names: Collection<String>) {
        sorted = names.distinctBy { it.lowercase() }.map { it.lowercase() to it }.sortedBy { it.first }
    }

    fun add(name: String) {
        val key = name.lowercase()
        val list = sorted
        val idx = list.binarySearchBy(key) { it.first }
        if (idx >= 0) return
        sorted = ArrayList(list).apply { add(-idx - 1, key to name) }
    }

    fun startsWith(prefix: String, limit: Int): List<String> {
        val key = prefix.lowercase()
        val list = sorted
        var i = list.binarySearchBy(key) { it.first }.let { if (it < 0) -it - 1 else it }
        val result = ArrayList<String>()
        while (i < list.size && result.size < limit && list[i].first.startsWith(key)) {
            result += list[i].second
            i++
        }
        return result
    }
}
