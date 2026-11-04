package com.bggg.watchman.routing

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.bggg.watchman.routing.ConditionalRestriction.Match

/**
 * Explicit route-local condition evaluation. Never consults the host clock, zone or holiday calendar.
 * Implements bounded weekday/month selectors, clock windows, PH/SH exclusions, AND and supplied
 * facts. Other opening-hours rules (including arbitrary semicolon overrides, solar times, dates
 * and comments requiring local knowledge) remain UNKNOWN, not permission to traverse a road.
 * See https://wiki.openstreetmap.org/wiki/Conditional_restrictions .
 */
object RestrictionCondition {
    data class Context(
        val instant: Instant,
        val routeZone: ZoneId?,
        /** Keys are explicit condition tokens such as wet, snow, destination or hazmat. */
        val facts: Map<String, Match> = emptyMap(),
        /** OSM units: weight/axleload in tonnes; length/width/height in metres; counts unitless. */
        val quantities: Map<String, BigDecimal> = emptyMap(),
        val holiday: (String, LocalDate) -> Match = { _, _ -> Match.UNKNOWN }
    )
    data class Evaluation(val match: Match, val unresolved: List<String> = emptyList())
    private data class Window(val start: Int, val end: Int)
    private data class Selector(val dayParts: List<String>? = null, val months: Set<Int>? = null)
    private val weekdays = listOf("mo", "tu", "we", "th", "fr", "sa", "su")
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val clock = Regex("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})")
    private val comparison = Regex("([a-z][a-z0-9_:]*)\\s*(<=|>=|=|<|>)\\s*([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
    private val comment = Regex("^(.*?)\\s*\"(?:[^\"\\\\]|\\\\.)*\"$", RegexOption.DOT_MATCHES_ALL)

    fun evaluate(raw: String, context: Context): Evaluation {
        if (raw.length > 16_384) return unknown("Condition exceeds input bound")
        return evaluatePart(raw.trim(), context, 0)
    }

    private fun unknown(reason: String) = Evaluation(Match.UNKNOWN, listOf(reason))
    private fun and(a: Match, b: Match): Match = when {
        a == Match.NO || b == Match.NO -> Match.NO
        a == Match.YES && b == Match.YES -> Match.YES
        else -> Match.UNKNOWN
    }
    private fun or(a: Match, b: Match): Match = when {
        a == Match.YES || b == Match.YES -> Match.YES
        a == Match.NO && b == Match.NO -> Match.NO
        else -> Match.UNKNOWN
    }
    private fun invert(a: Match) = when (a) { Match.YES -> Match.NO; Match.NO -> Match.YES; Match.UNKNOWN -> Match.UNKNOWN }

    private fun evaluatePart(raw: String, context: Context, depth: Int): Evaluation {
        if (depth > 32 || raw.isBlank()) return unknown("Empty or excessively nested condition")
        var level = 0
        var quoted = false
        var escaped = false
        var outerEnd = -1
        val cuts = ArrayList<Int>()
        for (i in raw.indices) {
            val c = raw[i]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
                continue
            }
            when (c) {
                '"' -> quoted = true
                '(' -> { level++; if (level > 32) return unknown("Excessive condition nesting") }
                ')' -> { level--; if (level < 0) return unknown("Unbalanced condition"); if (level == 0 && outerEnd < 0) outerEnd = i }
            }
            if (level == 0 && i > 0 && i + 3 < raw.length && raw.regionMatches(i, "AND", 0, 3, true) &&
                raw[i - 1].isWhitespace() && raw[i + 3].isWhitespace()) cuts += i
        }
        if (quoted || level != 0) return unknown("Unbalanced condition")
        if (raw.startsWith('(') && outerEnd == raw.lastIndex) return evaluatePart(raw.substring(1, raw.lastIndex).trim(), context, depth + 1)
        if (cuts.isNotEmpty()) {
            if (cuts.size > 255) return unknown("Too many combined conditions")
            val results = ArrayList<Evaluation>()
            var start = 0
            for (cut in cuts) { results += evaluatePart(raw.substring(start, cut).trim(), context, depth + 1); start = cut + 3 }
            results += evaluatePart(raw.substring(start).trim(), context, depth + 1)
            return Evaluation(results.fold(Match.YES) { a, b -> and(a, b.match) }, results.flatMap { it.unresolved }.distinct())
        }
        comment.matchEntire(raw)?.let {
            val prefix = it.groupValues[1].trim()
            val result = if (prefix.isEmpty()) Evaluation(Match.YES) else evaluatePart(prefix, context, depth + 1)
            return Evaluation(and(result.match, Match.UNKNOWN), result.unresolved + "Local comment requires evidence: $raw")
        }
        comparison.matchEntire(raw)?.let {
            val key = it.groupValues[1].lowercase(java.util.Locale.ROOT)
            val actual = context.quantities[key] ?: return unknown("Missing vehicle quantity: $key")
            val threshold = it.groupValues[3].toBigDecimalOrNull() ?: return unknown("Invalid numeric threshold")
            if (actual.signum() < 0) return unknown("Invalid negative vehicle quantity: $key")
            val order = actual.compareTo(threshold)
            val yes = when (it.groupValues[2]) { "<" -> order < 0; ">" -> order > 0; "<=" -> order <= 0; ">=" -> order >= 0; else -> order == 0 }
            return Evaluation(if (yes) Match.YES else Match.NO)
        }
        val token = raw.lowercase(java.util.Locale.ROOT)
        if (Regex("[a-z][a-z0-9_:]*").matches(token) && token !in weekdays && token !in months && token != "ph" && token != "sh") {
            return context.facts[token]?.let { if (it == Match.UNKNOWN) unknown("Unresolved fact: $token") else Evaluation(it) }
                ?: unknown("Missing or unsupported fact: $token")
        }
        return schedule(raw, context)
    }

