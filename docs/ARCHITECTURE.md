# Architecture & Design Document

## Overview

This document describes the architecture, design decisions, and implementation details of the Beyond Binary Inclusive Emergency Response App -- a native Android application built for the Beyond Binary Hackathon 2026.

The app is designed for **motor-impaired and vulnerable users** in Singapore, providing an accessible emergency response flow that works through multiple interaction modalities (touch, voice, sensor, haptics) and integrates with SCDF's Emergency SMS service.

---

## System Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│                           Android Device                                 │
│                                                                          │
│  ┌─────────────────┐     ┌──────────────────┐     ┌──────────────────┐  │
│  │ FallDetection    │     │  MainActivity     │     │  Bystander       │  │
│  │ Service          │────>│  (Emergency Flow) │     │  Cards Activity  │  │
│  │ (Foreground)     │     │                   │     │                  │  │
│  │                  │     │  EmergencyViewModel│     │  OpenAiService   │  │
│  │ Accelerometer    │     │  InteractionMgr   │     │  (GPT-4o-mini)   │  │
│  │ Fall Detection   │     │  LocationHandler  │     │                  │  │
│  │ Tremor Detection │     │  DispatchBridge   │     │  Detail Activity │  │
│  └────────┬─────────┘     └────────┬──────────┘     └──────────────────┘  │
│           │                        │                                      │
│           │  broadcast /           │  SMS                                 │
│           │  direct launch         │                                      │
│           v                        v                                      │
│  ┌─────────────────┐     ┌──────────────────┐     ┌──────────────────┐  │
│  │ BootReceiver     │     │ SCDF 70995       │     │ OpenAI API       │  │
│  │ (auto-start)     │     │ Caregiver SMS    │     │ (cloud)          │  │
│  └─────────────────┘     └──────────────────┘     └──────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## Design Patterns

### MVVM (Model-View-ViewModel)

```
┌──────────────┐      observes       ┌─────────────────┐      reads/writes     ┌──────────────┐
│   Activity   │ ◄──── StateFlow ──── │ EmergencyViewModel│ ◄─────────────────── │  Model Layer │
│ (View)       │                      │ (ViewModel)       │                      │              │
│              │ ────── events ──────>│                   │ ────── persist ─────>│ UserProfile  │
│ MainActivity │                      │ _uiState          │                      │ Repository   │
│ Onboarding   │                      │ _emergencyEvent   │                      │ AppConfig    │
│ Settings     │                      │ _stateEvents      │                      │ ServiceConfig│
└──────────────┘                      └─────────────────┘                      └──────────────┘
```

- **ViewModel** owns all emergency state via `MutableStateFlow`.
- **Activities** observe flows using `lifecycleScope` + `repeatOnLifecycle`.
- **State transitions** are atomic -- `compareAndSet` ensures consistency.

### Centralized State Machine

The emergency flow uses a single enum-driven state machine with 6 states:

```kotlin
enum class EmergencyUiState {
    IDLE,               // Monitoring, no emergency
    DROP_COUNTDOWN,     // Fall detected, 10-second countdown
    SERVICE_SELECTION,  // Choose: Fire / Ambulance / Police
    CONTEXT_SELECTION,  // What's happening? (context-specific options)
    LOCATION_CONFIRM,   // Confirm GPS location on map
    DISPATCH_ACTIVE     // SMS sent, emergency active
}
```

**State transition rules:**
- Forward: `IDLE → DROP_COUNTDOWN → SERVICE_SELECTION → CONTEXT_SELECTION → LOCATION_CONFIRM → DISPATCH_ACTIVE`
- Cancel: Any state → `IDLE`
- Auto-escalation: `DROP_COUNTDOWN` timer expires → `SERVICE_SELECTION`

### Package-by-Feature Structure

```
model/    — Data classes, enums, configuration, persistence
service/  — Background services (fall detection, boot receiver)
util/     — Utility modules (SMS, location, voice, AI)
ui/       — Activities and ViewModel
```

---

## Core Components

### 1. FallDetectionService

**Type:** Foreground Service (`foregroundServiceType="health"`)

**Detection Pipeline:**

```
Accelerometer Data
       │
       ▼
┌──────────────┐    ┌───────────────┐    ┌────────────────┐
│  Free-Fall   │───>│    Impact     │───>│   Stillness    │───> TRIGGER
│  < 3.0 m/s²  │    │  > 15.0 m/s²  │    │  Post-impact   │
│  ≥ 150ms     │    │  within 1.5s  │    │  low motion    │
└──────────────┘    └───────────────┘    └────────────────┘

Linear Acceleration Data
       │
       ▼
┌──────────────────┐
│ Tremor Detection │
│ RMS ≥ 2.0 m/s²   │
│ Zero-cross 18-80 │
│ 5 sustained wins │───> TRIGGER
└──────────────────┘
```

**Key Design Decisions:**
- Uses `PARTIAL_WAKE_LOCK` to keep CPU active for sensor processing.
- Attempts direct `startActivity()` on trigger for immediate screen pop-up; falls back to full-screen notification intent on OEMs that block background activity starts.
- 20-second cooldown between triggers to prevent spam.

### 2. InteractionManager

Manages all user interaction modalities:

| Modality | Technology | Usage |
|---|---|---|
| Voice Output | `TextToSpeech` | Announce state changes, read options |
| Voice Input | `SpeechRecognizer` | Keyword matching ("help", "fire", etc.) |
| Haptics | `Vibrator` / `VibrationEffect` | Distinct patterns per state |
| Volume Ducking | `AudioManager` | Lower speaker volume while TTS plays to reduce STT echo |

