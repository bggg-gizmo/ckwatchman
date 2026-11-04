package com.bggg.watchman.alerts

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Persistent per-device encounter state with pass/reset hysteresis. */
class ProximityEncounterTracker {
    enum class State { UNSEEN, APPROACHING, VISUAL_RANGE, FOV, PASSED, RESET_ELIGIBLE }

    data class Input(
        val deviceId: String,
        val distanceMeters: Double,
        val withinBroadAlert: Boolean,
        /** Signed route delta: positive ahead, negative behind; null when no usable route. */
        val signedRouteDeltaMeters: Double?,
        val audioStage: ProximityAudioPolicy.Stage,
        val visualRangeMeters: Double,
        val resetDistanceMeters: Double,
        val nowMillis: Long
    )

    data class Result(
        val state: State,
        val newSpokenAlert: Boolean,
        val toneEligible: Boolean,
        val speechKey: String? = null
    )

    private data class Record(
        var state: State = State.UNSEEN,
        var alerted: Boolean = false,
        var closestDistanceMeters: Double = Double.POSITIVE_INFINITY,
        var lastDistanceMeters: Double = Double.POSITIVE_INFINITY,
        var increasingFixes: Int = 0,
        var passedAtMillis: Long = 0L,
        var speechDelivered: Boolean = false,
        var speechKey: String = java.util.UUID.randomUUID().toString(),
        var withinBroadAlert: Boolean = false
    )

    private val records = HashMap<String, Record>()

    @Synchronized
    fun update(input: Input): Result {
        val record = records.getOrPut(input.deviceId) { Record() }
        record.withinBroadAlert = input.withinBroadAlert
        val distance = input.distanceMeters.coerceAtLeast(0.0)

        if (record.state == State.PASSED) {
            val separated = distance >= input.resetDistanceMeters
            val oldEnough = input.nowMillis - record.passedAtMillis >= MIN_RESET_DELAY_MS
            if (separated && oldEnough) record.state = State.RESET_ELIGIBLE
            record.lastDistanceMeters = distance
            return Result(record.state, false, false)
        }

        if (record.state == State.RESET_ELIGIBLE) {
            val aheadAgain = input.signedRouteDeltaMeters?.let { it >= ROUTE_PASS_MARGIN_METERS } ?: input.withinBroadAlert
            if (!aheadAgain) {
                record.lastDistanceMeters = distance
                return Result(State.RESET_ELIGIBLE, false, false)
            }
            record.state = State.UNSEEN
            record.alerted = false
            record.speechDelivered = false
            record.speechKey = java.util.UUID.randomUUID().toString()
            record.closestDistanceMeters = Double.POSITIVE_INFINITY
            record.increasingFixes = 0
        }

        val routePassed = input.signedRouteDeltaMeters?.let { it < -ROUTE_PASS_MARGIN_METERS } == true
        val hadActiveApproach = record.state in setOf(State.APPROACHING, State.VISUAL_RANGE, State.FOV)
        record.closestDistanceMeters = minOf(record.closestDistanceMeters, distance)

        if (input.signedRouteDeltaMeters == null && hadActiveApproach &&
            record.closestDistanceMeters <= input.visualRangeMeters &&
            distance > record.lastDistanceMeters + GPS_JITTER_METERS) {
            record.increasingFixes++
        } else if (distance + GPS_JITTER_METERS < record.lastDistanceMeters) {
            record.increasingFixes = 0
        }
        val distancePassed = input.signedRouteDeltaMeters == null &&
            hadActiveApproach &&
            record.increasingFixes >= REQUIRED_INCREASING_FIXES &&
            distance >= record.closestDistanceMeters + PASS_SEPARATION_METERS

        if (routePassed || distancePassed) {
            record.state = State.PASSED
            record.passedAtMillis = input.nowMillis
            record.lastDistanceMeters = distance
            return Result(State.PASSED, false, false)
        }

        if (!input.withinBroadAlert) {
            record.lastDistanceMeters = distance
            return Result(record.state, false, false)
        }

        val nextState = when (input.audioStage) {
            ProximityAudioPolicy.Stage.FOV -> State.FOV
            ProximityAudioPolicy.Stage.VISUAL_RANGE -> State.VISUAL_RANGE
            ProximityAudioPolicy.Stage.CLOSE,
            ProximityAudioPolicy.Stage.APPROACHING,
            ProximityAudioPolicy.Stage.OUTSIDE -> State.APPROACHING
            ProximityAudioPolicy.Stage.PASSED -> State.PASSED
        }
        val newAlert = !record.alerted
        record.alerted = true
        record.state = nextState
        record.lastDistanceMeters = distance
        return Result(nextState, newAlert, input.audioStage !in setOf(
            ProximityAudioPolicy.Stage.OUTSIDE,
            ProximityAudioPolicy.Stage.PASSED
        ), record.speechKey.takeUnless { record.speechDelivered })
    }

