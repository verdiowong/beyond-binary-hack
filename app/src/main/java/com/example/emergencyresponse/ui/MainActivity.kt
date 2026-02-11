package com.example.emergencyresponse.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.ViewSwitcher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.emergencyresponse.R
import com.example.emergencyresponse.model.AppConfig
import com.example.emergencyresponse.model.ContextOption
import com.example.emergencyresponse.model.EmergencyUiModel
import com.example.emergencyresponse.model.EmergencyUiState
import com.example.emergencyresponse.model.ServiceConfig
import com.example.emergencyresponse.model.UserProfile
import com.example.emergencyresponse.model.UserProfileRepository
import com.example.emergencyresponse.service.FallDetectionService
import com.example.emergencyresponse.util.DispatchBridge
import com.example.emergencyresponse.util.InteractionManager
import com.example.emergencyresponse.util.LocationHandler
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private val emergencyViewModel: EmergencyViewModel by viewModels()

    private lateinit var rootLayout: View
    private lateinit var stateSwitcher: ViewSwitcher
    private lateinit var stateCard: MaterialCardView
    private lateinit var idleTriggerButton: Button
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var primaryActionButton: Button
    private lateinit var secondaryActionButton: Button
    private lateinit var tertiaryActionButton: Button
    private lateinit var quaternaryActionButton: Button
    private lateinit var cancelButton: Button

    /** Context options for the currently selected service (set during SERVICE_SELECTION). */
    private var activeContextOptions: List<ContextOption> = emptyList()

    private lateinit var interactionManager: InteractionManager
    private lateinit var locationHandler: LocationHandler
    private lateinit var dispatchBridge: DispatchBridge
    private lateinit var profileRepo: UserProfileRepository

    private var countdownTimer: CountDownTimer? = null
    private var isFallReceiverRegistered = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val denied = results.filterValues { granted -> !granted }.keys
            if (denied.isNotEmpty()) {
                subtitleText.text = "Permissions denied: ${denied.joinToString(", ")}"
            } else {
                subtitleText.text = "All required permissions granted."
            }
            // After permissions are resolved, (re)start the foreground service
            // since health-type service requires ACTIVITY_RECOGNITION on API 34+.
            startFallDetectionServiceIfAllowed()
        }

    private val fallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FallDetectionService.ACTION_FALL_DETECTED) {
                val triggerType = intent.getStringExtra(FallDetectionService.EXTRA_TRIGGER_TYPE) ?: "unknown"
                Log.i(TAG, "Fall/tremor broadcast received. triggerType=$triggerType")
                emergencyViewModel.onEmergencyTrigger("FALL_DETECTION_SERVICE")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Allow this activity to appear over the lock screen and turn on the display.
        // The manifest attributes handle API 27+; this covers runtime enforcement.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }

        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        stateSwitcher = findViewById(R.id.stateSwitcher)
        stateCard = findViewById(R.id.stateCard)
        idleTriggerButton = findViewById(R.id.idleTriggerButton)
        titleText = findViewById(R.id.titleText)
        subtitleText = findViewById(R.id.subtitleText)
        primaryActionButton = findViewById(R.id.primaryActionButton)
        secondaryActionButton = findViewById(R.id.secondaryActionButton)
        tertiaryActionButton = findViewById(R.id.tertiaryActionButton)
        quaternaryActionButton = findViewById(R.id.quaternaryActionButton)
        cancelButton = findViewById(R.id.cancelButton)

        interactionManager = InteractionManager(this).also { it.initialize() }
        locationHandler = LocationHandler(this)
        dispatchBridge = DispatchBridge(this)
        profileRepo = UserProfileRepository(this)

        // Launch onboarding on first run
        if (!profileRepo.isOnboardingComplete) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        setupUiActions()
        observeState()
        requestCriticalPermissionsIfNeeded()
        requestBatteryOptimizationExemptionIfNeeded()
        handleServiceLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleServiceLaunchIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        startFallDetectionServiceIfAllowed()
        if (!isFallReceiverRegistered) {
            val filter = IntentFilter(FallDetectionService.ACTION_FALL_DETECTED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(fallReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(fallReceiver, filter)
            }
            isFallReceiverRegistered = true
        }
    }

    /**
     * Start the fall detection foreground service only if the prerequisite
     * permissions for the `health` foreground-service type are granted.
     * On API < 34 this always succeeds; on 34+ it requires ACTIVITY_RECOGNITION.
     */
    private fun startFallDetectionServiceIfAllowed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
        ) {
            Log.w(TAG, "ACTIVITY_RECOGNITION not yet granted; deferring service start.")
            return
        }
        Log.d(TAG, "Starting foreground FallDetectionService")
        ContextCompat.startForegroundService(this, Intent(this, FallDetectionService::class.java))
    }

    private fun setupUiActions() {
        idleTriggerButton.setOnClickListener {
            Log.i(TAG, "Idle trigger button clicked")
            emergencyViewModel.onEmergencyTrigger("IDLE_SCREEN_BUTTON")
        }

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        primaryActionButton.setOnClickListener {
            when (emergencyViewModel.uiModel.value.state) {
                EmergencyUiState.IDLE -> emergencyViewModel.onEmergencyTrigger("UI_BUTTON")
                EmergencyUiState.DROP_COUNTDOWN -> {
                    Log.i(TAG, "\"Help\" clicked during countdown -> SERVICE_SELECTION")
                    emergencyViewModel.onCountdownFinished()
                }
                EmergencyUiState.SERVICE_SELECTION -> selectService(0)
                EmergencyUiState.CONTEXT_SELECTION -> selectContext(0)
                EmergencyUiState.LOCATION_CONFIRM -> emergencyViewModel.onLocationConfirmed()
                EmergencyUiState.DISPATCH_ACTIVE -> emergencyViewModel.onCancel()
            }
        }

        secondaryActionButton.setOnClickListener {
            when (emergencyViewModel.uiModel.value.state) {
                EmergencyUiState.SERVICE_SELECTION -> selectService(1)
                EmergencyUiState.CONTEXT_SELECTION -> selectContext(1)
                else -> Unit
            }
        }

        tertiaryActionButton.setOnClickListener {
            when (emergencyViewModel.uiModel.value.state) {
                EmergencyUiState.SERVICE_SELECTION -> selectService(2)
                EmergencyUiState.CONTEXT_SELECTION -> selectContext(2)
                else -> Unit
            }
        }

        quaternaryActionButton.setOnClickListener {
            when (emergencyViewModel.uiModel.value.state) {
                EmergencyUiState.CONTEXT_SELECTION -> selectContext(3)
                else -> Unit
            }
        }

        cancelButton.setOnClickListener { emergencyViewModel.onCancel() }
    }

    private fun selectService(index: Int) {
        val services = ServiceConfig.ALL_SERVICES
        if (index in services.indices) {
            val svc = services[index]
            emergencyViewModel.onServiceSelected(svc.scdfPrefix)
        }
    }

    private fun selectContext(index: Int) {
        if (index in activeContextOptions.indices) {
            val ctx = activeContextOptions[index]
            val userName = profileRepo.load().name
            emergencyViewModel.onContextSelected(ctx.smsDescription(userName))
        }
    }

    /**
     * Handle a voice keyword action from STT.
     * Maps action strings back to ViewModel transitions.
     */
    private fun handleVoiceAction(currentState: EmergencyUiState, action: String) {
        Log.i(TAG, "Voice action: '$action' in state $currentState")
        when (currentState) {
            EmergencyUiState.DROP_COUNTDOWN -> when (action) {
                "CANCEL" -> emergencyViewModel.onCancel()
                "ESCALATE" -> emergencyViewModel.onCountdownFinished()
            }
            EmergencyUiState.SERVICE_SELECTION -> {
                // action is the scdfPrefix (e.g. "Fire Engine", "Ambulance", "Police")
                emergencyViewModel.onServiceSelected(action)
            }
            EmergencyUiState.CONTEXT_SELECTION -> {
                // action is the smsDescription (already resolved with user name from STT keywords)
                emergencyViewModel.onContextSelected(action)
            }
            EmergencyUiState.LOCATION_CONFIRM -> when (action) {
                "CONFIRM" -> emergencyViewModel.onLocationConfirmed()
                "CANCEL" -> emergencyViewModel.onCancel()
            }
            else -> Unit
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                emergencyViewModel.uiModel.collect { renderState(it) }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                emergencyViewModel.stateEvents.collect { handleStateEvent(it) }
            }
        }
    }

    private fun handleStateEvent(state: EmergencyUiState) {
        when (state) {
            EmergencyUiState.LOCATION_CONFIRM -> {
                if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    subtitleText.text = "Location permission required to confirm address."
                    requestCriticalPermissionsIfNeeded()
                    return
                }
                // If device GPS/location is off, prompt user to enable it
                if (!locationHandler.isLocationEnabled()) {
                    subtitleText.text = "Location services are disabled. Please enable GPS."
                    try {
                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    } catch (e: Exception) {
                        Log.e(TAG, "Cannot open location settings: ${e.message}")
                    }
                    return
                }
                locationHandler.requestHighAccuracyLocation(
                    onLocationReady = { loc ->
                        emergencyViewModel.onLocationResolved(loc.address, loc.latitude, loc.longitude)
                    },
                    onError = { message -> subtitleText.text = message }
                )
            }

            EmergencyUiState.DISPATCH_ACTIVE -> {
                if (!hasPermission(Manifest.permission.SEND_SMS)) {
                    requestCriticalPermissionsIfNeeded()
                }

                // Enrich event with full user profile data
                val profile = profileRepo.load()
                emergencyViewModel.enrichFromProfile(profile)

                val baseEvent = emergencyViewModel.emergencyEvent.value

                // Append medical info to context if available
                val enrichedContext = buildList {
                    add(baseEvent.context)
                    if (profile.medicalConditions.isNotBlank()) add("Medical: ${profile.medicalConditions}")
                    if (profile.medicalId.isNotBlank()) add("ID: ${profile.medicalId}")
                }.filter { it.isNotBlank() }.joinToString(". ")

                val dispatchEvent = baseEvent.copy(context = enrichedContext)

                dispatchBridge.dispatchSms(
                    event = dispatchEvent,
                    onComplete = { success, result ->
                        subtitleText.text = if (success) result else "Dispatch error: $result"
                    }
                )
            }

            else -> Unit
        }
    }

    private fun renderState(model: EmergencyUiModel) {
        Log.d(TAG, "Rendering state=${model.state}")
        val flowVisible = model.state != EmergencyUiState.IDLE
        stateSwitcher.displayedChild = if (flowVisible) 1 else 0

        // Apply the visual theme for this state (background, button colors, status bar).
        applyStateTheme(model.state)

        when (model.state) {
            EmergencyUiState.IDLE -> {
                cancelTimer()
                hideAllOptionButtons()
                titleText.text = "Ready"
                subtitleText.text = "Press and hold a large button or volume key to trigger emergency flow."
                primaryActionButton.text = "Start Emergency"
                primaryActionButton.contentDescription = "Start emergency flow. Double tap to activate."
                primaryActionButton.visibility = View.VISIBLE
                cancelButton.visibility = View.GONE
            }

            EmergencyUiState.DROP_COUNTDOWN -> {
                hideAllOptionButtons()
                titleText.text = "\u26A0\uFE0F  Fall Detected"
                subtitleText.text = "Dispatch starts in ${AppConfig.COUNTDOWN_DURATION_MS / 1000} seconds unless cancelled."
                primaryActionButton.text = "Help"
                primaryActionButton.contentDescription = "I need help. Double tap to request emergency services now."
                primaryActionButton.visibility = View.VISIBLE
                cancelButton.contentDescription = "I am okay. Double tap to cancel the emergency."
                cancelButton.visibility = View.VISIBLE
                startCountdownIfNeeded()
            }

            EmergencyUiState.SERVICE_SELECTION -> {
                cancelTimer()
                titleText.text = "Choose Service"
                subtitleText.text = "Which emergency service do you need?"
                hideAllOptionButtons()
                val services = ServiceConfig.ALL_SERVICES
                val buttons = listOf(primaryActionButton, secondaryActionButton, tertiaryActionButton)
                val colors = listOf(
                    getColor(R.color.fire_orange),
                    getColor(R.color.emergency_red),
                    getColor(R.color.police_blue)
                )
                services.forEachIndexed { i, svc ->
                    if (i < buttons.size) {
                        buttons[i].text = "${svc.emoji}  ${svc.label}"
                        buttons[i].contentDescription = "Select ${svc.label} service"
                        buttons[i].visibility = View.VISIBLE
                        buttons[i].backgroundTintList = ColorStateList.valueOf(colors[i])
                    }
                }
                cancelButton.contentDescription = "Cancel emergency and return to idle"
                cancelButton.visibility = View.VISIBLE
            }

            EmergencyUiState.CONTEXT_SELECTION -> {
                val service = ServiceConfig.findService(model.selectedService ?: "")
                titleText.text = "What is happening?"
                subtitleText.text = "Service: ${service?.let { "${it.emoji} ${it.label}" } ?: model.selectedService}"
                hideAllOptionButtons()
                activeContextOptions = service?.contexts ?: emptyList()
                val buttons = listOf(primaryActionButton, secondaryActionButton, tertiaryActionButton, quaternaryActionButton)
                activeContextOptions.forEachIndexed { i, ctx ->
                    if (i < buttons.size) {
                        buttons[i].text = "${ctx.emoji}  ${ctx.label}"
                        buttons[i].contentDescription = "Select ${ctx.label}"
                        buttons[i].visibility = View.VISIBLE
                    }
                }
                cancelButton.contentDescription = "Cancel emergency and return to idle"
                cancelButton.visibility = View.VISIBLE
            }

            EmergencyUiState.LOCATION_CONFIRM -> {
                hideAllOptionButtons()
                titleText.text = "\uD83D\uDCCD  Confirm Location"
                val locationReady = model.resolvedAddress != null
                subtitleText.text = model.resolvedAddress ?: "Resolving your location\u2026"
                primaryActionButton.text = if (locationReady) "Confirm & Dispatch" else "Waiting for location\u2026"
                primaryActionButton.contentDescription = if (locationReady)
                    "Confirm location and dispatch emergency. Address: ${model.resolvedAddress}"
                else
                    "Waiting for location to resolve. Button disabled."
                primaryActionButton.visibility = View.VISIBLE
                primaryActionButton.isEnabled = locationReady
                primaryActionButton.alpha = if (locationReady) 1.0f else 0.5f
                cancelButton.contentDescription = "Cancel emergency and return to idle"
                cancelButton.visibility = View.VISIBLE
            }

            EmergencyUiState.DISPATCH_ACTIVE -> {
                cancelTimer()
                hideAllOptionButtons()
                titleText.text = "\u2705  Dispatch Active"
                subtitleText.text = "Emergency notification has been sent."
                primaryActionButton.text = "Done \u2014 Return to Idle"
                primaryActionButton.contentDescription = "Emergency dispatched. Double tap to return to idle."
                primaryActionButton.visibility = View.VISIBLE
                cancelButton.visibility = View.GONE
            }
        }

        interactionManager.onStateChanged(model.state)

        // Enhanced TTS: announce available options (plain text, no emojis)
        val optionLabels: List<String> = when (model.state) {
            EmergencyUiState.SERVICE_SELECTION ->
                ServiceConfig.ALL_SERVICES.map { it.label }
            EmergencyUiState.CONTEXT_SELECTION ->
                activeContextOptions.map { it.label }
            else -> emptyList()
        }
        if (optionLabels.isNotEmpty()) {
            interactionManager.announceOptions(optionLabels)
        }

        // STT: start/stop voice keyword listening based on state and comm preference.
        // IMPORTANT: uses startListeningAfterTts to avoid the microphone picking up
        // the TTS speaker output and false-matching keywords (e.g. "okay" from TTS echo).
        val profile = profileRepo.load()
        val useVoice = profile.communicationMode != UserProfile.COMM_MODE_TOUCH
        if (useVoice) {
            val keywords = interactionManager.keywordsForState(model.state, activeContextOptions, profile.name)
            if (keywords.isNotEmpty()) {
                interactionManager.startListeningAfterTts(keywords) { action ->
                    runOnUiThread { handleVoiceAction(model.state, action) }
                }
            } else {
                interactionManager.stopListening()
            }
        } else {
            interactionManager.stopListening()
        }
    }

    /**
     * Apply per-state visual theming: background color, status bar, button tints, title color.
     * Each state has a distinct color scheme so the user immediately knows where they are.
     */
    @Suppress("DEPRECATION") // window.statusBarColor — fine on targetSdk 34
    private fun applyStateTheme(state: EmergencyUiState) {
        data class StateTheme(
            val background: Int,
            val statusBar: Int,
            val titleColor: Int,
            val primaryBtn: Int,
            val secondaryBtn: Int
        )

        val theme = when (state) {
            EmergencyUiState.IDLE -> StateTheme(
                background = getColor(R.color.bg_idle),
                statusBar = getColor(R.color.primary_dark),
                titleColor = getColor(R.color.text_primary),
                primaryBtn = getColor(R.color.emergency_red),
                secondaryBtn = getColor(R.color.police_blue)
            )
            EmergencyUiState.DROP_COUNTDOWN -> StateTheme(
                background = getColor(R.color.bg_countdown),
                statusBar = getColor(R.color.emergency_red_dark),
                titleColor = getColor(R.color.emergency_red),
                primaryBtn = getColor(R.color.emergency_red),
                secondaryBtn = getColor(R.color.police_blue)
            )
            EmergencyUiState.SERVICE_SELECTION -> StateTheme(
                background = getColor(R.color.bg_selection),
                statusBar = getColor(R.color.neutral_dark),
                titleColor = getColor(R.color.text_primary),
                primaryBtn = getColor(R.color.emergency_red),
                secondaryBtn = getColor(R.color.police_blue)
            )
            EmergencyUiState.CONTEXT_SELECTION -> StateTheme(
                background = getColor(R.color.bg_selection),
                statusBar = getColor(R.color.neutral_dark),
                titleColor = getColor(R.color.text_primary),
                primaryBtn = getColor(R.color.emergency_red),
                secondaryBtn = getColor(R.color.safe_green)
            )
            EmergencyUiState.LOCATION_CONFIRM -> StateTheme(
                background = getColor(R.color.bg_location),
                statusBar = getColor(R.color.police_blue_dark),
                titleColor = getColor(R.color.text_primary),
                primaryBtn = getColor(R.color.safe_green),
                secondaryBtn = getColor(R.color.police_blue)
            )
            EmergencyUiState.DISPATCH_ACTIVE -> StateTheme(
                background = getColor(R.color.bg_dispatch),
                statusBar = getColor(R.color.safe_green_dark),
                titleColor = getColor(R.color.safe_green),
                primaryBtn = getColor(R.color.neutral_dark),
                secondaryBtn = getColor(R.color.police_blue)
            )
        }

        rootLayout.setBackgroundColor(theme.background)
        window.statusBarColor = theme.statusBar
        titleText.setTextColor(theme.titleColor)
        primaryActionButton.backgroundTintList = ColorStateList.valueOf(theme.primaryBtn)
        secondaryActionButton.backgroundTintList = ColorStateList.valueOf(theme.secondaryBtn)
        // Tertiary/quaternary get contextual colors set directly in renderState()
    }

    private fun hideAllOptionButtons() {
        primaryActionButton.visibility = View.GONE
        primaryActionButton.isEnabled = true
        primaryActionButton.alpha = 1.0f
        secondaryActionButton.visibility = View.GONE
        tertiaryActionButton.visibility = View.GONE
        quaternaryActionButton.visibility = View.GONE
    }

    private fun startCountdownIfNeeded() {
        if (countdownTimer != null) return

        Log.i(TAG, "Countdown started (${AppConfig.COUNTDOWN_DURATION_MS / 1000}s)")
        countdownTimer = object : CountDownTimer(AppConfig.COUNTDOWN_DURATION_MS, AppConfig.COUNTDOWN_TICK_MS) {
            override fun onTick(millisUntilFinished: Long) {
                subtitleText.text = "Dispatch starts in ${millisUntilFinished / 1000}s unless cancelled."
            }

            override fun onFinish() {
                Log.i(TAG, "Countdown finished -> SERVICE_SELECTION")
                countdownTimer = null
                emergencyViewModel.onCountdownFinished()
            }
        }.start()
    }

    private fun cancelTimer() {
        Log.d(TAG, "Countdown timer cancelled")
        countdownTimer?.cancel()
        countdownTimer = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Member 1 TODO: Hook into onKeyDown to listen for Volume Button presses as emergency triggers.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            emergencyViewModel.onEmergencyTrigger("VOLUME_BUTTON")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        countdownTimer?.cancel()
        locationHandler.stopLocationUpdates()
        interactionManager.release()
        if (isFallReceiverRegistered) {
            unregisterReceiver(fallReceiver)
            isFallReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun requestCriticalPermissionsIfNeeded() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filterNot(::hasPermission)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    /**
     * Prompt the user to exempt this app from battery optimizations.
     * Critical on OEMs (Samsung, Xiaomi, Huawei, OPPO, OnePlus, Vivo) that
     * aggressively kill background services.
     */
    private fun requestBatteryOptimizationExemptionIfNeeded() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.d(TAG, "Battery optimization already exempted.")
            logOemBackgroundRestrictionGuidance()
            return
        }

        Log.w(TAG, "App is NOT exempt from battery optimization. Requesting exemption.")
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open battery optimization settings: ${e.message}")
            subtitleText.text = "Please disable battery optimization for this app in Settings."
        }

        logOemBackgroundRestrictionGuidance()
    }

    /**
     * Log manufacturer-specific instructions for disabling aggressive background
     * killers. In a future iteration this could display a UI dialog.
     */
    private fun logOemBackgroundRestrictionGuidance() {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val guidance = when {
            manufacturer.contains("samsung") ->
                "Samsung: Settings > Battery > Background usage limits > Never sleeping apps > Add this app."
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") ->
                "Xiaomi/POCO: Settings > Battery > App battery saver > Choose 'No restrictions' for this app. " +
                "Also enable Autostart in Security app."
            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                "Huawei/Honor: Settings > Battery > App launch > Set this app to 'Manage manually' with all toggles ON."
            manufacturer.contains("oppo") || manufacturer.contains("realme") ->
                "OPPO/Realme: Settings > Battery > More battery settings > Optimize battery use > " +
                "Exclude this app. Also allow Auto-start."
            manufacturer.contains("vivo") ->
                "Vivo: Settings > Battery > Background power consumption management > Allow this app."
            manufacturer.contains("oneplus") ->
                "OnePlus: Settings > Battery > Battery optimization > All apps > This app > Don't optimize."
            else -> null
        }

        if (guidance != null) {
            Log.i(TAG, "OEM background restriction guidance for ${Build.MANUFACTURER}: $guidance")
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun handleServiceLaunchIntent(intent: Intent?) {
        val incomingIntent = intent ?: return
        val launchFromService = incomingIntent.getBooleanExtra(FallDetectionService.EXTRA_START_DROP_COUNTDOWN, false)
        if (!launchFromService) return
        val source = incomingIntent.getStringExtra(FallDetectionService.EXTRA_START_SOURCE) ?: "SERVICE_TRIGGER"
        Log.i(TAG, "Launch intent from sensor service. source=$source")
        emergencyViewModel.onEmergencyTrigger(source)
        incomingIntent.removeExtra(FallDetectionService.EXTRA_START_DROP_COUNTDOWN)
        incomingIntent.removeExtra(FallDetectionService.EXTRA_START_SOURCE)
    }
}
