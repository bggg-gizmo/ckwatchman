package com.bggg.watchman.routing

import com.bggg.watchman.data.GeoPoint
import com.bggg.watchman.data.Route
import com.bggg.watchman.data.SearchResult
import com.bggg.watchman.storage.SearchResultEvidenceJson
import com.bggg.watchman.util.Geo
import java.time.Instant
import kotlin.math.abs

/** Source-bound public STREET approach for a geocoded road/address reference. This does
 * not certify parking, a private/customer entrance, all approaches serving a facility,
 * the rest of a route's legality, or unavoidable camera exposure. Missing evidence fails
 * closed. Original address markers remain awareness data, not an invented driving leg.
 */
class VerifiedVehicleApproach private constructor(
    val original: SearchResult, val point: GeoPoint, val wayId: Long,
    val direction: MotorcarTagPolicy.Direction, val sourceEvidence: String,
    val validUntilMillis: Long
) {
    companion object {
        private val LOCAL_ROADS=setOf("primary","secondary","tertiary","unclassified","residential","living_street","service")
        internal fun resolve(route: Route, source: RoadAwareExposure.Observation, original: SearchResult?,
            origin: Boolean, geometryVerified: BooleanArray, nowMillis: Long,
            active: () -> Unit): VerifiedVehicleApproach? {
            active()
            val input=original ?: return null
            if(nowMillis<source.observedAtMillis || nowMillis>=source.validUntilMillis ||
                (if(origin)route.requestedOrigin else route.destination.point)!=input.point) return null
            val supplied=input.geocoderEvidence ?: return null
            val evidence=SearchResultEvidenceJson.decode(SearchResultEvidenceJson.encode(supplied,input.point),input.point)
                ?: return null
            if(evidence.osmType!="way" || evidence.osmId==null ||
                !(evidence.featureClass=="highway" || (evidence.featureClass=="place" &&
                    evidence.featureType in setOf("house","street","road")))) return null
            val span=if(origin) source.spans.firstOrNull { it.last>it.first } else source.spans.lastOrNull { it.last>it.first }
            span ?: return null
            val segment=if(origin)0 else route.points.lastIndex-1
            if(span.wayId!=evidence.osmId || !geometryVerified[segment]) return null
            val way=source.ways[span.wayId] ?: return null
            if(way.highway !in LOCAL_ROADS || way.tags["highway"]!=way.highway || dynamic(way.tags)) return null
            // Exact source identity establishes which road is referenced. Display names
            // may use aliases, abbreviations or another locale; they do not grant access.
            val geometry=way.nodeIds.map { active(); source.nodes[it]?.point ?: return null }
            val projection=Geo.projectOntoRoute(input.point,geometry,active) ?: return null
            if(projection.distanceToRouteMeters>NoCamsObstaclePlanner.MAX_ENDPOINT_SNAP_METERS) return null
            // A source way can double back or revisit a coordinate. Do not choose an
            // arbitrary equally close source fraction or an unobserved opposite carriageway.
            var along=0.0
            for(i in 0 until geometry.lastIndex) {
                active()
                val p=Geo.projectOntoRoute(input.point,listOf(geometry[i],geometry[i+1]),active) ?: return null
                val length=Geo.distanceMeters(geometry[i],geometry[i+1])
                if(p.distanceToRouteMeters<=projection.distanceToRouteMeters+0.25 &&
                    abs(along+p.fraction*length-projection.distanceAlongMeters)>1.0) return null
                along+=length
            }
            val endpoint=if(origin)route.points.first() else route.points.last()
            if(Geo.distanceMeters(endpoint,projection.point)>1.0) return null
            val first=Geo.projectOntoRoute(route.points[span.first],geometry,active) ?: return null
            val last=Geo.projectOntoRoute(route.points[span.last],geometry,active) ?: return null
            val progress=last.distanceAlongMeters-first.distanceAlongMeters
            if(abs(progress)<=1.0) return null
            val direction=if(progress>0) MotorcarTagPolicy.Direction.FORWARD else MotorcarTagPolicy.Direction.BACKWARD
            // No guessed local zone, vehicle dimensions, holiday or gate state. Dynamic
            // cases are refused above; remaining unsupported limits still evaluate UNKNOWN.
            val context=RestrictionCondition.Context(Instant.ofEpochMilli(nowMillis),null)
            val decision=MotorcarRoadProfile.way(way.tags,direction,context)
            if(decision.publicThrough!=ConditionalRestriction.Match.YES ||
                decision.access.unhandledTags.isNotEmpty() || decision.limits.unhandledTags.isNotEmpty()) return null
            for(n in way.nodeIds) {
                active()
                val node=source.nodes[n] ?: return null
                if(Geo.distanceMeters(node.point,projection.point)>1.0) continue
                if(dynamic(node.tags) || node.tags["barrier"]?.let { it!="no" }==true) return null
                val crossed=MotorcarRoadProfile.node(node.tags,direction,context)
                if(crossed.publicThrough!=ConditionalRestriction.Match.YES ||
                    crossed.access.unhandledTags.isNotEmpty() || crossed.limits.unhandledTags.isNotEmpty()) return null
            }
            active()
            return VerifiedVehicleApproach(input,projection.point,way.id,direction,source.sourceEvidence,source.validUntilMillis)
        }
        private fun dynamic(tags: Map<String,String>)=tags.keys.any {
            it.contains("conditional") || it=="opening_hours" || it.startsWith("opening_hours:")
        }
    }
}
