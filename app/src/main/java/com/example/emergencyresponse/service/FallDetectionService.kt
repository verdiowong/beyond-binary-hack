package com.example.emergencyresponse.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.emergencyresponse.ui.MainActivity
import kotlin.math.abs
import kotlin.math.sqrt

class FallDetectionService : Service(), SensorEventListener {
    companion object {
        private const val TAG = "FallDetectionService"
        const val ACTION_FALL_DETECTED = "com.example.emergencyresponse.ACTION_FALL_DETECTED"
        const val EXTRA_TRIGGER_TYPE = "trigger_type"
        const val EXTRA_START_DROP_COUNTDOWN = "extra_start_drop_countdown"
        const val EXTRA_START_SOURCE = "extra_start_source"
        const val TRIGGER_FALL = "fall"
        const val TRIGGER_TREMOR = "severe_tremor"

        private const val MONITOR_CHANNEL_ID = "fall_detection_service"
        private const val ALERT_CHANNEL_ID = "fall_detection_alerts"
        private const val NOTIFICATION_ID = 1011
        private const val ALERT_NOTIFICATION_ID = 1012

        // Fall pipeline: free-fall -> impact >25 -> stillness.
        private const val FREE_FALL_THRESHOLD = 2.3f
        private const val FREE_FALL_MIN_MS = 180L
        private const val IMPACT_THRESHOLD = 25.0f
        private const val IMPACT_WINDOW_MS = 1500L
        private const val STILLNESS_WINDOW_MS = 3000L
        private const val STILLNESS_MAX_LINEAR = 2.5f
        private const val STILLNESS_RMS_LINEAR = 1.4f

        // Tremor pipeline.
        private const val TREMOR_WINDOW_MS = 3000L
        private const val TREMOR_MIN_RMS = 2.0f
        private const val TREMOR_MIN_ZERO_CROSS = 18
        private const val TREMOR_MAX_ZERO_CROSS = 85
        private const val TREMOR_MIN_ACTIVE_SAMPLES = 50
        private const val TREMOR_SUSTAINED_WINDOWS = 5

        // Noise suppression filters.
        private const val LPF_ALPHA_MAG = 0.18f
        private const val LPF_ALPHA_GRAVITY = 0.10f
        private const val LPF_ALPHA_BUMP = 0.22f

        private const val ALERT_COOLDOWN_MS = 15_000L
    }

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var isSensorRegistered = false

    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 9.81f
    private var magLpf = 9.81f
    private var bumpLpf = 0f

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
        startAsForeground()
        Log.i(TAG, "Foreground sensor service created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called. startId=$startId flags=$flags")
        registerSensorIfNeeded()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterSensor()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val nowMs = SystemClock.elapsedRealtime()
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]

        // Low-pass gravity estimate to isolate linear acceleration.
        gravityX += LPF_ALPHA_GRAVITY * (x - gravityX)
        gravityY += LPF_ALPHA_GRAVITY * (y - gravityY)
        gravityZ += LPF_ALPHA_GRAVITY * (z - gravityZ)
        val lx = x - gravityX
        val ly = y - gravityY
        val lz = z - gravityZ

        val magnitude = vectorMagnitude(x, y, z)
        magLpf += LPF_ALPHA_MAG * (magnitude - magLpf)

        // Extra low-pass on linear magnitude to ignore tiny bumps.
        val linearMag = vectorMagnitude(lx, ly, lz)
        bumpLpf += LPF_ALPHA_BUMP * (linearMag - bumpLpf)

        processFallPipeline(magLpf, linearMag, nowMs)
        processTremorPipeline(lx, ly, lz, nowMs)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun processFallPipeline(magnitude: Float, linearMag: Float, nowMs: Long) {
        if (impactDetectedMs != null) {
            processStillness(linearMag, nowMs)
            return
        }

        val freeFallStart = freeFallStartMs
        val freeFallConfirmed = freeFallConfirmedMs
        if (freeFallConfirmed == null) {
            if (magnitude <= FREE_FALL_THRESHOLD) {
                if (freeFallStart == null) {
                    freeFallStartMs = nowMs
                    Log.d(TAG, "Free-fall candidate started. |V|=${"%.2f".format(magnitude)}")
                } else if (nowMs - freeFallStart >= FREE_FALL_MIN_MS) {
                    freeFallConfirmedMs = nowMs
                    Log.d(TAG, "Free-fall confirmed.")
                }
            } else {
                freeFallStartMs = null
            }
            return
        }

        val age = nowMs - freeFallConfirmed
        if (magnitude >= IMPACT_THRESHOLD && age <= IMPACT_WINDOW_MS) {
            impactDetectedMs = nowMs
            stillnessStartMs = nowMs
            stillnessSamples = 0
            stillnessEnergy = 0.0
            stillnessPeak = 0f
            Log.d(TAG, "Impact detected. |V|=${"%.2f".format(magnitude)}")
            return
        }

        if (age > IMPACT_WINDOW_MS) {
            resetFallPipeline()
        }
    }

    private fun processStillness(linearMag: Float, nowMs: Long) {
        val start = stillnessStartMs ?: return
        stillnessSamples += 1
        stillnessEnergy += (linearMag * linearMag).toDouble()
        stillnessPeak = maxOf(stillnessPeak, linearMag)

        if (nowMs - start < STILLNESS_WINDOW_MS) return

        val rms = if (stillnessSamples == 0) Float.MAX_VALUE else {
            sqrt((stillnessEnergy / stillnessSamples).toFloat())
        }
        val isStill = stillnessPeak <= STILLNESS_MAX_LINEAR && rms <= STILLNESS_RMS_LINEAR
        Log.d(
            TAG,
            "Stillness check isStill=$isStill peak=${"%.2f".format(stillnessPeak)} rms=${"%.2f".format(rms)}"
        )
        if (isStill) {
            emitEmergencyTrigger(TRIGGER_FALL)
        }
        resetFallPipeline()
    }

    private fun processTremorPipeline(lx: Float, ly: Float, lz: Float, nowMs: Long) {
        // High-pass filter (rapid components only).
        tremorLpfX += LPF_ALPHA_MAG * (lx - tremorLpfX)
        tremorLpfY += LPF_ALPHA_MAG * (ly - tremorLpfY)
        tremorLpfZ += LPF_ALPHA_MAG * (lz - tremorLpfZ)
        val hpX = lx - tremorLpfX
        val hpY = ly - tremorLpfY
        val hpZ = lz - tremorLpfZ
        val hpMag = vectorMagnitude(hpX, hpY, hpZ)

        tremorTimes.addLast(nowMs)
        tremorHpX.addLast(hpX)
        tremorHpMag.addLast(hpMag)
        pruneWindow(nowMs)

        if (nowMs - lastTremorEvalMs < 500L) return
        lastTremorEvalMs = nowMs

        if (tremorHpMag.size < TREMOR_MIN_ACTIVE_SAMPLES) {
            tremorWindowHits = maxOf(0, tremorWindowHits - 1)
            return
        }

        var sumSq = 0.0
        for (v in tremorHpMag) sumSq += (v * v).toDouble()
        val rms = sqrt((sumSq / tremorHpMag.size).toFloat())
        val zc = zeroCrossings(tremorHpX)
        val rhythmic = zc in TREMOR_MIN_ZERO_CROSS..TREMOR_MAX_ZERO_CROSS
        val severe = rms >= TREMOR_MIN_RMS

        if (rhythmic && severe) tremorWindowHits += 1 else tremorWindowHits = maxOf(0, tremorWindowHits - 1)
        Log.d(TAG, "Tremor eval rms=${"%.2f".format(rms)} zc=$zc hits=$tremorWindowHits")

        if (tremorWindowHits >= TREMOR_SUSTAINED_WINDOWS) {
            emitEmergencyTrigger(TRIGGER_TREMOR)
            tremorWindowHits = 0
            tremorTimes.clear()
            tremorHpX.clear()
            tremorHpMag.clear()
        }
    }

    private fun emitEmergencyTrigger(triggerType: String) {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastAlertMs < ALERT_COOLDOWN_MS) return
        lastAlertMs = nowMs

        Log.i(TAG, "Emergency trigger emitted. type=$triggerType")

        // Acquire a partial wake lock so the CPU stays awake long enough
        // for the broadcast + full-screen notification to fire, even in Doze.
        acquireEmergencyWakeLock()

        // Keep current in-app flow wiring.
        val broadcast = Intent(ACTION_FALL_DETECTED).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(EXTRA_TRIGGER_TYPE, triggerType)
        }
        sendBroadcast(broadcast)

        // Attempt to launch MainActivity directly so the countdown screen
        // pops up immediately, instead of relying solely on the notification.
        // Wrapped in try-catch because some OEMs / Android 12+ may block
        // background activity starts; the notification below is the fallback.
        try {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_START_DROP_COUNTDOWN, true)
                putExtra(EXTRA_START_SOURCE, triggerType)
            }
            startActivity(launchIntent)
            Log.i(TAG, "Direct activity launch succeeded for trigger=$triggerType")
        } catch (e: Exception) {
            Log.w(TAG, "Direct activity launch blocked (falling back to notification): ${e.message}")
        }

        showEmergencyAlertNotification(triggerType)
    }

    /**
     * Acquire a short-lived PARTIAL_WAKE_LOCK to guarantee the device
     * stays awake while the emergency alert notification + broadcast are delivered.
     * Auto-releases after 30 seconds.
     */
    private fun acquireEmergencyWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "EmergencyResponse::FallTriggerWakeLock"
            )
            wl.acquire(30_000L) // 30 seconds max
            Log.d(TAG, "Emergency wake lock acquired (30s timeout)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock: ${e.message}")
        }
    }

    private fun registerSensorIfNeeded() {
        if (isSensorRegistered) return
        val manager = sensorManager ?: return
        val accel = accelerometer
        if (accel == null) {
            Log.e(TAG, "Accelerometer missing; cannot run sensor backend.")
            stopSelf()
            return
        }
        manager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        isSensorRegistered = true
        Log.i(TAG, "Accelerometer registered at SENSOR_DELAY_GAME")
    }

    private fun unregisterSensor() {
        if (!isSensorRegistered) return
        sensorManager?.unregisterListener(this)
        isSensorRegistered = false
    }

    private fun startAsForeground() {
        createNotificationChannels()
        val notification = buildForegroundNotification()
        try {
            startForeground(NOTIFICATION_ID, notification)
            Log.i(TAG, "Foreground notification started (id=$NOTIFICATION_ID)")
        } catch (e: Exception) {
            // On API 34+ this can fail if ACTIVITY_RECOGNITION is not yet granted
            // (required for foregroundServiceType="health"). The service will still
            // run but without the foreground priority until permissions are granted
            // and it is restarted by MainActivity.
            Log.e(TAG, "startForeground failed (missing permission?): ${e.message}")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val monitorChannel = NotificationChannel(
            MONITOR_CHANNEL_ID,
            "Emergency Fall Monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps fall and tremor monitoring active"
        }
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Emergency Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Immediate emergency alerts and countdown wake-up"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannels(listOf(monitorChannel, alertChannel))
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
            .setContentTitle("Emergency monitor active")
            .setContentText("Fall and tremor detection is running in the background.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun showEmergencyAlertNotification(triggerType: String) {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_START_DROP_COUNTDOWN, true)
            putExtra(EXTRA_START_SOURCE, triggerType)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            2001,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Emergency detected")
            .setContentText("Tap to open countdown now ($triggerType).")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .setFullScreenIntent(contentIntent, true)
            .build()

        Log.i(TAG, "Posting emergency alert notification for trigger=$triggerType")
        getSystemService(NotificationManager::class.java).notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun pruneWindow(nowMs: Long) {
        while (tremorTimes.isNotEmpty() && nowMs - tremorTimes.first() > TREMOR_WINDOW_MS) {
            tremorTimes.removeFirst()
            tremorHpX.removeFirst()
            tremorHpMag.removeFirst()
        }
    }

    private fun zeroCrossings(samples: ArrayDeque<Float>): Int {
        var count = 0
        var prev = 0
        for (value in samples) {
            if (abs(value) < 0.2f) continue
            val sign = if (value > 0f) 1 else -1
            if (prev != 0 && sign != prev) count += 1
            prev = sign
        }
        return count
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

    private fun vectorMagnitude(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)
}
