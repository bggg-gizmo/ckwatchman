package com.bggg.watchman.cameras

import com.bggg.watchman.data.CameraType
import java.util.Locale

data class CameraClassification(val type: CameraType, val confidence: Int)

/** Centralized, testable classification policy for normalized camera/sensor records. */
object CameraClassifier {
    fun classify(t: Map<String, String>): CameraClassification {
        val blob = t.entries.joinToString(" ") { "${it.key}=${it.value}" }.lowercase(Locale.US)
        val enforcement = (t["enforcement"] ?: "").lowercase(Locale.US)
        val surveillanceType = (t["surveillance:type"] ?: "").lowercase(Locale.US)
        val surveillance = (t["surveillance"] ?: "").lowercase(Locale.US)
        val manufacturer = ((t["manufacturer"] ?: "") + " " + (t["brand"] ?: "")).lowercase(Locale.US)
        val product = listOf(
            t["product"], t["model"], t["device"], t["sensor:type"], t["camera:type"], t["name"]
        ).filterNotNull().joinToString(" ").lowercase(Locale.US)
        val operator = (t["operator"] ?: "").lowercase(Locale.US)

        return when {
            containsAny(blob, "signaltrace", "signal trace", "eoc plus", "eoc+") ->
                CameraClassification(CameraType.RF_SENSOR, 99)
            containsAny(blob, "flock raven", "raven gunshot", "raven acoustic", "shotspotter", "soundthinking", "gunshot detection", "acoustic sensor") ->
                CameraClassification(CameraType.ACOUSTIC, 99)
            isKnownPlateReaderVendorProduct(manufacturer, product, operator, blob) ->
                CameraClassification(CameraType.ALPR, 98)
            surveillanceType in setOf("alpr", "anpr", "lpr") || surveillance in setOf("alpr", "anpr", "lpr") ||
                "license_plate" in blob || "number_plate" in blob -> CameraClassification(CameraType.ALPR, 97)
            "flock" in manufacturer || "flock" in operator -> CameraClassification(CameraType.FLOCK, 98)
            "traffic_signals" in enforcement || "red_light" in enforcement || "red light" in blob -> CameraClassification(CameraType.RED_LIGHT, 96)
            t["highway"] == "speed_camera" || "maxspeed" in enforcement || "average_speed" in enforcement -> CameraClassification(CameraType.SPEED, 96)
            t["highway"] == "toll_gantry" || t["barrier"] == "toll_booth" -> CameraClassification(CameraType.TOLL, 95)
            containsAny(blob, "wavetronix", "traffic sensor", "vehicle detector", "rwis", "road weather") ||
                t["man_made"] == "monitoring_station" || t.keys.any { it.startsWith("monitoring:traffic") } ->
                CameraClassification(CameraType.SENSOR, 90)
            t["surveillance:zone"]?.contains("traffic", true) == true || t["surveillance"]?.equals("traffic", true) == true -> CameraClassification(CameraType.TRAFFIC, 90)
            t["man_made"] == "surveillance" || surveillance.isNotBlank() || t.keys.any { it.startsWith("surveillance:") } ->
                CameraClassification(CameraType.SURVEILLANCE, 86)
            "department of transportation" in operator || "transportation department" in operator || "highway" in operator || operator == "dot" || operator.endsWith(" dot") -> CameraClassification(CameraType.HIGHWAY, 82)
            "traffic" in blob -> CameraClassification(CameraType.TRAFFIC, 78)
            else -> CameraClassification(CameraType.OTHER, 60)
        }
    }

    private fun isKnownPlateReaderVendorProduct(
        manufacturer: String,
        product: String,
        operator: String,
        blob: String
    ): Boolean {
        if (containsAny(blob, "elsag", "mph-900", "mph900", "road reader", "roadrunner", "plate hunter")) return true
        if ("leonardo" in manufacturer && containsAny(product, "lpr", "alpr", "anpr", "elsag", "mph")) return true
        if (containsAny(blob, "vigilant solutions", "motorola vigilant", "vigilant lpr")) return true
        if (containsAny(blob, "rekor", "openalpr", "genetec autovu", "autovu", "platesmart", "plate smart")) return true
        if (containsAny(blob, "adaptive recognition", "survision", "neology") && containsAny(blob, "plate", "lpr", "alpr", "anpr")) return true
        return ("lpr" in product || "alpr" in product || "anpr" in product) &&
            (manufacturer.isNotBlank() || operator.isNotBlank())
    }

    private fun containsAny(value: String, vararg needles: String): Boolean = needles.any { it in value }

    fun priority(type: CameraType): Int = when (type) {
        CameraType.RF_SENSOR -> 102
        CameraType.ACOUSTIC -> 101
        CameraType.FLOCK -> 100
        CameraType.ALPR -> 95
        CameraType.RED_LIGHT -> 90
        CameraType.SPEED -> 88
        CameraType.SURVEILLANCE -> 80
        CameraType.HIGHWAY -> 78
        CameraType.TRAFFIC -> 75
        CameraType.SENSOR -> 70
        CameraType.TOLL -> 65
        CameraType.OTHER -> 50
    }
}
