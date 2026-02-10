package com.example.emergencyresponse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.CountDownTimer
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.ViewSwitcher
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EmergencyUiState {
    IDLE,
    DROP_COUNTDOWN,
    SERVICE_SELECTION,
    CONTEXT_SELECTION,
    LOCATION_CONFIRM,
    DISPATCH_ACTIVE
}

data class EmergencyEvent(
    val source: String,
    val state: EmergencyUiState,
    val service: String? = null,
    val context: String? = null,
    val location: String? = null,
    val timestampMillis: Long = System.currentTimeMillis()
)

data class EmergencyUiModel(
    val state: EmergencyUiState = EmergencyUiState.IDLE,
    val selectedService: String? = null,
    val selectedContext: String? = null,
    val resolvedAddress: String? = null
)

class EmergencyViewModel : ViewModel() {
    private val _uiModel = MutableStateFlow(EmergencyUiModel())
    val uiModel: StateFlow<EmergencyUiModel> = _uiModel.asStateFlow()

    private val _events = MutableSharedFlow<EmergencyEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<EmergencyEvent> = _events.asSharedFlow()

    fun onEmergencyTrigger(source: String) {
        _uiModel.update { it.copy(state = EmergencyUiState.DROP_COUNTDOWN) }
        _events.tryEmit(EmergencyEvent(source = source, state = EmergencyUiState.DROP_COUNTDOWN))
    }

    fun onCountdownFinished() {
        _uiModel.update { it.copy(state = EmergencyUiState.SERVICE_SELECTION) }
        _events.tryEmit(EmergencyEvent(source = "COUNTDOWN", state = EmergencyUiState.SERVICE_SELECTION))
    }

    fun onServiceSelected(service: String) {
        _uiModel.update { it.copy(state = EmergencyUiState.CONTEXT_SELECTION, selectedService = service) }
        _events.tryEmit(
            EmergencyEvent(
                source = "USER",
                state = EmergencyUiState.CONTEXT_SELECTION,
                service = service
            )
        )
    }

    fun onContextSelected(context: String) {
        _uiModel.update { it.copy(state = EmergencyUiState.LOCATION_CONFIRM, selectedContext = context) }
        _events.tryEmit(
            EmergencyEvent(
                source = "USER",
                state = EmergencyUiState.LOCATION_CONFIRM,
                service = _uiModel.value.selectedService,
                context = context
            )
        )
    }

    fun onLocationResolved(address: String) {
        _uiModel.update { it.copy(resolvedAddress = address) }
    }

    fun onLocationConfirmed() {
        _uiModel.update { it.copy(state = EmergencyUiState.DISPATCH_ACTIVE) }
        _events.tryEmit(
            EmergencyEvent(
                source = "USER",
                state = EmergencyUiState.DISPATCH_ACTIVE,
                service = _uiModel.value.selectedService,
                context = _uiModel.value.selectedContext,
                location = _uiModel.value.resolvedAddress
            )
        )
    }

    fun onCancel() {
        _uiModel.value = EmergencyUiModel()
        _events.tryEmit(EmergencyEvent(source = "USER", state = EmergencyUiState.IDLE))
    }
}

class MainActivity : AppCompatActivity() {
    private val emergencyViewModel: EmergencyViewModel by viewModels()

    private lateinit var stateSwitcher: ViewSwitcher
    private lateinit var idleTriggerButton: Button
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var primaryActionButton: Button
    private lateinit var secondaryActionButton: Button
    private lateinit var cancelButton: Button

    private lateinit var interactionManager: InteractionManager
    private lateinit var locationHandler: LocationHandler
    private lateinit var dispatchBridge: DispatchBridge

    private var countdownTimer: CountDownTimer? = null
    private var isFallReceiverRegistered = false

