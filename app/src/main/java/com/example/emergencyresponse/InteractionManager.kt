package com.example.emergencyresponse

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

class InteractionManager(private val context: Context) {
    private var tts: TextToSpeech? = null

    fun initialize() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }
    }

    fun onStateChanged(state: EmergencyUiState) {
        val spoken = when (state) {
            EmergencyUiState.IDLE -> "System is idle."
            EmergencyUiState.DROP_COUNTDOWN -> "Possible fall detected. Countdown started."
            EmergencyUiState.SERVICE_SELECTION -> "Please choose service. Fire, Ambulance, or Police."
            EmergencyUiState.CONTEXT_SELECTION -> "Please describe your situation."
            EmergencyUiState.LOCATION_CONFIRM -> "Please confirm your location."
            EmergencyUiState.DISPATCH_ACTIVE -> "Dispatch in progress."
        }
        speak(spoken)
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "emergency_tts")
    }

    // Member 3 TODO: Integrate SpeechToText (STT) to listen for keywords:
    // "Fire," "Ambulance," "Police," "Yes," or "No."
    fun startVoiceKeywordListening(onKeywordDetected: (String) -> Unit) {
        // Placeholder: wire SpeechRecognizer + partial results here.
        // Example callback: onKeywordDetected("Ambulance")
    }

    // Member 3 TODO: Setup a Camera placeholder that detects "Any Motion"
    // in the frame as an "I am okay" signal.
    fun startCameraMotionWatch(onMotionDetected: () -> Unit) {
        // Placeholder: wire CameraX preview frames + simple frame differencing.
        // Example callback: onMotionDetected()
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