    @Synchronized
    fun speechPending(deviceId: String, key: String): Boolean = records[deviceId]?.let {
        it.speechKey == key && !it.speechDelivered && it.withinBroadAlert &&
            it.state in setOf(State.APPROACHING, State.VISUAL_RANGE, State.FOV)
    } == true

    @Synchronized
    fun acknowledgeSpeech(deviceId: String, key: String) {
        records[deviceId]?.takeIf { it.speechKey == key }?.speechDelivered = true
    }

    @Synchronized
    fun state(deviceId: String): State = records[deviceId]?.state ?: State.UNSEEN

    @Synchronized
    fun reset() = records.clear()

    @Synchronized
    fun encode(): String = buildString {
        append(CODEC_VERSION)
        records.toSortedMap().forEach { (deviceId, record) ->
            append('\n')
            append(Base64.getUrlEncoder().withoutPadding().encodeToString(deviceId.toByteArray(StandardCharsets.UTF_8)))
            append('|').append(record.state.name)
            append('|').append(record.alerted)
            append('|').append(record.closestDistanceMeters)
            append('|').append(record.lastDistanceMeters)
            append('|').append(record.increasingFixes)
            append('|').append(record.passedAtMillis)
            append('|').append(record.speechDelivered)
            append('|').append(record.speechKey)
        }
    }

    @Synchronized
    fun restore(encoded: String?): Boolean {
        if (encoded.isNullOrBlank()) return false
        val lines = encoded.lineSequence().toList()
        val legacy = lines.firstOrNull() == LEGACY_CODEC_VERSION
        if (!legacy && lines.firstOrNull() != CODEC_VERSION) return false
        val restored = HashMap<String, Record>()
        lines.drop(1).forEach { line ->
            val fields = line.split('|')
            // Early v1 has seven fields; a corrective development writer also emitted
            // nine fields under v1. Both must migrate without losing acknowledgments.
            if (fields.size != 9 && !(legacy && fields.size == 7)) return@forEach
            val deviceId = runCatching {
                String(Base64.getUrlDecoder().decode(fields[0]), StandardCharsets.UTF_8)
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@forEach
            val state = runCatching { State.valueOf(fields[1]) }.getOrNull() ?: return@forEach
            val alerted = fields[2].toBooleanStrictOrNull() ?: return@forEach
            val closest = fields[3].toDoubleOrNull()?.takeIf { it >= 0.0 } ?: return@forEach
            val last = fields[4].toDoubleOrNull()?.takeIf { it >= 0.0 } ?: return@forEach
            val increasing = fields[5].toIntOrNull()?.takeIf { it >= 0 } ?: return@forEach
            val passedAt = fields[6].toLongOrNull()?.takeIf { it >= 0L } ?: return@forEach
            val speechDelivered = if (fields.size == 7) false
                else fields[7].toBooleanStrictOrNull() ?: return@forEach
            val speechKey = if (fields.size == 7) java.util.UUID.randomUUID().toString()
                else fields[8].takeIf { it.matches(Regex("[a-zA-Z0-9-]+")) } ?: return@forEach
            restored[deviceId] = Record(state, alerted, closest, last, increasing, passedAt,
                speechDelivered = speechDelivered, speechKey = speechKey)
        }
        records.clear()
        records.putAll(restored)
        return true
    }

    companion object {
        private const val CODEC_VERSION = "watchman-proximity-v2"
        private const val LEGACY_CODEC_VERSION = "watchman-proximity-v1"
        const val ROUTE_PASS_MARGIN_METERS = 20.0
        const val GPS_JITTER_METERS = 8.0
        const val PASS_SEPARATION_METERS = 25.0
        const val REQUIRED_INCREASING_FIXES = 2
        const val MIN_RESET_DELAY_MS = 10_000L
    }
}
