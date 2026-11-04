package com.bggg.watchman

import com.bggg.watchman.routing.PackedNodeReferences

fun packedNodeReferencesSmoke() {
    fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    check(PackedNodeReferences.decode(byteArrayOf(), 0).isEmpty())
    check(PackedNodeReferences.decode(bytes(2), 1).contentEquals(intArrayOf(1)))
    check(PackedNodeReferences.decode(bytes(2,2,1,0,130,2), 5).contentEquals(intArrayOf(1,2,1,1,130)))
    check(PackedNodeReferences.decode(bytes(254,255,255,255,15,251,255,255,255,15,252,255,255,255,15), 3)
        .contentEquals(intArrayOf(Int.MAX_VALUE,1,Int.MAX_VALUE)))
    // Independent writer used only by tests; production never rewrites trusted geometry.
    fun encode(values: IntArray): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        var previous = 0L
        for (item in values) {
            val delta = item.toLong() - previous
            var number = if (delta < 0) -delta * 2 - 1 else delta * 2
            do {
                val digit = (number % 128).toInt()
                number /= 128
                output.write(digit + if (number > 0) 128 else 0)
            } while (number != 0L)
            previous = item.toLong()
        }
        return output.toByteArray()
    }
    val random = java.util.Random(771237)
    repeat(1000) {
        val values = IntArray(random.nextInt(400)) { 1 + random.nextInt(Int.MAX_VALUE - 1) }
        check(PackedNodeReferences.decode(encode(values), values.size).contentEquals(values))
    }
    val full = IntArray(100_000) { if (it % 2 == 0) 1 else Int.MAX_VALUE }
    check(PackedNodeReferences.decode(encode(full), full.size).contentEquals(full))
    for ((blob, count) in listOf(bytes(128) to 1, bytes(130,0) to 1, bytes(255,255,255,255,255) to 1,
        bytes(0) to 1, bytes(2,2) to 1, bytes(2) to 0, bytes(255,255,255,255,15) to 1,
        bytes(2,3) to 2, bytes(2) to -1, bytes(2) to 100_001)) {
        check(runCatching { PackedNodeReferences.decode(blob, count) }.isFailure)
    }
    println("Packed graph geometry: exact literal vectors, extrema/repeats, 1000 deterministic random sequences, 100000-reference bound and malformed/noncanonical/trailing rejection PASS; Android SQLite reader tested separately")
}
