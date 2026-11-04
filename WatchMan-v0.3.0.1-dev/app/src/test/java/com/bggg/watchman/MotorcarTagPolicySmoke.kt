package com.bggg.watchman

import com.bggg.watchman.routing.MotorcarTagPolicy as Policy
import com.bggg.watchman.routing.ConditionalRestriction.Match
import com.bggg.watchman.routing.RestrictionCondition
import java.time.Instant
import java.time.ZoneId

fun motorcarTagPolicySmoke() {
    val defaults = Policy.Defaults("yes", "no", "test fixture public two-way road, not a production road profile")
    val context = RestrictionCondition.Context(Instant.parse("2026-09-14T15:00:00Z"), ZoneId.of("America/Los_Angeles"))
    fun resolve(vararg tags: Pair<String, String>, backward: Boolean = false) =
        Policy.resolve(mapOf(*tags), if (backward) Policy.Direction.BACKWARD else Policy.Direction.FORWARD, defaults, context)
    check(resolve().accessAndDirection == Match.YES)
    check(resolve("access" to "designated").publicThroughAccess == Match.UNKNOWN)
    check(resolve("access" to "no", "motorcar" to "yes").publicThroughAccess == Match.YES)
    check(resolve("access:forward" to "no", "vehicle" to "yes").publicThroughAccess == Match.YES)
    check(resolve("motor_vehicle" to "yes", "motorcar" to "private").accessClasses == setOf(Policy.AccessClass.PRIVATE))
    check(resolve("access" to "yes", "motorcar:backward" to "no").publicThroughAccess == Match.YES)
    check(resolve("access" to "yes", "motorcar:backward" to "no", backward = true).publicThroughAccess == Match.NO)
    check(resolve("access:conditional" to "no @ Mo-Fr", "motorcar" to "yes").publicThroughAccess == Match.YES)
    check(resolve("motorcar:conditional" to "no @ Mo-Fr", "motorcar:forward" to "yes").publicThroughAccess == Match.YES)
    check(resolve("motorcar:forward" to "no", "motorcar:forward:conditional" to "yes @ Mo-Fr").publicThroughAccess == Match.YES)
    check(resolve("motorcar:conditional" to "no @ Tu").publicThroughAccess == Match.YES)
    val unknown = resolve("motorcar:conditional" to "no @ wet")
    check(unknown.publicThroughAccess == Match.UNKNOWN && unknown.access.map { it.rawValue }.toSet() == setOf("yes", "no"))
    check(unknown.diagnostics.any { "wet" in it } && unknown.sourceTags["motorcar:conditional"] == "no @ wet")
    check(resolve("motorcar" to "no", "motorcar:conditional" to "no @ wet").publicThroughAccess == Match.NO)
    check(resolve("motorcar:conditional" to "no @ wet; yes @ Mo-Fr").publicThroughAccess == Match.YES)
    check(resolve("access:conditional" to "malformed", "motorcar" to "yes").publicThroughAccess == Match.YES)
    check(resolve("motorcar:conditional" to "malformed").publicThroughAccess == Match.UNKNOWN)
    check(resolve("motorcar" to "no", "motorcar:conditional" to "malformed").publicThroughAccess == Match.UNKNOWN)
    check(resolve("motorcar" to "").publicThroughAccess == Match.UNKNOWN)
    for (value in listOf("destination", "customers", "delivery", "private", "permit", "residents", "employees", "agricultural", "forestry", "disabled")) {
        val result = resolve("motorcar" to value)
        check(result.publicThroughAccess == Match.NO && Policy.AccessClass.UNKNOWN !in result.accessClasses)
        check(result.access.single().rawValue == value) // Restricted permission survives; never promoted to through access.
    }
    for (value in listOf("yes", "permissive", "discouraged", "designated")) check(resolve("motorcar" to value).publicThroughAccess == Match.YES)
    check(resolve("oneway" to "yes").directionAllowed == Match.YES)
    check(resolve("oneway" to "yes", backward = true).directionAllowed == Match.NO)
    check(resolve("oneway" to "-1").directionAllowed == Match.NO)
    check(resolve("oneway" to "-1", backward = true).directionAllowed == Match.YES)
    check(resolve("oneway" to "yes", "oneway:motorcar" to "no", backward = true).directionAllowed == Match.YES)
    check(resolve("oneway" to "yes", "oneway:bicycle" to "no", backward = true).accessAndDirection == Match.NO)
    for (value in listOf("reversible", "alternating", "unknown", "")) check(resolve("oneway" to value).directionAllowed == Match.UNKNOWN)
    check(resolve("oneway" to "yes", "oneway:conditional" to "-1 @ Mo-Fr").directionAllowed == Match.NO)
    check(resolve("oneway" to "yes", "oneway:conditional" to "-1 @ wet").directionAllowed == Match.UNKNOWN)
    check(resolve("oneway" to "no", "oneway:conditional" to "yes @ wet").directionAllowed == Match.YES) // Forward permitted in both possibilities.
    check(resolve("oneway" to "no", "oneway:conditional" to "yes @ wet", backward = true).directionAllowed == Match.UNKNOWN)
    check(resolve("motorcar:lanes" to "yes|no").unhandledTags == mapOf("motorcar:lanes" to "yes|no"))
    check(resolve("motorcar:lanes" to "yes|no").accessAndDirection == Match.UNKNOWN)
    check(resolve("access:conditional:forward" to "no @ wet").accessAndDirection == Match.UNKNOWN)
    check(Policy.resolve(emptyMap(), Policy.Direction.FORWARD, Policy.Defaults(null, null, "undetermined"), context).accessAndDirection == Match.UNKNOWN)
    check(runCatching { Policy.Defaults("yes", "no", "") }.isFailure)
    val failed = Policy.resolve(mapOf("access:conditional" to "no @ wet"), Policy.Direction.FORWARD, defaults) { error("Unavailable") }
    check(failed.publicThroughAccess == Match.UNKNOWN)
    // Every hierarchy level overrides all less-specific levels even when their direction/condition matches.
    val modes = listOf("access", "vehicle", "motor_vehicle", "motorcar")
    for (i in modes.indices) for (j in i + 1 until modes.size) {
        val tags = mapOf("${modes[i]}:forward:conditional" to "no @ Mo-Fr", modes[j] to "yes")
        check(Policy.resolve(tags, Policy.Direction.FORWARD, defaults, context).publicThroughAccess == Match.YES)
    }
    // Resolver copies provenance; external mutation cannot change an already evaluated result.
    val tags = mutableMapOf("access" to "private")
    val frozen = Policy.resolve(tags, Policy.Direction.FORWARD, defaults, context)
    tags["access"] = "yes"
    check(frozen.sourceTags["access"] == "private" && frozen.publicThroughAccess == Match.NO)
    println("Motorcar tag policy: mode/direction/conditional precedence, restricted access retention, forward/reverse/dynamic one-way, unresolved/malformed constraints, provenance and lane deferral PASS; barriers/turns/road-profile/search integration still separate")
}
