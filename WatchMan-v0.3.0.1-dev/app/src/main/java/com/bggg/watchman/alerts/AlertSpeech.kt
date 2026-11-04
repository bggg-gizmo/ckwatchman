package com.bggg.watchman.alerts

import com.bggg.watchman.data.CameraPoi
import com.bggg.watchman.data.CameraType
import java.util.Locale

/** Concise, driving-friendly speech names derived from normalized type and product evidence. */
object AlertSpeech {
    fun deviceName(camera: CameraPoi): String {
        val evidence = buildString {
            append(camera.name.orEmpty()).append(' ')
            append(camera.manufacturer.orEmpty()).append(' ')
            append(camera.operator.orEmpty()).append(' ')
            camera.tags.forEach { (key, value) -> append(key).append('=').append(value).append(' ') }
        }.lowercase(Locale.US)

        return when {
            containsAny(evidence, "signaltrace", "signal trace", "eoc plus", "eoc+") ->
                "SignalTrace EOC Plus RF sensor"
            "raven" in evidence && "flock" in evidence ->
                "Flock Raven acoustic sensor"
            containsAny(evidence, "shotspotter", "soundthinking") ->
                "SoundThinking ShotSpotter acoustic sensor"
            containsAny(evidence, "leonardo", "elsag", "mph-900", "mph900") ->
                "Leonardo ELSAG plate reader"
            containsAny(evidence, "motorola vigilant", "vigilant solutions", "vigilant lpr") ->
                "Motorola Vigilant plate reader"
            containsAny(evidence, "rekor", "openalpr") ->
                "Rekor OpenALPR plate reader"
            containsAny(evidence, "genetec autovu", "autovu") ->
                "Genetec AutoVu plate reader"
            containsAny(evidence, "platesmart", "plate smart") ->
                "PlateSmart plate reader"
            containsAny(evidence, "adaptive recognition") ->
                "Adaptive Recognition plate reader"
            containsAny(evidence, "survision") ->
                "Survision plate reader"
            containsAny(evidence, "neology") ->
                "Neology plate reader"
            containsAny(evidence, "wavetronix") ->
                "Wavetronix traffic sensor"
            else -> typeName(camera.type)
        }
    }

    private fun typeName(type: CameraType): String = when (type) {
        CameraType.RED_LIGHT -> "red-light camera"
        CameraType.SPEED -> "speed camera"
        CameraType.TRAFFIC -> "traffic monitoring device"
        CameraType.HIGHWAY -> "highway camera"
        CameraType.ALPR -> "license plate reader"
        CameraType.FLOCK -> "Flock Safety plate reader"
        CameraType.ACOUSTIC -> "acoustic sensor"
        CameraType.RF_SENSOR -> "RF electronic-signature sensor"
        CameraType.SURVEILLANCE -> "surveillance camera"
        CameraType.TOLL -> "toll gantry sensor"
        CameraType.SENSOR -> "traffic sensor"
        CameraType.OTHER -> "roadside camera or sensor"
    }

    private fun containsAny(value: String, vararg needles: String): Boolean =
        needles.any { it in value }
}
