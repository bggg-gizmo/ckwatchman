package com.bggg.watchman.routing

/**
 * OSM value-condition clause syntax and ordered, three-valued resolution.
 * Reference: https://wiki.openstreetmap.org/wiki/Conditional_restrictions
 * This does not interpret opening-hours/calendar/vehicle predicates or determine road legality.
 * Callers must supply that interpretation; unknown predicates retain every possible value.
 */
object ConditionalRestriction {
    enum class Match { YES, NO, UNKNOWN }
    data class Clause(val value: String, val condition: String)
    data class Parsed(val raw: String, val clauses: List<Clause>, val problem: String?)
    data class Resolution(
        val possibleValues: Set<String>,
        val unknownConditions: List<String>,
        val syntaxProblem: String?
    ) {
        /** null means unresolved, not unrestricted. */
        val certainValue: String? get() = if (syntaxProblem == null) possibleValues.singleOrNull() else null
    }

    private const val MAX_LENGTH = 16_384
    private const val MAX_CLAUSES = 256
    private const val MAX_DEPTH = 32
    private val feetInches = Regex("[0-9]+'[0-9]+(?:\\.[0-9]+)?\"")

    fun parse(raw: String): Parsed {
        if (raw.length > MAX_LENGTH) return Parsed(raw, emptyList(), "Conditional value exceeds $MAX_LENGTH characters")
        return try {
            require(raw.isNotBlank()) { "Empty conditional value" }
            val parts = splitTopLevel(raw, ';')
            require(parts.size <= MAX_CLAUSES) { "Too many conditional clauses" }
            val clauses = parts.map { part ->
                val pair = splitTopLevel(part, '@')
                require(pair.size == 2) { "Each conditional clause needs exactly one top-level @" }
                val value = pair[0].trim()
                val condition = pair[1].trim()
                require(value.isNotEmpty()) { "Missing restriction value" }
                require(condition.isNotEmpty()) { "Missing restriction condition" }
                require(value.none { it == '(' || it == ')' } && ('"' !in value || feetInches.matches(value))) { "Invalid parenthesized or quoted restriction value" }
                require(condition != "()" && condition.any { !it.isWhitespace() && it != '(' && it != ')' }) {
                    "Empty parenthesized condition"
                }
                Clause(value, condition)
            }
            Parsed(raw, clauses, null)
        } catch (error: IllegalArgumentException) {
            Parsed(raw, emptyList(), error.message ?: "Invalid conditional syntax")
        }
    }

    /**
     * Apply clauses in source order. A known match replaces preceding choices; an unknown match
     * may or may not replace them. No condition is presumed false merely because it is unsupported.
     * A malformed tag exposes no certain value: the caller must retain/diagnose the constraint.
     */
    fun resolve(parsed: Parsed, baseValue: String, predicate: (String) -> Match): Resolution {
        if (parsed.problem != null) return Resolution(emptySet(), emptyList(), parsed.problem)
        var possible = linkedSetOf(baseValue)
        val unknown = ArrayList<String>()
        for (clause in parsed.clauses) {
            val match = try { predicate(clause.condition) } catch (_: Exception) { Match.UNKNOWN }
            when (match) {
                Match.YES -> possible = linkedSetOf(clause.value)
                Match.NO -> Unit
                Match.UNKNOWN -> { possible.add(clause.value); unknown += clause.condition }
            }
        }
        return Resolution(possible.toSet(), unknown.toList(), null)
    }

    private fun splitTopLevel(text: String, separator: Char): List<String> {
        var depth = 0
        var quoted = false
        var escaped = false
        var start = 0
        var valueStart = 0
        var inCondition = false
        val parts = ArrayList<String>()
        for ((index, char) in text.withIndex()) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
                continue
            }
            when (char) {
                '"' -> {
                    // An inch mark in a numeric restriction value is not the start of a comment.
                    if (inCondition || !feetInches.matches(text.substring(valueStart, index + 1).trim())) quoted = true
                }
                '(' -> { depth++; require(depth <= MAX_DEPTH) { "Conditional nesting exceeds $MAX_DEPTH" } }
                ')' -> { depth--; require(depth >= 0) { "Unexpected closing parenthesis at $index" } }
            }
            if (depth == 0 && char == '@') inCondition = true
            if (depth == 0 && char == ';') { valueStart = index + 1; inCondition = false }
            if (depth == 0 && char == separator) { parts += text.substring(start, index); start = index + 1 }
        }
        require(!quoted && !escaped) { "Unterminated conditional comment" }
        require(depth == 0) { "Unclosed conditional parentheses" }
        parts += text.substring(start)
        return parts
    }
}