**TTS Echo Prevention:**
- Tracks `lastSpokenPrompt` from TTS.
- `looksLikeTtsEcho()` compares STT results against the prompt using word overlap ratio.
- Results with >60% overlap and >3 common words are filtered out.
- Volume ducking reduces `STREAM_MUSIC` during TTS playback.

### 3. LocationHandler

- Uses `FusedLocationProviderClient` for GPS.
- `Geocoder` for reverse-geocoding coordinates to a street address.
- Returns a `ResolvedLocation` data class with lat, lng, and formatted address.
- Checks `LocationManager.isProviderEnabled()` and prompts user to enable GPS if disabled.

### 4. DispatchBridge

- Formats SMS per SCDF guidelines.
- `MOCK_DISPATCH_MODE` flag controls whether SCDF SMS is actually sent.
- `TEST_CAREGIVER_SMS` flag allows real SMS to caregivers in mock mode.
- Caregiver SMS includes a Google Maps link: `https://maps.google.com/?q={lat},{lng}`
- SMS templates use `{name}` placeholder filled from `UserProfile`.

### 5. OpenAiService

- Lightweight HTTP client using `HttpURLConnection` (no external dependencies).
- Calls OpenAI `gpt-4o-mini` Chat Completions API.
- Prompt specifies exactly 6 card categories and enforces JSON output format.
- Strips markdown code fences from response if present.
- Comprehensive logging at every step for debugging.
- API key injected via `BuildConfig.OPENAI_API_KEY` from `gradle.properties`.

### 6. UserProfileRepository

- `SharedPreferences`-based persistence.
- Stores: name, address, unit number, caregiver number, secondary contact, medical conditions, allergies, blood type, medications, medical ID, communication mode.
- `isOnboardingComplete` flag controls first-launch behaviour.

---

## Data Flow

### Emergency Trigger → Dispatch

```
1. FallDetectionService detects fall/tremor
2. Service sends broadcast ACTION_FALL_DETECTED + attempts direct Activity launch
3. MainActivity receives broadcast/intent
4. EmergencyViewModel.onEmergencyTrigger() → state = DROP_COUNTDOWN
5. InteractionManager announces state, starts STT
6. User responds (voice/touch) or timer expires
7. State machine progresses through SERVICE_SELECTION → CONTEXT_SELECTION → LOCATION_CONFIRM
8. At each step: InteractionManager reads options via TTS, listens via STT, provides haptics
9. LocationHandler resolves GPS + address
10. User confirms location (with map)
11. DispatchBridge formats and sends SMS to SCDF + caregiver
12. State = DISPATCH_ACTIVE (user sees confirmation + can view medical card)
```

### Bystander Cards Flow

```
1. User taps "Bystander Help Cards" on idle screen
2. BystanderCardsActivity opens with loading spinner
3. OpenAiService.generateBystanderCards() called with UserProfile + language code
4. If API succeeds: 6 personalised cards displayed in colour-coded grid
5. If API fails/unconfigured: 6 hardcoded fallback cards shown
6. User taps card → BystanderCardDetailActivity with full text
7. Language toggle buttons trigger re-fetch from OpenAI in selected language
8. "Speak Message" button reads card via TTS with locale-appropriate voice
```

---

## Security Considerations

- **API Key**: OpenAI key stored in `gradle.properties` (not committed with real value). Injected at compile time via `BuildConfig`.
- **SMS**: SCDF dispatch is mocked by default to prevent accidental emergency calls during development.
- **Permissions**: All sensitive permissions are requested at runtime with clear explanations.
- **No Network for Core Flow**: The core emergency flow (fall detection → SMS dispatch) works entirely offline. Only bystander card generation requires internet.

---

## Accessibility Design Principles

1. **Redundancy**: Every interaction has at least 2 modalities (e.g., touch + voice, visual + audio).
2. **Graceful Degradation**: If voice fails, touch still works. If AI fails, fallback cards appear. If GPS fails, manual address is used.
3. **Time Sensitivity**: During `DROP_COUNTDOWN`, STT starts immediately without waiting for TTS to finish. Auto-escalation ensures help is called even if the user cannot respond.
4. **Cognitive Load**: Each screen asks one question with 3-4 large, clearly labelled options.
5. **Inclusivity**: Multi-language support for bystander cards. Medical info shared in plain, bystander-readable format.

---

## Dependencies

| Dependency | Version | Purpose |
|---|---|---|
| `androidx.core:core-ktx` | 1.12.0 | Kotlin extensions for Android |
| `androidx.appcompat:appcompat` | 1.6.1 | Backward-compatible UI |
| `androidx.activity:activity-ktx` | 1.9.3 | Activity result APIs |
| `androidx.lifecycle:lifecycle-*` | 2.7.0 | ViewModel, StateFlow |
| `com.google.android.material:material` | 1.11.0 | Material Design 3 components |
| `com.google.android.gms:play-services-location` | 21.1.0 | GPS location |
| `kotlinx-coroutines-android` | 1.7.3 | Coroutines |
| `org.json` | (Android built-in) | JSON parsing for OpenAI |

No external HTTP libraries -- uses `java.net.HttpURLConnection` for OpenAI calls.

---

## Future Enhancements

- Camera-based thumbs-up gesture detection for non-verbal "I'm okay" response.
- Integration with Singapore's myResponder app for CPR-trained bystander alerts.
- Wearable companion (Wear OS) for wrist-based fall detection.
- Multi-user household support with individual profiles.
- Real-time location sharing with caregivers during active emergency.