    private val fallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FallDetectionService.ACTION_FALL_DETECTED) {
                emergencyViewModel.onEmergencyTrigger("FALL_DETECTION_SERVICE")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stateSwitcher = findViewById(R.id.stateSwitcher)
        idleTriggerButton = findViewById(R.id.idleTriggerButton)
        titleText = findViewById(R.id.titleText)
        subtitleText = findViewById(R.id.subtitleText)
        primaryActionButton = findViewById(R.id.primaryActionButton)
        secondaryActionButton = findViewById(R.id.secondaryActionButton)
        cancelButton = findViewById(R.id.cancelButton)

        interactionManager = InteractionManager(this).also { it.initialize() }
        locationHandler = LocationHandler(this)
        dispatchBridge = DispatchBridge(this)

        setupUiActions()
        observeState()
    }

    override fun onStart() {
        super.onStart()
        startService(Intent(this, FallDetectionService::class.java))
        if (!isFallReceiverRegistered) {
            registerReceiver(
                fallReceiver,
                IntentFilter(FallDetectionService.ACTION_FALL_DETECTED)
            )
            isFallReceiverRegistered = true
        }
    }

    private fun setupUiActions() {
        idleTriggerButton.setOnClickListener {
            emergencyViewModel.onEmergencyTrigger("IDLE_SCREEN_BUTTON")
        }

        primaryActionButton.setOnClickListener {
            when (emergencyViewModel.uiModel.value.state) {
                EmergencyUiState.IDLE -> emergencyViewModel.onEmergencyTrigger("UI_BUTTON")
                EmergencyUiState.SERVICE_SELECTION -> emergencyViewModel.onServiceSelected("Ambulance")
                EmergencyUiState.CONTEXT_SELECTION -> emergencyViewModel.onContextSelected("Fall with possible injury")
                EmergencyUiState.LOCATION_CONFIRM -> emergencyViewModel.onLocationConfirmed()
                EmergencyUiState.DISPATCH_ACTIVE -> emergencyViewModel.onCancel()
                else -> Unit
            }
        }

        secondaryActionButton.setOnClickListener {
            when (emergencyViewModel.uiModel.value.state) {
                EmergencyUiState.SERVICE_SELECTION -> emergencyViewModel.onServiceSelected("Police")
                EmergencyUiState.CONTEXT_SELECTION -> emergencyViewModel.onContextSelected("I am okay")
                else -> Unit
            }
        }

        cancelButton.setOnClickListener { emergencyViewModel.onCancel() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                emergencyViewModel.uiModel.collect { renderState(it) }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                emergencyViewModel.events.collect { handleEvent(it) }
            }
        }
    }

    private fun handleEvent(event: EmergencyEvent) {
        when (event.state) {
            EmergencyUiState.LOCATION_CONFIRM -> {
                locationHandler.requestHighAccuracyLocation(
                    onAddressReady = { address -> emergencyViewModel.onLocationResolved(address) },
                    onError = { message -> subtitleText.text = message }
                )
            }

            EmergencyUiState.DISPATCH_ACTIVE -> {
                val (unitNumber, medicalId) = locationHandler.loadUserProfile()
                val contextWithMedical =
                    listOfNotNull(event.context, medicalId?.let { "Medical ID: $it" }).joinToString(". ")
                val dispatchEvent = event.copy(context = contextWithMedical)

                dispatchBridge.dispatchSms(
                    event = dispatchEvent,
                    unitNumber = unitNumber ?: "01-01",
                    caregiverPhone = "+6500000000",
                    onComplete = { success, result ->
                        subtitleText.text = if (success) result else "Dispatch error: $result"
                    }
                )
            }

            else -> Unit
        }
    }

    private fun renderState(model: EmergencyUiModel) {
        val flowVisible = model.state != EmergencyUiState.IDLE
        stateSwitcher.displayedChild = if (flowVisible) 1 else 0

        when (model.state) {
            EmergencyUiState.IDLE -> {
                cancelTimer()
                titleText.text = "Ready"
                subtitleText.text = "Press and hold a large button or volume key to trigger emergency flow."
                primaryActionButton.text = "Start Emergency"
                secondaryActionButton.visibility = View.GONE
                cancelButton.visibility = View.GONE
            }

            EmergencyUiState.DROP_COUNTDOWN -> {
                titleText.text = "Fall detected"
                subtitleText.text = "Dispatch starts in 10 seconds unless cancelled."
                primaryActionButton.text = "Keep me safe"
                secondaryActionButton.visibility = View.GONE
                cancelButton.visibility = View.VISIBLE
                startCountdownIfNeeded()
            }

            EmergencyUiState.SERVICE_SELECTION -> {
                cancelTimer()
                titleText.text = "Choose service"
                subtitleText.text = "Select emergency service type."
                primaryActionButton.text = "Ambulance"
                secondaryActionButton.text = "Police"
                secondaryActionButton.visibility = View.VISIBLE
                cancelButton.visibility = View.VISIBLE
            }

            EmergencyUiState.CONTEXT_SELECTION -> {
                titleText.text = "Describe context"
                subtitleText.text = "Selected: ${model.selectedService ?: "Unknown"}"
                primaryActionButton.text = "Fall / Injury"
                secondaryActionButton.text = "I am okay"
                secondaryActionButton.visibility = View.VISIBLE
                cancelButton.visibility = View.VISIBLE
            }

            EmergencyUiState.LOCATION_CONFIRM -> {
                titleText.text = "Confirm location"
                subtitleText.text = model.resolvedAddress ?: "Resolving location..."
                primaryActionButton.text = "Confirm & Dispatch"
                secondaryActionButton.visibility = View.GONE
                cancelButton.visibility = View.VISIBLE
            }

            EmergencyUiState.DISPATCH_ACTIVE -> {
                cancelTimer()
                titleText.text = "Dispatch active"
                subtitleText.text = "Emergency notification has been sent."
                primaryActionButton.text = "Reset to idle"
                secondaryActionButton.visibility = View.GONE
                cancelButton.visibility = View.GONE
            }
        }

        interactionManager.onStateChanged(model.state)
    }

    private fun startCountdownIfNeeded() {
        if (countdownTimer != null) return

        // Member 1 TODO: Implement a 10-second CountDownTimer.
        // If it finishes, move to SERVICE_SELECTION.
        countdownTimer = object : CountDownTimer(10_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                subtitleText.text = "Dispatch starts in ${millisUntilFinished / 1000}s unless cancelled."
            }

            override fun onFinish() {
                countdownTimer = null
                emergencyViewModel.onCountdownFinished()
            }
        }.start()
    }

    private fun cancelTimer() {
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
}
