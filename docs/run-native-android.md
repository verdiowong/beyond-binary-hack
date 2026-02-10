# Run Native Android Skeleton

This guide explains how to run and test the native Kotlin emergency app skeleton in this repository.

It is focused on:

- `app/src/main/java/com/example/emergencyresponse/`
- `app/src/main/res/layout/activity_main.xml`

It intentionally ignores `frontend/sos-assist`.

## Prerequisites

- Android Studio (latest stable)
- Android SDK (installed via Android Studio)
- A device or emulator with Android 8.0+ recommended
- JDK 17 (usually bundled with Android Studio)

## Important Current State

This repo currently contains Android source files, but not a full Gradle Android app shell.

That means you should either:

- Create/open an Android Studio project and copy these files in, or
- Add missing Gradle/manifest wrapper files directly in this repo before CLI build.

## Option A (Recommended): Run with Android Studio

### 1) Create a native project

In Android Studio:

1. `File` -> `New` -> `New Project`
2. Choose `Empty Views Activity`
3. Set:
   - Name: `EmergencyResponse`
   - Package: `com.example.emergencyresponse`
   - Language: `Kotlin`
   - Minimum SDK: API 26 or above

### 2) Copy skeleton files

Replace generated files with:

- `MainActivity.kt`
- `FallDetectionService.kt`
- `InteractionManager.kt`
- `LocationHandler.kt`
- `DispatchBridge.kt`
- `activity_main.xml`

Place them at:

- `app/src/main/java/com/example/emergencyresponse/`
- `app/src/main/res/layout/`

### 3) Add dependency

Add Google location dependency in app module `build.gradle(.kts)`:

```kotlin
dependencies {
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
```

If your template is missing lifecycle/coroutines, add:

```kotlin
dependencies {
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
```

### 4) Update manifest

In `app/src/main/AndroidManifest.xml`, ensure:

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

And register the service:

```xml
<application ...>
    <service
        android:name=".FallDetectionService"
        android:exported="false" />
</application>
```

### 5) Run

- Connect a physical Android device or start emulator.
- Click `Run` in Android Studio.
- Accept runtime permissions when prompted.

## Option B: Build from command line (after project shell exists)

From repo root:

```bash
./gradlew assembleDebug
./gradlew installDebug
```

On Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

## Functional Test Checklist

### Basic flow

1. App opens in `IDLE`.
2. Tap big trigger button -> state changes to `DROP_COUNTDOWN`.
3. Let countdown finish -> goes to `SERVICE_SELECTION`.
4. Continue through context and location confirmation.
5. Confirm dispatch -> `DISPATCH_ACTIVE`.

### Trigger tests

- Press hardware volume up/down -> should trigger emergency flow.
- Simulate fall detection broadcast:

```bash
adb shell am broadcast -a com.example.emergencyresponse.ACTION_FALL_DETECTED
```

### Module checks

- TTS announces state transitions.
- Location text updates when GPS resolves.
- SMS formatter produces SCDF-compatible message shape.
- Dispatch attempts SMS to SCDF `70995` and caregiver number.

## Common Issues

- **Build errors for Google location classes**: missing `play-services-location`.
- **Runtime crash for permissions**: permission not declared or not granted.
- **No SMS sent**: emulator limitations, missing SIM/service, or denied `SEND_SMS`.
- **Location never updates**: GPS disabled on device/emulator.

## Security and Safety Notes

- Use test phone numbers in development.
- Do not send real emergency messages during testing.
- Validate all dispatch outputs in logs before enabling live numbers.
