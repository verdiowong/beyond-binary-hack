# Beyond Binary Hack - Native Android Emergency Skeleton

This repository now contains a native Android Kotlin skeleton for an emergency response app designed for motor-impaired users.

This documentation is focused on the native Android implementation under `app/src/main/` and intentionally ignores `frontend/sos-assist` for now.

Run/setup guide: `docs/run-native-android.md`

## Project Goal

Build a state-driven emergency workflow with a centralized `EmergencyViewModel` that coordinates UI, sensors, voice/vision, location, and dispatch.

## Current Native Android Scope

Implemented skeleton files:

- `app/src/main/java/com/example/emergencyresponse/MainActivity.kt`
- `app/src/main/java/com/example/emergencyresponse/FallDetectionService.kt`
- `app/src/main/java/com/example/emergencyresponse/InteractionManager.kt`
- `app/src/main/java/com/example/emergencyresponse/LocationHandler.kt`
- `app/src/main/java/com/example/emergencyresponse/DispatchBridge.kt`
- `app/src/main/res/layout/activity_main.xml`

## Architecture Overview

### Core state machine

The app uses one shared enum:

- `EmergencyUiState.IDLE`
- `EmergencyUiState.DROP_COUNTDOWN`
- `EmergencyUiState.SERVICE_SELECTION`
- `EmergencyUiState.CONTEXT_SELECTION`
- `EmergencyUiState.LOCATION_CONFIRM`
- `EmergencyUiState.DISPATCH_ACTIVE`

### Shared app contracts

Defined in `MainActivity.kt`:

- `EmergencyEvent` (cross-module event payload)
- `EmergencyUiModel` (screen state model)
- `EmergencyViewModel` (single source of truth for transitions and state)

All modules should communicate through the ViewModel and `EmergencyEvent` semantics.

### Data/control flow

1. Trigger arrives (UI button, volume key, or fall detection).
2. `EmergencyViewModel` transitions to `DROP_COUNTDOWN`.
3. Countdown ends or user actions continue flow.
4. Service/context/location are gathered.
5. Dispatch module formats and sends SMS.

## Team Ownership and TODO Map

### Member 1 - Frontend & UI Logic

Files:

- `MainActivity.kt`
- `activity_main.xml`

Responsibilities:

- Keep UI state rendering aligned to `EmergencyUiState`.
- Maintain accessibility-first controls (large 100dp+ buttons).
- Finalize countdown and input trigger behavior.

Key TODO markers:

- Implement robust 10-second countdown behavior.
- Harden `onKeyDown` handling for volume-based emergency triggers.

### Member 2 - Sensor Backend

File:

- `FallDetectionService.kt`

Responsibilities:

- Process accelerometer data in background service.
- Detect free-fall + impact sequence reliably.
- Trigger emergency flow via broadcast/event.

Key TODO markers:

- Improve high-pass filtering and threshold strategy.
- Replace placeholder trigger logic with multi-step fall detection.

### Member 3 - Voice/Vision Backend

File:

- `InteractionManager.kt`

Responsibilities:

- TTS announcements for state changes.
- STT keyword recognition (`Fire`, `Ambulance`, `Police`, `Yes`, `No`).
- Camera motion fallback as "I am okay" signal.

Key TODO markers:

- Integrate `SpeechRecognizer` flow and callbacks.
- Add CameraX frame processing placeholder pipeline.

### Member 4 - Geo/Data Backend

File:

- `LocationHandler.kt`

Responsibilities:

- High-accuracy location retrieval via `FusedLocationProviderClient`.
- Singapore-focused reverse geocoding.
- User profile persistence (unit number + medical ID).

Key TODO markers:

- Refine address formatting for Singapore landmarks/blocks.
- Decide SharedPrefs vs Room for persistent profile.

### Member 5 - Integration & SMS

File:

- `DispatchBridge.kt`

Responsibilities:

- Build SCDF `70995` message formatter.
- Dispatch formatted SMS to SCDF and caregiver.
- Improve delivery/error handling.

Key TODO markers:

- Finalize formatter: `"[Service]. [Address], #[Unit]. [Context]."`
- Add robust result reporting and retries where needed.

## Local Setup (Native Android)

Current repo has source skeleton only. To run, place this code inside a full Android project module.

### Minimum expected Android stack

- Android Studio (latest stable)
- Kotlin (latest stable plugin)
- minSdk around 26+ recommended
- Target package: `com.example.emergencyresponse`

### Required dependencies

Add to your app module as needed:

- AndroidX lifecycle + ViewModel + coroutines
- Google Play Services Location (`play-services-location`)
- CameraX (for motion placeholder work)
- Speech/voice APIs (platform `SpeechRecognizer`, `TextToSpeech`)

### Required manifest permissions

Declare and request runtime permissions:

- `android.permission.ACCESS_FINE_LOCATION`
- `android.permission.SEND_SMS`
- `android.permission.RECORD_AUDIO`
- `android.permission.CAMERA`

Optional depending on implementation details:

- `android.permission.BODY_SENSORS`
- `android.permission.FOREGROUND_SERVICE`

Also register `FallDetectionService` in `AndroidManifest.xml`.

## Integration Contract Between Modules

Use this contract to avoid coupling:

- UI updates and state transitions must be initiated via `EmergencyViewModel`.
- Module outputs should map to `EmergencyEvent` fields:
  - `source`
  - `state`
  - `service`
  - `context`
  - `location`
- Modules should not directly mutate each other's internals.

## Recommended Branch/PR Workflow

1. Each member works in feature branch by ownership area.
2. Keep PRs small and tied to one module boundary.
3. Add at least one test/demo scenario per PR.
4. Confirm state transitions still respect the 6-state flow.

## Manual Test Checklist

### Core flow

- From `IDLE`, trigger via large button.
- Confirm `DROP_COUNTDOWN` appears.
- Confirm countdown expiry moves to `SERVICE_SELECTION`.
- Select service -> context -> location confirm -> dispatch active.

### Alternative triggers

- Trigger from volume up/down key.
- Trigger from fall-detection broadcast:
  - `adb shell am broadcast -a com.example.emergencyresponse.ACTION_FALL_DETECTED`

### Module sanity checks

- TTS announces every state transition.
- Location resolves and displays a usable address.
- Dispatch builds correctly formatted message.
- SMS attempts target SCDF and caregiver destination numbers.

## Known Skeleton Limitations

- This is a scaffold, not production-ready fall detection.
- STT and camera motion logic are placeholders.
- SMS/location permissions and edge cases still need full handling.
- Manifest/Gradle project shell may need completion depending on host project.

## Next Implementation Milestones

1. Extract `EmergencyViewModel`, `EmergencyEvent`, and state enum into dedicated files for cleaner module sharing.
2. Add unit tests for state transitions and message formatting.
3. Add instrumentation tests for lifecycle and permission flows.
4. Introduce dependency injection (for testability and clear boundaries).