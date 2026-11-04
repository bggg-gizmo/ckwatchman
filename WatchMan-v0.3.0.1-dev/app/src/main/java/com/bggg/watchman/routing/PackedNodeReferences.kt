package com.bggg.watchman.routing

/** Version-1 compact-store local IDs, not OSM identities. No partial result on malformed input. */
object PackedNodeReferences {
    fun decode(blob: ByteArray, count: Int): IntArray {
        require(count in 0..100_000 && blob.size <= count * 5) { "Packed geometry exceeds format bounds" }
        val result = IntArray(count)
        var position = 0
        var previous = 0L
        for (index in result.indices) {
            var encoded = 0L
            var complete = false
            for (part in 0..4) {
                require(position < blob.size) { "Truncated packed geometry" }
                val byte = blob[position++].toInt() and 255
                encoded = encoded or ((byte and 127).toLong() shl (part * 7))
                if (byte < 128) {
                    require(part == 0 || byte != 0) { "Noncanonical packed geometry" }
                    complete = true
                    break
                }
            }
            require(complete && encoded <= 4_294_967_294L) { "Oversized packed geometry delta" }
            previous += if (encoded and 1L != 0L) -(encoded / 2) - 1 else encoded / 2
            require(previous in 1..Int.MAX_VALUE.toLong()) { "Invalid packed local node identity" }
            result[index] = previous.toInt()
        }
        require(position == blob.size) { "Trailing packed geometry" }
        return result
    }
}
