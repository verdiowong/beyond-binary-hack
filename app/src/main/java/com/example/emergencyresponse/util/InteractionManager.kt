package com.example.emergencyresponse.util

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.emergencyresponse.model.ContextOption
import com.example.emergencyresponse.model.EmergencyUiState
import com.example.emergencyresponse.model.ServiceConfig
import java.util.Locale

/**
 * Manages Text-to-Speech (TTS), Speech-to-Text (STT), and haptic feedback
 * for the emergency response flow.
 *
 * Key design: STT only starts **after the last queued TTS utterance finishes**,
 * preventing the microphone from picking up the speaker output.
 */
class InteractionManager(private val context: Context) {

    companion object {
        private const val TAG = "InteractionManager"
        private const val UTT_STATE = "state_tts"
        private const val UTT_OPTIONS = "options_tts"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Saved music stream volume, restored after TTS finishes. */
    private var savedMusicVolume: Int = -1

    // ── TTS ─────────────────────────────────────────────────────────────────

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /**
     * The utterance ID we are waiting for before starting STT.
     * If only a state announcement is queued, this is [UTT_STATE].
     * If options are also queued, this is [UTT_OPTIONS].
     * STT starts only when [onDone] fires with this ID.
     */
    private var lastExpectedUtterance: String = UTT_STATE

    /** Callback to start STT after all TTS completes. */
    private var pendingSttStart: (() -> Unit)? = null

    // ── STT ─────────────────────────────────────────────────────────────────

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var currentKeywords: Map<String, String> = emptyMap()
    private var keywordCallback: ((String) -> Unit)? = null

    // ── Haptics ─────────────────────────────────────────────────────────────

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    fun initialize() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS started: $utteranceId")
                        // Duck the music/TTS stream volume so STT (if running
                        // in parallel) is less likely to pick up speaker echo.
                        duckVolume()
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS done: $utteranceId (waiting for: $lastExpectedUtterance)")
                        restoreVolume()
                        // Only fire pending STT when the LAST expected utterance finishes
                        if (utteranceId == lastExpectedUtterance) {
                            mainHandler.post {
                                pendingSttStart?.invoke()
                                pendingSttStart = null
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.w(TAG, "TTS error: $utteranceId")
                        restoreVolume()
                        if (utteranceId == lastExpectedUtterance) {
                            mainHandler.post {
                                pendingSttStart?.invoke()
                                pendingSttStart = null
                            }
                        }
                    }
                })
                Log.d(TAG, "TTS initialized successfully")
            } else {
                Log.e(TAG, "TTS init failed with status=$status")
            }
        }
    }

    fun release() {
        pendingSttStart = null
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    // ═══════════════════════════════════════════════════════════════════════
    // State change handler (called from MainActivity.renderState)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Announce the state change via TTS and trigger haptic feedback.
     * TTS messages are kept SHORT to maximize user response time.
     */
    fun onStateChanged(state: EmergencyUiState) {
        val spoken = when (state) {
            EmergencyUiState.IDLE -> "Idle."
            // SHORT — leaves ~8s for the user to respond within the 10s countdown
            EmergencyUiState.DROP_COUNTDOWN -> "Fall detected. Say help, or okay to cancel."
            EmergencyUiState.SERVICE_SELECTION -> "Choose service."
            EmergencyUiState.CONTEXT_SELECTION -> "What is happening?"
            EmergencyUiState.LOCATION_CONFIRM -> "Confirm location."
            EmergencyUiState.DISPATCH_ACTIVE -> "Dispatch sent."
        }
        // Reset: assume no options will be queued (countdown, location, dispatch)
        lastExpectedUtterance = UTT_STATE
        speak(spoken)
        vibrateForState(state)
    }

    /**
     * After announcing the state, also announce the available option labels.
     * Updates [lastExpectedUtterance] so STT waits for this to finish too.
     */
    fun announceOptions(optionLabels: List<String>) {
        if (optionLabels.isEmpty()) return
        val optionsText = optionLabels.mapIndexed { i, label ->
            "Option ${i + 1}, $label."
        }.joinToString(" ")

        // Mark this as the last utterance — STT must wait for it
        lastExpectedUtterance = UTT_OPTIONS
        tts?.speak(optionsText, TextToSpeech.QUEUE_ADD, Bundle(), UTT_OPTIONS)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TTS
    // ═══════════════════════════════════════════════════════════════════════

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), UTT_STATE)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STT (Speech-to-Text) — Voice keyword recognition
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Schedule STT to start **after the last queued TTS utterance finishes**.
     * This prevents the mic from picking up TTS audio and false-matching keywords.
     */
    fun startListeningAfterTts(keywords: Map<String, String>, onMatch: (String) -> Unit) {
        pendingSttStart = { startListeningNow(keywords, onMatch) }
        // If TTS isn't active at all (e.g. TTS init failed), start after a brief safety delay
        if (!ttsReady) {
            mainHandler.postDelayed({
                pendingSttStart?.invoke()
                pendingSttStart = null
            }, 500)
        }
        // Otherwise, the UtteranceProgressListener.onDone will trigger it
    }

    /**
     * Start STT **immediately** without waiting for TTS to finish.
     * Use this for time-critical states (e.g. DROP_COUNTDOWN) where the user
     * needs to be able to speak right away. Volume ducking in the TTS
     * UtteranceProgressListener reduces echo pickup from the speaker.
     */
    fun startListeningImmediately(keywords: Map<String, String>, onMatch: (String) -> Unit) {
        pendingSttStart = null  // cancel any pending deferred start
        startListeningNow(keywords, onMatch)
    }

    /**
     * Immediately start the speech recognizer.
     */
    private fun startListeningNow(keywords: Map<String, String>, onMatch: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available on this device")
            return
        }

        stopListening()

        currentKeywords = keywords
        keywordCallback = onMatch

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(createRecognitionListener())
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-SG")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            Log.d(TAG, "STT started listening for keywords: ${keywords.keys}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start STT: ${e.message}")
            isListening = false
        }
    }

    fun stopListening() {
        pendingSttStart = null
        if (isListening) {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping STT: ${e.message}")
            }
            isListening = false
        }
    }

    /**
     * Build keyword maps for each emergency state.
     *
     * IMPORTANT: Escalation/action keywords come FIRST so they take priority
     * over cancel keywords if both appear in the same utterance.
     */
    fun keywordsForState(state: EmergencyUiState, contextOptions: List<ContextOption> = emptyList(), userName: String = ""): Map<String, String> {
        return when (state) {
            EmergencyUiState.DROP_COUNTDOWN -> linkedMapOf(
                // Escalation keywords FIRST
                "help" to "ESCALATE",
                "help me" to "ESCALATE",
                "emergency" to "ESCALATE",
                "i need help" to "ESCALATE",
                "need help" to "ESCALATE",
                "assist" to "ESCALATE",
                // Cancel keywords after
                "i'm okay" to "CANCEL",
                "i am okay" to "CANCEL",
                "im okay" to "CANCEL",
                "i'm fine" to "CANCEL",
                "i am fine" to "CANCEL",
                "okay" to "CANCEL",
                "cancel" to "CANCEL",
                "fine" to "CANCEL",
                "no" to "CANCEL"
            )
            EmergencyUiState.SERVICE_SELECTION -> linkedMapOf(
                "fire" to ServiceConfig.FIRE.scdfPrefix,
                "ambulance" to ServiceConfig.AMBULANCE.scdfPrefix,
                "police" to ServiceConfig.POLICE.scdfPrefix
            )
            EmergencyUiState.CONTEXT_SELECTION -> {
                val map = linkedMapOf<String, String>()
                contextOptions.forEach { map[it.label.lowercase()] = it.smsDescription(userName) }
                map
            }
            EmergencyUiState.LOCATION_CONFIRM -> linkedMapOf(
                "yes" to "CONFIRM",
                "confirm" to "CONFIRM",
                "correct" to "CONFIRM",
                "no" to "CANCEL",
                "cancel" to "CANCEL",
                "wrong" to "CANCEL"
            )
            else -> emptyMap()
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "STT ready for speech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "STT user started speaking")
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "STT user stopped speaking")
            }

            override fun onError(error: Int) {
                val errorName = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
                    SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
                    SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
                    SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
                    SpeechRecognizer.ERROR_SERVER -> "SERVER"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "NO_PERMISSION"
                    else -> "UNKNOWN($error)"
                }
                Log.w(TAG, "STT error: $errorName")
                isListening = false
                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    restartListening()
                }
            }

            override fun onResults(results: Bundle?) {
                val matched = processResults(results)
                if (!matched) {
                    restartListening()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    Log.d(TAG, "STT partial (ignored): ${matches.joinToString()}")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    /**
     * Process final STT results. Returns true if a keyword was matched.
     */
    private fun processResults(results: Bundle?): Boolean {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (matches.isNullOrEmpty()) return false

        Log.d(TAG, "STT final results: ${matches.joinToString(" | ")}")

        for (match in matches) {
            val lower = match.lowercase()
            for ((keyword, action) in currentKeywords) {
                if (lower.contains(keyword)) {
                    Log.i(TAG, "STT keyword MATCHED: '$keyword' -> action '$action' (heard: '$match')")
                    keywordCallback?.invoke(action)
                    return true
                }
            }
        }
        Log.d(TAG, "STT: no keyword matched in results")
        return false
    }

    private fun restartListening() {
        if (currentKeywords.isNotEmpty() && keywordCallback != null) {
            mainHandler.postDelayed({
                if (currentKeywords.isNotEmpty()) {
                    startListeningNow(currentKeywords, keywordCallback ?: return@postDelayed)
                }
            }, 600)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Volume ducking — reduce speaker volume while TTS plays so parallel
    // STT is less likely to pick up echo from the speaker output.
    // ═══════════════════════════════════════════════════════════════════════

    private fun duckVolume() {
        try {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (savedMusicVolume < 0) savedMusicVolume = current
            val ducked = (current * 0.7).toInt().coerceAtLeast(1)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, ducked, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Volume duck failed: ${e.message}")
        }
    }

    private fun restoreVolume() {
        try {
            if (savedMusicVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
                savedMusicVolume = -1
            }
        } catch (e: Exception) {
            Log.w(TAG, "Volume restore failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Haptic feedback
    // ═══════════════════════════════════════════════════════════════════════

    @Suppress("DEPRECATION")
    private fun vibrateForState(state: EmergencyUiState) {
        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = when (state) {
                EmergencyUiState.DROP_COUNTDOWN -> {
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 100, 80, 100, 80, 100, 80, 100),
                        intArrayOf(0, 200, 0, 200, 0, 200, 0, 200),
                        -1
                    )
                }
                EmergencyUiState.SERVICE_SELECTION -> {
                    VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                EmergencyUiState.CONTEXT_SELECTION -> {
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 100, 100, 100),
                        intArrayOf(0, 180, 0, 180),
                        -1
                    )
                }
                EmergencyUiState.LOCATION_CONFIRM -> {
                    VibrationEffect.createOneShot(100, 100)
                }
                EmergencyUiState.DISPATCH_ACTIVE -> {
                    VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                EmergencyUiState.IDLE -> null
            }
            effect?.let { vibrator.vibrate(it) }
        } else {
            when (state) {
                EmergencyUiState.DROP_COUNTDOWN -> vibrator.vibrate(longArrayOf(0, 100, 80, 100, 80, 100), -1)
                EmergencyUiState.SERVICE_SELECTION -> vibrator.vibrate(150)
                EmergencyUiState.DISPATCH_ACTIVE -> vibrator.vibrate(400)
                else -> {}
            }
        }
    }
}