    private fun schedule(raw: String, context: Context): Evaluation {
        val parts = raw.split(';').map { it.trim() }
        // General opening-hours rule replacement requires a full rule engine; never guess union semantics.
        if (parts.size > 3 || parts.drop(1).any { !Regex("(?:PH|SH)\\s+off", RegexOption.IGNORE_CASE).matches(it) }) {
            return unknown("Unsupported opening-hours rule sequence: $raw")
        }
        val base = parts[0]
        val windows = ArrayList<Window>()
        val firstClock = Regex("\\d{1,2}:").find(base)
        val selectorText = if (firstClock == null) base else base.substring(0, firstClock.range.first).trim()
        val selector = if (base == "24/7") Selector() else parseSelector(selectorText)
            ?: return unknown("Unsupported date/day selector: $selectorText")
        if (firstClock != null) {
            val spans = base.substring(firstClock.range.first).split(',')
            if (spans.size > 256) return unknown("Too many time windows")
            for (span in spans) {
                val match = clock.matchEntire(span.trim()) ?: return unknown("Unsupported time window: $span")
                val h1 = match.groupValues[1].toInt(); val m1 = match.groupValues[2].toInt()
                val h2 = match.groupValues[3].toInt(); val m2 = match.groupValues[4].toInt()
                if (h1 !in 0..23 || m1 !in 0..59 || h2 !in 0..24 || m2 !in 0..59 || (h2 == 24 && m2 != 0)) return unknown("Invalid clock boundary: $span")
                val start = h1 * 60 + m1; var end = h2 * 60 + m2
                if (start == end) return unknown("Ambiguous equal clock boundaries: $span")
                if (end < start) end += 1440
                windows += Window(start * 60, end * 60)
            }
        } else if (base != "24/7" && selectorText.isBlank()) return unknown("Empty time selector")
        val zone = context.routeZone ?: return unknown("Missing route time zone")
        val local = context.instant.atZone(zone)
        val date = local.toLocalDate()
        val second = local.toLocalTime().toSecondOfDay()
        val missing = ArrayList<String>()
        fun holiday(kind: String, day: LocalDate): Match {
            val result = try { context.holiday(kind, day) } catch (_: Exception) { Match.UNKNOWN }
            if (result == Match.UNKNOWN) missing += "Missing $kind calendar: $day"
            return result
        }
        fun selected(day: LocalDate): Match {
            selector.months?.let { return if (day.monthValue in it) Match.YES else Match.NO }
            return selector.dayParts?.fold(Match.NO) { result, part ->
                val value = if (part == "ph" || part == "sh") holiday(part.uppercase(java.util.Locale.ROOT), day)
                    else if (day.dayOfWeek.value - 1 in cyclicRange(part, weekdays)!!) Match.YES else Match.NO
                or(result, value)
            } ?: Match.YES
        }
        var active = if (windows.isEmpty()) selected(date) else Match.NO
        for (window in windows) {
            if (second >= window.start && second < window.end) active = or(active, selected(date))
            if (window.end > 86400 && second < window.end - 86400) active = or(active, selected(date.minusDays(1)))
        }
        for (exclusion in parts.drop(1)) {
            val kind = exclusion.take(2).uppercase(java.util.Locale.ROOT)
            active = and(active, invert(holiday(kind, date)))
        }
        return Evaluation(active, missing.distinct())
    }

    private fun parseSelector(raw: String): Selector? {
        if (raw.isBlank()) return Selector()
        val parts = raw.lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), "").split(',')
        if (parts.size > 256) return null
        if (parts.all { it == "ph" || it == "sh" || cyclicRange(it, weekdays) != null }) return Selector(dayParts = parts)
        val ranges = parts.map { cyclicRange(it, months) ?: return null }
        return Selector(months = ranges.flatten().map { it + 1 }.toSet())
    }

    private fun cyclicRange(raw: String, names: List<String>): Set<Int>? {
        val ends = raw.split('-')
        if (ends.size !in 1..2) return null
        val start = names.indexOf(ends[0]); val end = names.indexOf(ends.last())
        if (start < 0 || end < 0) return null
        return if (start <= end) (start..end).toSet() else ((start until names.size) + (0..end)).toSet()
    }
}
