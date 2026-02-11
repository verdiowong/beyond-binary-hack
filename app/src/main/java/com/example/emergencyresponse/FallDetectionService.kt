package com.example.emergencyresponse

import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

class FallDetectionService : Service(), SensorEventListener {
    companion object {
        const val ACTION_FALL_DETECTED = "com.example.emergencyresponse.ACTION_FALL_DETECTED"
        const val EXTRA_TRIGGER_TYPE = "trigger_type"
        const val TRIGGER_FALL = "fall"
        const val TRIGGER_TREMOR = "severe_tremor"

        private const val GRAVITY_EARTH = 9.81f

        // Free-fall -> impact -> stillness thresholds.
        private const val FREE_FALL_THRESHOLD = 3.0f
        private const val FREE_FALL_MIN_MS = 220L
        private const val IMPACT_THRESHOLD = 22.0f
        private const val IMPACT_WINDOW_MS = 1500L
        private const val STILLNESS_WINDOW_MS = 5000L
        private const val STILLNESS_MAX_LINEAR = 1.8f
        private const val STILLNESS_RMS_LINEAR = 1.0f

        // Tremor thresholds.
        private const val TREMOR_WINDOW_MS = 3000L
        private const val TREMOR_MIN_RMS = 1.35f
        private const val TREMOR_MIN_ZERO_CROSS = 16
        private const val TREMOR_MAX_ZERO_CROSS = 80
        private const val TREMOR_MIN_ACTIVE_SAMPLES = 60
        private const val TREMOR_SUSTAINED_WINDOWS = 3

        // Filters and sampling.
        private const val LPF_ALPHA_RAW = 0.15f
        private const val LPF_ALPHA_GRAVITY = 0.08f
        private const val HPF_ALPHA_TREMOR = 0.88f
        private const val SAMPLE_PERIOD_US = 20_000 // 50Hz
        private const val MAX_REPORT_LATENCY_US = 200_000
        private const val ALERT_COOLDOWN_MS = 20_000L
    }

    inner class LocalBinder : Binder() {
        fun getService(): FallDetectionService = this@FallDetectionService
    }

    private val binder = LocalBinder()
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var gravitySensor: Sensor? = null
    private var linearAccelerationSensor: Sensor? = null
    private var isRegistered = false

    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = GRAVITY_EARTH
    private var hasGravitySensorReading = false
    private var rawMagLpf = GRAVITY_EARTH
    private var linearMag = 0f

    private var freeFallStartMs: Long? = null
    private var freeFallConfirmedMs: Long? = null
    private var impactDetectedMs: Long? = null
    private var stillnessStartMs: Long? = null
    private var stillnessSamples = 0
    private var stillnessEnergy = 0.0
    private var stillnessPeak = 0f

