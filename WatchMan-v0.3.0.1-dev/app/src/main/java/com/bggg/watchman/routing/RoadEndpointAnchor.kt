package com.bggg.watchman.routing

/** A projected candidate tied to one immutable source, not an entrance or an access permission. */
data class RoadEndpointAnchor(val sourceRevision:String,val arcId:Long,
    val fromNode:Long,val toNode:Long,val directedFraction:Double) {
    init {
        require(sourceRevision.isNotBlank() && arcId>0 && fromNode>0 && toNode>0 &&
            directedFraction.isFinite() && directedFraction in 0.0..1.0) { "Invalid source-bound road endpoint" }
    }
}
