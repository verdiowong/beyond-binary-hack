# Beyond Binary Hack - Inclusive Emergency Response App

A native Android (Kotlin) emergency response application designed for motor-impaired users. The app detects falls via accelerometer sensors, guides the user through an accessible emergency flow using voice, touch, and haptic feedback, and dispatches SMS alerts to SCDF (Singapore Civil Defence Force) and caregiver contacts.

## Features

- **Fall & Tremor Detection** -- Background foreground service monitors the accelerometer for free-fall + impact sequences and sustained severe tremor patterns.
- **Accessible Emergency Flow** -- Large touch targets, Text-to-Speech announcements, Speech-to-Text voice commands, and haptic vibration patterns for each state.
- **Multi-step Emergency Wizard**:
  1. **Drop Countdown** -- 10-second countdown after fall detection; user can say "help" or "okay" to escalate or cancel.
  2. **Service Selection** -- Choose Fire, Ambulance, or Police with large emoji buttons or voice.
  3. **Context Selection** -- Describe what's happening (e.g. Fall, Chest Pain, Bleeding, My Unit on Fire).
  4. **Location Confirmation** -- GPS auto-resolve with reverse geocoding; confirm before dispatch.
  5. **Dispatch** -- Sends formatted SMS to SCDF 70995 and caregiver/secondary contacts with Google Maps link.
- **User Onboarding** -- Multi-step wizard collects name, address, caregiver contacts, medical info, and communication preference (touch/voice/both).
- **Settings** -- Edit profile data at any time.
- **Boot Startup** -- Fall detection service auto-starts on device boot.
- **OEM Battery Guidance** -- Logs manufacturer-specific instructions for disabling aggressive background killers (Samsung, Xiaomi, Huawei, OPPO, OnePlus, Vivo).

## Project Structure

```
app/src/main/java/com/example/emergencyresponse/
  model/                         # Data classes, enums, configuration
    AppConfig.kt                 # Feature flags, defaults, SCDF number
    EmergencyUiState.kt          # 6-state enum (IDLE -> DISPATCH_ACTIVE)
    EmergencyUiModel.kt          # Immutable UI render snapshot
    EmergencyEvent.kt            # Accumulated dispatch payload
    UserProfile.kt               # User profile data class
    UserProfileRepository.kt     # SharedPreferences-backed persistence
    ServiceConfig.kt             # Emergency services registry (Fire/Ambulance/Police + contexts)

  service/                       # Background services
    FallDetectionService.kt      # Foreground service with accelerometer fall + tremor detection
    BootReceiver.kt              # Starts FallDetectionService on device boot

  util/                          # Utility handlers
    DispatchBridge.kt            # SMS formatting and dispatch (SCDF + caregiver)
    LocationHandler.kt           # GPS location + reverse geocoding
    InteractionManager.kt        # TTS, STT voice recognition, haptic feedback

  ui/                            # Screens and ViewModel
    MainActivity.kt              # Main emergency flow UI
    OnboardingActivity.kt        # First-launch profile setup wizard
    SettingsActivity.kt          # Edit profile screen
    EmergencyViewModel.kt        # Centralized state machine and event holder

app/src/main/res/
  layout/
    activity_main.xml            # Main flow layout with ViewSwitcher
    activity_onboarding.xml      # 5-step onboarding wizard layout
    activity_settings.xml        # Settings form layout
  values/
    colors.xml                   # Material 3 color palette
    themes.xml                   # App theme (Theme.EmergencyResponse)
  drawable/
    bg_status_active.xml         # Monitoring status indicator
```

## Architecture

### State Machine

The app uses a centralized 6-state enum driven by `EmergencyViewModel`:

```
IDLE -> DROP_COUNTDOWN -> SERVICE_SELECTION -> CONTEXT_SELECTION -> LOCATION_CONFIRM -> DISPATCH_ACTIVE
  ^                                                                                          |
  |__________________________________________________________________________________________|
                                        (cancel from any state)
```

### Data Flow

1. **Trigger** arrives (fall detection broadcast, UI button, or volume key press).
2. `EmergencyViewModel` transitions to `DROP_COUNTDOWN`.
3. User responds (voice or touch) to escalate or cancel.
4. Service type, context, and location are gathered step by step.
5. `DispatchBridge` formats and sends SMS to SCDF and caregiver contacts.

### Key Design Decisions

- **MVVM** -- `EmergencyViewModel` owns all state; Activities observe via `StateFlow`.
- **Foreground Service** -- `FallDetectionService` runs with `foregroundServiceType="health"` for reliable background sensor monitoring.
- **TTS-before-STT** -- Speech recognition starts only after all TTS utterances finish, preventing microphone echo.
- **Mock Dispatch** -- SMS to SCDF is mocked by default; caregiver SMS can be tested independently.

## How to Run

### Prerequisites

- **Android Studio** (latest stable, e.g. Ladybug or newer)
- **JDK 17+** (bundled with Android Studio)
- **Android SDK 34** (compileSdk/targetSdk)
- **Physical Android device** or emulator with API 26+ (minSdk 26)
  - A physical device is strongly recommended for testing fall detection sensors, SMS, and voice recognition.

### Steps

1. **Clone the repository**:
   ```bash
   git clone https://github.com/<your-org>/beyond-binary-hack.git
   cd beyond-binary-hack
   ```

2. **Open in Android Studio**:
   - File > Open > select the `beyond-binary-hack` root folder.
   - Wait for Gradle sync to complete.

3. **Connect a device or start an emulator**:
   - Enable USB debugging on your physical device, or
   - Create an AVD with API 26+ in Device Manager.

4. **Build and run**:
   - Select the `app` run configuration.
   - Click Run (green play button) or press `Shift+F10`.
   - The app will install and launch on your device.

