package com.example.emergencyresponse

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.SensorManager
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Instrumented tests for FallDetectionService.
 * 
 * These tests run on an Android device or emulator and test the service
 * with real Android framework components and sensors.
 * 
 * Run with: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class FallDetectionServiceInstrumentedTest {

    private lateinit var context: Context
    private var service: FallDetectionService? = null
    private val bindLatch = CountDownLatch(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? FallDetectionService.LocalBinder)?.getService()
            bindLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        service?.let {
            context.unbindService(serviceConnection)
        }
        service?.stopSelf()
    }

    @Test
    fun testServiceCanBind() {
        val intent = Intent(context, FallDetectionService::class.java)
        val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        
        assertTrue(bound, "Service should bind successfully")
        
        // Wait for binding
        assertTrue(
            bindLatch.await(5, TimeUnit.SECONDS),
            "Service should bind within 5 seconds"
        )
        
        assertNotNull(service, "Service instance should not be null after binding")
    }

    @Test
    fun testServiceStartsSuccessfully() {
        val intent = Intent(context, FallDetectionService::class.java)
        val componentName = context.startService(intent)
        
        assertNotNull(componentName, "Service should start successfully")
        
        // Clean up
        context.stopService(intent)
    }

    @Test
    fun testDeviceHasAccelerometer() {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        
        assertNotNull(accelerometer, "Device should have an accelerometer sensor")
    }

    @Test
    fun testServiceHasRequiredSensors() {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        
        // Check for required sensors
        val accelerometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        assertNotNull(accelerometer, "Accelerometer is required for fall detection")
        
        // Optional sensors (won't fail test if not present)
        val gravitySensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)
        val linearAccelSensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_LINEAR_ACCELERATION)
        
        // Log availability (for debugging)
        InstrumentationRegistry.getInstrumentation().targetContext.apply {
            println("Gravity sensor available: ${gravitySensor != null}")
            println("Linear acceleration sensor available: ${linearAccelSensor != null}")
        }
    }

    @Test
    fun testServiceSurvivesRestart() {
        // Start service
        val intent = Intent(context, FallDetectionService::class.java)
        context.startService(intent)
        
        // Bind to service
        val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        assertTrue(bound, "Service should bind")
        
        assertTrue(
            bindLatch.await(5, TimeUnit.SECONDS),
            "Service should bind within timeout"
        )
        
        val firstService = service
        assertNotNull(firstService, "Service should be available after first bind")
        
        // Unbind
        context.unbindService(serviceConnection)
        
        // Service should remain running due to START_STICKY
        // Wait a moment
        Thread.sleep(1000)
        
        // Rebind
        val secondBindLatch = CountDownLatch(1)
        val secondConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? FallDetectionService.LocalBinder)?.getService()
                secondBindLatch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        
        context.bindService(intent, secondConnection, Context.BIND_AUTO_CREATE)
        assertTrue(
            secondBindLatch.await(5, TimeUnit.SECONDS),
            "Service should rebind successfully"
        )
        
        assertNotNull(service, "Service should still be available after rebind")
        
        // Clean up
        context.unbindService(secondConnection)
        context.stopService(intent)
    }

    @Test
    fun testBroadcastIntentFormat() {
        // Test that the expected broadcast action constant is correct
        val expectedAction = "com.example.emergencyresponse.ACTION_FALL_DETECTED"
        assertTrue(
            FallDetectionService.ACTION_FALL_DETECTED == expectedAction,
            "Broadcast action should match expected format"
        )
        
        assertTrue(
            FallDetectionService.EXTRA_TRIGGER_TYPE.isNotEmpty(),
            "Extra trigger type key should be defined"
        )
        
        assertTrue(
            FallDetectionService.TRIGGER_FALL.isNotEmpty(),
            "Fall trigger value should be defined"
        )
        
        assertTrue(
            FallDetectionService.TRIGGER_TREMOR.isNotEmpty(),
            "Tremor trigger value should be defined"
        )
    }

    @Test
    fun testServiceRunsInForegroundCompatibility() {
        // This test verifies that the service can potentially run in foreground
        // (though current implementation doesn't use foreground service)
        val intent = Intent(context, FallDetectionService::class.java)
        val componentName = context.startService(intent)
        
        assertNotNull(componentName, "Service should start")
        
        // If you implement foreground service in the future, you can add:
        // val notification = createNotification()
        // service?.startForeground(NOTIFICATION_ID, notification)
        
        context.stopService(intent)
    }
}
