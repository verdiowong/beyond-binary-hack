package com.example.emergencyresponse

import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.IBinder
import kotlin.math.sqrt

class FallDetectionService : Service(), SensorEventListener {
    companion object {
        const val ACTION_FALL_DETECTED = "com.example.emergencyresponse.ACTION_FALL_DETECTED"
    }

    inner class LocalBinder : Binder() {
        fun getService(): FallDetectionService = this@FallDetectionService
    }

    private val binder = LocalBinder()

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null

    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private val alpha = 0.8f

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        accelerometer?.also { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val values = event?.values ?: return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        gravityX = alpha * gravityX + (1 - alpha) * values[0]
        gravityY = alpha * gravityY + (1 - alpha) * values[1]
        gravityZ = alpha * gravityZ + (1 - alpha) * values[2]

        val linearX = values[0] - gravityX
        val linearY = values[1] - gravityY
        val linearZ = values[2] - gravityZ
        val magnitude = sqrt((linearX * linearX + linearY * linearY + linearZ * linearZ).toDouble())

        // Member 2 TODO: Implement a stronger high-pass filter and thresholds
        // for "free-fall" (near 0g) followed by an impact spike.
        val freeFallDetected = magnitude < 1.5
        val impactDetected = magnitude > 18.0

        if (freeFallDetected || impactDetected) {
            onFallDetected()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun onFallDetected() {
        // Member 2 TODO: If a fall is detected, send a broadcast or update
        // the ViewModel to trigger the countdown.
        val intent = Intent(ACTION_FALL_DETECTED).apply {
            putExtra("event_source", "SENSOR_FALL")
        }
        sendBroadcast(intent)
    }
}
