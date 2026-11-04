package com.bggg.watchman

import com.bggg.watchman.routing.MotorcarRoadProfile as Profile
import com.bggg.watchman.routing.MotorcarTagPolicy as Access
import com.bggg.watchman.routing.RestrictionCondition
import com.bggg.watchman.routing.ConditionalRestriction.Match
import java.time.Instant
import java.time.ZoneId
import java.math.BigDecimal

fun motorcarRoadProfileSmoke() {
    val context = RestrictionCondition.Context(Instant.parse("2026-09-14T15:00:00Z"), ZoneId.of("America/Los_Angeles"),
        quantities = mapOf("height" to BigDecimal("2"), "width" to BigDecimal("1.8"), "weight" to BigDecimal("1.5")))
    val yes = Match.YES; val no = Match.NO; val unknown = Match.UNKNOWN
    fun way(vararg tags: Pair<String, String>, backward: Boolean = false, ctx: RestrictionCondition.Context = context) =
        Profile.way(mapOf(*tags), if (backward) Access.Direction.BACKWARD else Access.Direction.FORWARD, ctx)
    fun node(vararg tags: Pair<String, String>, ctx: RestrictionCondition.Context = context) = Profile.node(mapOf(*tags), Access.Direction.FORWARD, ctx)
    for (road in listOf("motorway", "motorway_link", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street", "service", "track", "road")) {
        check(way("highway" to road).publicThrough == yes)
    }
    check(way("highway" to "motorway", backward = true).publicThrough == no)
    check(way("highway" to "motorway", "oneway" to "no", backward = true).publicThrough == yes)
    check(way("highway" to "motorway_link", backward = true).publicThrough == yes)
    check(way("highway" to "residential", "junction" to "roundabout", backward = true).publicThrough == no)
    check(way("highway" to "residential", "junction" to "circular", backward = true).publicThrough == yes)
    check(way("highway" to "service", "service" to "driveway").access.accessClasses == setOf(Access.AccessClass.DESTINATION))
    check(way("highway" to "service", "service" to "driveway").publicThrough == no)
    check(way("highway" to "service", "access" to "private").permissionIndependentPassability == yes)
    check(way("highway" to "service", "access" to "private").publicThrough == no)
    check(way("highway" to "raceway").publicThrough == no)
    check(way("highway" to "footway", "access" to "yes").publicThrough == no)
    check(way("highway" to "footway", "motorcar" to "yes").publicThrough == yes)
    check(way("highway" to "footway", "motorcar:conditional" to "yes @ wet").publicThrough == unknown)
    for (type in listOf("steps", "construction", "proposed", "bus_guideway")) check(way("highway" to type, "motorcar" to "yes").publicThrough == no)
    check(way("highway" to "service", "area" to "yes").publicThrough == no)
    check(way("highway" to "residential", "smoothness" to "impassable").publicThrough == no)
    check(way("highway" to "track", "smoothness" to "horrible").publicThrough == unknown)
    check(way("highway" to "residential", "ford" to "yes").publicThrough == unknown)
    check(way("route" to "ferry", "motorcar" to "yes").publicThrough == unknown)
    check(way("route" to "ferry", "motorcar" to "yes", ctx = context.copy(facts = mapOf("ferry_operating" to yes))).publicThrough == yes)
    check(way("route" to "ferry", "access" to "yes", ctx = context.copy(facts = mapOf("ferry_operating" to yes))).publicThrough == no)
    check(way("highway" to "service", "barrier" to "fence").publicThrough == unknown)
    check(way("highway" to "residential", "maxheight" to "6'6\"").publicThrough == no)
    check(way("highway" to "unrecognized").publicThrough == unknown)
    check(node().publicThrough == yes)
    for (barrier in listOf("block", "wall", "fence", "turnstile", "cycle_barrier", "bollard")) check(node("barrier" to barrier, "access" to "yes").publicThrough == no)
    check(node("barrier" to "gate").publicThrough == yes)
    check(node("barrier" to "gate", "access" to "private").publicThrough == no)
    check(node("barrier" to "gate", "access" to "private").permissionIndependentPassability == yes)
    check(node("barrier" to "gate", "locked" to "yes").publicThrough == no)
    check(node("barrier" to "gate", "locked:conditional" to "yes @ wet").publicThrough == unknown)
    check(node("barrier" to "gate", "locked:conditional" to "yes @ Mo-Fr").publicThrough == no)
    check(node("barrier" to "gate", "locked:conditional" to "broken").publicThrough == unknown)
    check(node("barrier" to "gate", "opening_hours" to "Mo-Fr 09:00-17:00").publicThrough == no)
    check(node("barrier" to "gate", "opening_hours" to "24/7").publicThrough == yes)
    check(node("barrier" to "bollard", "bollard" to "rising").publicThrough == unknown)
    check(node("barrier" to "bollard", "bollard" to "rising", ctx = context.copy(facts = mapOf("barrier_open" to yes))).publicThrough == yes)
    check(node("barrier" to "bollard", "motorcar" to "yes", "maxwidth:physical" to "2.2").publicThrough == yes)
    check(node("barrier" to "bollard", "motorcar" to "yes", "maxwidth:physical" to "3'0\"").publicThrough == no)
    check(node("barrier" to "kerb", "kerb" to "lowered").publicThrough == yes)
    check(node("barrier" to "kerb", "kerb" to "raised").publicThrough == unknown)
    check(node("barrier" to "height_restrictor").publicThrough == unknown)
    check(node("barrier" to "height_restrictor", "maxheight" to "none").publicThrough == unknown)
    check(node("barrier" to "height_restrictor", "maxheight:conditional" to "3 @ Tu").publicThrough == unknown)
    check(node("barrier" to "height_restrictor", "maxheight:conditional" to "3 @ Mo-Fr").publicThrough == yes)
    check(node("barrier" to "height_restrictor", "maxheight" to "6'8\"").publicThrough == yes)
    check(node("barrier" to "height_restrictor", "maxheight" to "6'6\"").publicThrough == no)
    check(node("barrier" to "toll_booth").controls == setOf("toll_booth"))
    check(node("barrier" to "unknown_device").publicThrough == unknown)
    check(way("highway" to "service").access.access.single().sourceKey.contains(Profile.VERSION))
    println("Passenger road profile: full local/service/road classes, explicit motor exceptions, one-way defaults, non-driving/area/impassable exclusions, scoped access retention, point-versus-linear barriers, locked/opening conditions, dimensions, fords/ferries and unknown states PASS; restricted-component grants, timings, graph/turn search integration pending")
}
