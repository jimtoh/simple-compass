package com.example.simplecompass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometerReading = FloatArray(3)
    private var magnetometerReading = FloatArray(3)

    private val rotationMatrix = FloatArray(9)
    private val adjustedRotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var currentAzimuth = 0f
    private var calibratedOffset = 0f

    // Smoothing state
    private var filteredAzimuth = Float.NaN
    private val alpha = 0.15f // smoothing factor (0..1), lower = smoother
    private var lastUiUpdateTime = 0L

    private lateinit var needle: ImageView
    private lateinit var headingText: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    private var isCalibrating = false

    // Robust calibration collection
    private var isCollecting = false
    private var collectStartMs = 0L
    private val collectDurationMs = 1500L
    private val collectSamples: MutableList<Float> = mutableListOf()

    // Prefer rotation vector if available (more stable orientation)
    private var useRotationVector = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        needle = findViewById(R.id.needle)
        headingText = findViewById(R.id.headingText)
        val calibrate = findViewById<com.google.android.material.button.MaterialButton>(R.id.calibrateButton)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Restore state on configuration change (e.g., rotation)
        if (savedInstanceState != null) {
            isCalibrating = savedInstanceState.getBoolean("isCalibrating", false)
            calibratedOffset = savedInstanceState.getFloat("calibratedOffset", 0f)
        }
        // Reflect state in UI
        calibrate.setText(if (isCalibrating) R.string.finish_calibration else R.string.calibrate)

        calibrate.setOnClickListener {
            if (!isCalibrating) {
                // Step 1: show instruction and enter calibrating mode
                isCalibrating = true
                Toast.makeText(this, R.string.calibration_instruction, Toast.LENGTH_LONG).show()
                calibrate.setText(R.string.finish_calibration)
            } else if (!isCollecting) {
                // Step 2: user confirms completion -> start short stable collection window
                Toast.makeText(this, R.string.calibration_hold_steady, Toast.LENGTH_SHORT).show()
                isCollecting = true
                collectStartMs = System.currentTimeMillis()
                collectSamples.clear()
                // Temporarily disable button to prevent re-clicks during collection
                calibrate.isEnabled = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Prefer rotation vector
        val rotVec = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotVec != null) {
            useRotationVector = true
            sensorManager.registerListener(
                this, rotVec, SensorManager.SENSOR_DELAY_UI, SensorManager.SENSOR_DELAY_UI
            )
        } else {
            useRotationVector = false
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelerometer ->
                sensorManager.registerListener(
                    this, accelerometer, SensorManager.SENSOR_DELAY_UI, SensorManager.SENSOR_DELAY_UI
                )
            }
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { magneticField ->
                sensorManager.registerListener(
                    this, magneticField, SensorManager.SENSOR_DELAY_UI, SensorManager.SENSOR_DELAY_UI
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isCalibrating", isCalibrating)
        outState.putFloat("calibratedOffset", calibratedOffset)
        // Do not try to persist in-progress short collection; user can retry after rotation
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    private fun normalizeDegrees(angle: Float): Float {
        var a = angle % 360f
        if (a < 0) a += 360f
        return a
    }

    // Compute shortest signed angular difference from a to b in degrees (-180..180]
    private fun shortestDelta(from: Float, to: Float): Float {
        var diff = (to - from) % 360f
        if (diff > 180f) diff -= 360f
        if (diff <= -180f) diff += 360f
        return diff
    }

    private fun circularMeanDeg(samples: List<Float>): Float {
        if (samples.isEmpty()) return Float.NaN
        var sumSin = 0.0
        var sumCos = 0.0
        for (d in samples) {
            val r = Math.toRadians(d.toDouble())
            sumCos += kotlin.math.cos(r)
            sumSin += kotlin.math.sin(r)
        }
        val mean = Math.toDegrees(Math.atan2(sumSin, sumCos)).toFloat()
        return normalizeDegrees(mean)
    }

    private fun circularStdDevDeg(samples: List<Float>, mean: Float): Float {
        if (samples.isEmpty()) return Float.NaN
        var acc = 0.0
        for (d in samples) {
            val delta = shortestDelta(mean, d).toDouble()
            acc += delta * delta
        }
        val variance = acc / samples.size
        return kotlin.math.sqrt(variance).toFloat()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        } else {
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                accelerometerReading = event.values.clone()
            } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                magnetometerReading = event.values.clone()
            }
        }

        val success = if (useRotationVector && event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            true
        } else {
            SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)
        }
        if (success) {
            // Remap coordinates based on current display rotation so azimuth is consistent in portrait/landscape
            @Suppress("DEPRECATION")
            val rotation = windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0
            val (axisX, axisY) = when (rotation) {
                Surface.ROTATION_0 -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
            SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, adjustedRotationMatrix)

            SensorManager.getOrientation(adjustedRotationMatrix, orientationAngles)
            // Azimuth is angle around the Z-axis. Convert radians to degrees.
            val azimuthRad = orientationAngles[0]
            var azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
            if (azimuthDeg < 0) azimuthDeg += 360f

            currentAzimuth = azimuthDeg
            val target = normalizeDegrees(azimuthDeg - calibratedOffset)

            // Initialize filter at first reading
            if (filteredAzimuth.isNaN()) {
                filteredAzimuth = target
            } else {
                val delta = shortestDelta(filteredAzimuth, target)
                filteredAzimuth = normalizeDegrees(filteredAzimuth + alpha * delta)
            }

            val now = System.currentTimeMillis()
            // Update UI at ~10Hz or when significant change (>1 degree) happens
            val display = filteredAzimuth
            val shouldUpdate = (now - lastUiUpdateTime) > 100 || abs(shortestDelta(-needle.rotation, display)) > 1f
            if (shouldUpdate) {
                lastUiUpdateTime = now

                // Update heading text with rounded integer degrees
                val rounded = display.roundToInt() % 360
                headingText.text = getString(R.string.heading, rounded.toFloat())

                // Set needle rotation directly to avoid jittery animations
                needle.rotation = -display
            }

            // Handle robust calibration collection
            if (isCollecting) {
                collectSamples.add(display)
                if (now - collectStartMs >= collectDurationMs) {
                    isCollecting = false
                    val mean = circularMeanDeg(collectSamples)
                    val stddev = circularStdDevDeg(collectSamples, mean)
                    val calibrateButton = findViewById<com.google.android.material.button.MaterialButton>(R.id.calibrateButton)
                    calibrateButton.isEnabled = true
                    // Accept only if device was relatively stable
                    if (!mean.isNaN() && stddev <= 5f) {
                        // Do NOT change heading offset; calibration is for sensor settling only.
                        // Seed filtered heading to the measured mean to avoid jump.
                        filteredAzimuth = mean
                        isCalibrating = false
                        Toast.makeText(this, R.string.calibration_completed, Toast.LENGTH_SHORT).show()
                        calibrateButton.setText(R.string.calibrate)
                    } else {
                        Toast.makeText(this, R.string.calibration_unstable, Toast.LENGTH_SHORT).show()
                        // Stay in calibrating mode so user can try again
                        isCalibrating = true
                        calibrateButton.setText(R.string.finish_calibration)
                    }
                    collectSamples.clear()
                }
            }
        }
    }
}
