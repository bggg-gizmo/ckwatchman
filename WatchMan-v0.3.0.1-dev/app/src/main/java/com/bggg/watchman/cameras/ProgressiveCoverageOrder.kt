package com.bggg.watchman.cameras

/**
 * Deterministic breadth-first interval order for time-bounded corridor acquisition.
 *
 * The earliest work spans the complete traversal (start, end, middle, quarter points) before
 * filling smaller gaps. A short foreground deadline therefore yields geographically useful
 * partial coverage instead of a cluster at one end of a long route.
 */
object ProgressiveCoverageOrder {
    fun indices(size: Int): List<Int> {
        require(size >= 0)
        if (size == 0) return emptyList()
        if (size == 1) return listOf(0)

        val ordered = ArrayList<Int>(size)
        ordered += 0
        ordered += size - 1
        val intervals = ArrayList<Pair<Int, Int>>()
        intervals += 0 to size - 1
        var cursor = 0
        while (cursor < intervals.size) {
            val (start, end) = intervals[cursor++]
            if (end - start <= 1) continue
            val middle = (start + end) / 2
            ordered += middle
            intervals += start to middle
            intervals += middle to end
        }
        return ordered
    }

    fun <T> apply(items: List<T>): List<T> = indices(items.size).map(items::get)
}
