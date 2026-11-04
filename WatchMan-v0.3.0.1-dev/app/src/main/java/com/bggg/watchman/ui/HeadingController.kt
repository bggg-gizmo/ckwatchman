package com.bggg.watchman.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.roundToInt

/** Rotation-vector compass with accelerometer + magnetometer fallback. */
class HeadingController(context: Context, private val onHeading: (Float) -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetic = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val matrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val accelValues = FloatArray(3)
    private val magneticValues = FloatArray(3)
    private var hasAccel = false
    private var hasMagnetic = false
    private var running = false

    fun start() {
        if (running) return
        running = true
        if (rotation != null) manager.registerListener(this, rotation, SensorManager.SENSOR_DELAY_UI)
        else {
            accel?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            magnetic?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    fun stop() {
        if (!running) return
        manager.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                publish(matrix)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                event.values.copyInto(accelValues, endIndex = minOf(event.values.size, accelValues.size))
                hasAccel = true
                publishFallback()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                event.values.copyInto(magneticValues, endIndex = minOf(event.values.size, magneticValues.size))
                hasMagnetic = true
                publishFallback()
            }
        }
    }

    private fun publishFallback() {
        if (!hasAccel || !hasMagnetic) return
        if (SensorManager.getRotationMatrix(matrix, null, accelValues, magneticValues)) publish(matrix)
    }

    private fun publish(matrix: FloatArray) {
        SensorManager.getOrientation(matrix, orientation)
        var degrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (degrees < 0f) degrees += 360f
        onHeading(((degrees * 10f).roundToInt() / 10f))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }
}