5. **Grant permissions** when prompted:
   - Location (Fine)
   - SMS
   - Activity Recognition
   - Microphone (for voice commands)
   - Notifications (Android 13+)

6. **Complete onboarding** on first launch:
   - Enter your name, address, caregiver number, and communication preference.

7. **Test the emergency flow**:
   - Tap the large "Emergency Help" button on the main screen, or
   - Press a volume key, or
   - Simulate a fall broadcast via ADB:
     ```bash
     adb shell am broadcast -a com.example.emergencyresponse.ACTION_FALL_DETECTED --es trigger_type fall -p com.example.emergencyresponse
     ```

### Dispatch Mode Configuration

In `AppConfig.kt` (`model/AppConfig.kt`):

| Flag | Default | Effect |
|---|---|---|
| `MOCK_DISPATCH_MODE` | `true` | When `true`, SCDF SMS is **not** sent (logged + shown in dialog). |
| `TEST_CAREGIVER_SMS` | `true` | When `true` (and mock mode on), sends **real** SMS to caregiver/secondary contact only. |

To go **fully live** (production): set `MOCK_DISPATCH_MODE = false`.
To go **fully mock** (no SMS at all): set both to `true` / `false` respectively, or set `TEST_CAREGIVER_SMS = false`.

### Troubleshooting

- **R.jar file lock (Windows/OneDrive)**: If Gradle fails with an `IOException` on `R.jar`, close Android Studio, delete the `app/build` folder, and reopen. This is a known OneDrive file-locking issue.
- **Battery optimization**: The app prompts for battery optimization exemption on first launch. On OEM devices (Samsung, Xiaomi, etc.), you may also need to manually whitelist the app in the manufacturer's battery settings. Check Logcat for `OEM background restriction guidance` messages.
- **Voice commands not working**: Ensure microphone permission is granted and communication mode is set to "Voice" or "Both" in onboarding/settings.
- **Location not resolving**: Ensure device GPS is enabled. The app will prompt you to open location settings if GPS is off.

## Permissions

| Permission | Purpose |
|---|---|
| `ACCESS_FINE_LOCATION` | GPS for emergency location dispatch |
| `SEND_SMS` | Send emergency SMS to SCDF and contacts |
| `RECORD_AUDIO` | Voice command recognition (STT) |
| `CAMERA` | Reserved for future thumbs-up gesture detection |
| `FOREGROUND_SERVICE` | Background fall/tremor monitoring |
| `FOREGROUND_SERVICE_HEALTH` | Health-type foreground service (API 34+) |
| `ACTIVITY_RECOGNITION` | Required for health foreground service type |
| `VIBRATE` | Haptic feedback patterns per state |
| `WAKE_LOCK` | Keep CPU awake during emergency trigger |
| `RECEIVE_BOOT_COMPLETED` | Auto-start fall detection on boot |
| `POST_NOTIFICATIONS` | Notification channel alerts (API 33+) |
| `USE_FULL_SCREEN_INTENT` | Full-screen emergency alert on lock screen |

## Accessibility

The app is designed with accessibility as a core principle, not an afterthought:

### Multimodal Interaction (5 modalities)

| Modality | Implementation |
|---|---|
| **Accelerometer (sensor)** | Fall detection + tremor detection -- no user action needed |
| **Touch (motor)** | 100-120dp buttons, full-width, per-state color coding |
| **Voice Input (audio)** | SpeechRecognizer keyword matching per state (e.g. "help", "fire", "confirm") |
| **Voice Output (audio)** | TextToSpeech reads every state change and all available options aloud |
| **Haptics (tactile)** | Distinct VibrationEffect patterns per state (rapid pulse for countdown, single shot for selection, etc.) |

### Screen Reader Support (TalkBack)

- All interactive elements have `contentDescription` attributes in XML layouts.
- Dynamic buttons update `contentDescription` programmatically in `renderState()` as their labels change per state (e.g. "I need help. Double tap to request emergency services now." during countdown).
- Decorative elements (emoji icons, status indicators) use `importantForAccessibility="no"` to avoid cluttering the screen reader.

### Adaptive Communication

Users choose their preferred interaction mode during onboarding:
- **Touch** -- buttons only, no voice activation
- **Voice** -- full STT keyword recognition + TTS announcements
- **Both** -- all modalities active simultaneously

The app respects this preference at runtime; STT is only activated when the user's profile includes voice mode.

### Motor Accessibility

- Minimum button height: 100dp (primary), 88dp (secondary), 72dp (cancel) -- well above the 48dp WCAG minimum
- Full-width buttons (`match_parent`) -- no precision targeting required
- Volume keys as alternative emergency trigger -- no touch required at all
- Auto-escalation: if the user does not respond during countdown, emergency services are contacted automatically

## Tech Stack

- **Language**: Kotlin
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)
- **UI**: Material Design 3 (Material Components)
- **Architecture**: MVVM with StateFlow
- **Location**: Google Play Services FusedLocationProviderClient
- **Voice**: Android platform SpeechRecognizer + TextToSpeech
- **SMS**: Android SmsManager
- **Storage**: SharedPreferences

## SMS Format (SCDF Guidelines)

The app formats SMS per [SCDF Emergency SMS guidelines](https://www.scdf.gov.sg/home/about-scdf/emergency-medical-services):

- **Fire**: `Fire Engine. Blk 889 Tampines St 81, #05-123. {Name}'s unit is on fire.`
- **Ambulance**: `Ambulance. Blk 889 Tampines St 81, #05-123. {Name} has fallen and needs help.`

Caregiver SMS includes the SCDF message plus a Google Maps location link.