    private val tremorTimes = ArrayDeque<Long>()
    private val tremorHpX = ArrayDeque<Float>()
    private val tremorHpMag = ArrayDeque<Float>()
    private var tremorLpfX = 0f
    private var tremorLpfY = 0f
    private var tremorLpfZ = 0f
    private var tremorWindowHits = 0
    private var lastTremorEvalMs = 0L
    private var lastAlertMs = 0L

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        linearAccelerationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        registerSensorsIfNeeded()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterSensors()
        super.onDestroy()
    }

    private fun registerSensorsIfNeeded() {
        if (isRegistered) return
        val mgr = sensorManager ?: return

        accelerometer?.let {
            mgr.registerListener(this, it, SAMPLE_PERIOD_US, MAX_REPORT_LATENCY_US)
        }

        val linear = linearAccelerationSensor
        if (linear != null) {
            mgr.registerListener(this, linear, SAMPLE_PERIOD_US, MAX_REPORT_LATENCY_US)
        } else {
            gravitySensor?.let {
                mgr.registerListener(this, it, SAMPLE_PERIOD_US, MAX_REPORT_LATENCY_US)
            }
        }
        isRegistered = true
    }

    private fun unregisterSensors() {
        if (!isRegistered) return
        sensorManager?.unregisterListener(this)
        isRegistered = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        val values = e.values
        val nowMs = SystemClock.elapsedRealtime()

        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                gravityX = values[0]
                gravityY = values[1]
                gravityZ = values[2]
                hasGravitySensorReading = true
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                handleLinearSample(values[0], values[1], values[2], nowMs)
            }

            Sensor.TYPE_ACCELEROMETER -> {
                handleAccelerometerSample(values[0], values[1], values[2], nowMs)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun handleAccelerometerSample(ax: Float, ay: Float, az: Float, nowMs: Long) {
        val rawMagnitude = vectorMagnitude(ax, ay, az)
        rawMagLpf += LPF_ALPHA_RAW * (rawMagnitude - rawMagLpf)
        processFallPipeline(rawMagLpf, nowMs)

        if (linearAccelerationSensor == null) {
            if (!hasGravitySensorReading) {
                gravityX += LPF_ALPHA_GRAVITY * (ax - gravityX)
                gravityY += LPF_ALPHA_GRAVITY * (ay - gravityY)
                gravityZ += LPF_ALPHA_GRAVITY * (az - gravityZ)
            }
            val lx = ax - gravityX
            val ly = ay - gravityY
            val lz = az - gravityZ
            handleLinearSample(lx, ly, lz, nowMs)
        }
    }

    private fun handleLinearSample(lx: Float, ly: Float, lz: Float, nowMs: Long) {
        linearMag = vectorMagnitude(lx, ly, lz)
        processStillnessPipeline(nowMs)
        processTremorPipeline(lx, ly, lz, nowMs)
    }

    private fun processFallPipeline(rawMagnitude: Float, nowMs: Long) {
        val freeFallStart = freeFallStartMs
        val freeFallConfirmed = freeFallConfirmedMs
        val impactDetected = impactDetectedMs

        if (impactDetected != null) return

        if (freeFallConfirmed == null) {
            if (rawMagnitude <= FREE_FALL_THRESHOLD) {
                if (freeFallStart == null) {
                    freeFallStartMs = nowMs
                } else if (nowMs - freeFallStart >= FREE_FALL_MIN_MS) {
                    freeFallConfirmedMs = nowMs
                }
            } else {
                freeFallStartMs = null
            }
            return
        }

        val freeFallAgeMs = nowMs - freeFallConfirmed
        if (rawMagnitude >= IMPACT_THRESHOLD && freeFallAgeMs <= IMPACT_WINDOW_MS) {
            impactDetectedMs = nowMs
            stillnessStartMs = nowMs
            stillnessSamples = 0
            stillnessEnergy = 0.0
            stillnessPeak = 0f
            return
        }

        if (freeFallAgeMs > IMPACT_WINDOW_MS) {
            resetFallPipeline()
        }
    }

    private fun processStillnessPipeline(nowMs: Long) {
        val stillnessStart = stillnessStartMs ?: return
        stillnessSamples += 1
        stillnessEnergy += (linearMag * linearMag).toDouble()
        stillnessPeak = maxOf(stillnessPeak, linearMag)

        val elapsed = nowMs - stillnessStart
        if (elapsed < STILLNESS_WINDOW_MS) return

        val rms = if (stillnessSamples == 0) Float.MAX_VALUE else {
            sqrt((stillnessEnergy / stillnessSamples).toFloat())
        }
        val isStill = stillnessPeak <= STILLNESS_MAX_LINEAR && rms <= STILLNESS_RMS_LINEAR
        if (isStill) emitEmergencyTrigger(TRIGGER_FALL)
        resetFallPipeline()
    }

    private fun processTremorPipeline(lx: Float, ly: Float, lz: Float, nowMs: Long) {
        tremorLpfX += HPF_ALPHA_TREMOR * (lx - tremorLpfX)
        tremorLpfY += HPF_ALPHA_TREMOR * (ly - tremorLpfY)
        tremorLpfZ += HPF_ALPHA_TREMOR * (lz - tremorLpfZ)
        val hpX = lx - tremorLpfX
        val hpY = ly - tremorLpfY
        val hpZ = lz - tremorLpfZ
        val hpMag = vectorMagnitude(hpX, hpY, hpZ)

        tremorTimes.addLast(nowMs)
        tremorHpX.addLast(hpX)
        tremorHpMag.addLast(hpMag)
        pruneTremorWindow(nowMs)

        if (nowMs - lastTremorEvalMs < 500L) return
        lastTremorEvalMs = nowMs

        if (tremorHpMag.size < TREMOR_MIN_ACTIVE_SAMPLES) {
            tremorWindowHits = maxOf(0, tremorWindowHits - 1)
            return
        }

        var sumSq = 0.0
        for (v in tremorHpMag) sumSq += (v * v).toDouble()
        val rms = sqrt((sumSq / tremorHpMag.size).toFloat())
        val zeroCrossings = countZeroCrossings(tremorHpX)

        val rhythmic = zeroCrossings in TREMOR_MIN_ZERO_CROSS..TREMOR_MAX_ZERO_CROSS
        val severeAmplitude = rms >= TREMOR_MIN_RMS
        if (rhythmic && severeAmplitude) {
            tremorWindowHits += 1
        } else {
            tremorWindowHits = maxOf(0, tremorWindowHits - 1)
        }

        if (tremorWindowHits >= TREMOR_SUSTAINED_WINDOWS) {
            emitEmergencyTrigger(TRIGGER_TREMOR)
            tremorWindowHits = 0
            tremorTimes.clear()
            tremorHpX.clear()
            tremorHpMag.clear()
        }
    }

    private fun pruneTremorWindow(nowMs: Long) {
        while (tremorTimes.isNotEmpty() && nowMs - tremorTimes.first() > TREMOR_WINDOW_MS) {
            tremorTimes.removeFirst()
            tremorHpX.removeFirst()
            tremorHpMag.removeFirst()
        }
    }

    private fun countZeroCrossings(samples: ArrayDeque<Float>): Int {
        var count = 0
        var prevSign = 0
        for (v in samples) {
            if (abs(v) < 0.25f) continue
            val sign = if (v > 0f) 1 else -1
            if (prevSign != 0 && sign != prevSign) count += 1
            prevSign = sign
        }
        return count
    }

    private fun emitEmergencyTrigger(triggerType: String) {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastAlertMs < ALERT_COOLDOWN_MS) return
        lastAlertMs = nowMs

        val intent = Intent(ACTION_FALL_DETECTED).apply {
            putExtra("event_source", "SENSOR_FUSION")
            putExtra(EXTRA_TRIGGER_TYPE, triggerType)
        }
        sendBroadcast(intent)
    }

    private fun resetFallPipeline() {
        freeFallStartMs = null
        freeFallConfirmedMs = null
        impactDetectedMs = null
        stillnessStartMs = null
        stillnessSamples = 0
        stillnessEnergy = 0.0
        stillnessPeak = 0f
    }

    private fun vectorMagnitude(x: Float, y: Float, z: Float): Float {
        return sqrt(x * x + y * y + z * z)
    }
}
