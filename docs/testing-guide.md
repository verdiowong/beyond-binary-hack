# Testing Guide for FallDetectionService

This guide explains how to run and understand the tests for the `FallDetectionService` component.

## Overview

The FallDetectionService has three types of tests:

1. **Unit Tests** (`FallDetectionServiceTest.kt`) - Fast tests that run on JVM using Robolectric
2. **Integration Tests** (`FallDetectionServiceIntegrationTest.kt`) - End-to-end scenario tests using Robolectric
3. **Instrumented Tests** (`FallDetectionServiceInstrumentedTest.kt`) - Tests that run on real devices/emulators

## Test Coverage

### Unit Tests (FallDetectionServiceTest.kt)

Tests individual methods and components:

- ✅ Service lifecycle (onCreate, onBind, onStartCommand, onDestroy)
- ✅ Sensor registration and unregistration
- ✅ Vector magnitude calculations
- ✅ Sensor event handling for different sensor types
- ✅ Fall detection state machine (free-fall → impact → stillness)
- ✅ Tremor detection (zero-crossing analysis)
- ✅ Alert cooldown mechanism
- ✅ Tremor window pruning logic
- ✅ Fall pipeline reset functionality

### Integration Tests (FallDetectionServiceIntegrationTest.kt)

Tests complete workflows:

- ✅ Complete fall sequence (free-fall + impact + stillness → trigger)
- ✅ False positive prevention:
  - High G without free-fall (jumping, running)
  - Free-fall without impact (dropped phone caught)
  - Impact but person gets up quickly (controlled fall)
- ✅ Sustained tremor detection (rhythmic oscillations)
- ✅ Service lifecycle management
- ✅ Alert cooldown between triggers
- ✅ Broadcast intent validation

### Instrumented Tests (FallDetectionServiceInstrumentedTest.kt)

Tests on real Android:

- ✅ Service binding and unbinding
- ✅ Service start and stop
- ✅ Sensor availability verification
- ✅ Service persistence (START_STICKY behavior)
- ✅ Broadcast intent format validation

## Running Tests

### Prerequisites

Add the following to your `app/build.gradle.kts`:

```kotlin
dependencies {
    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.22")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.robolectric:robolectric:4.11.1")
    
    // Instrumented Testing
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
```

### Run Unit Tests (Fast, ~5-10 seconds)

```bash
# Run all unit tests
./gradlew test

# Run specific test class
./gradlew test --tests FallDetectionServiceTest

# Run with coverage report
./gradlew testDebugUnitTest jacocoTestReport
```

### Run Integration Tests (Medium, ~30-60 seconds)

```bash
# Run all integration tests
./gradlew test --tests FallDetectionServiceIntegrationTest

# Run specific test
./gradlew test --tests FallDetectionServiceIntegrationTest."complete fall sequence triggers emergency broadcast"
```

### Run Instrumented Tests (Slow, requires device/emulator)

```bash
# Connect device or start emulator first

# Run all instrumented tests
./gradlew connectedAndroidTest

# Run specific test class
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.emergencyresponse.FallDetectionServiceInstrumentedTest
```

### Run All Tests

```bash
# Run unit + integration tests (fast)
./gradlew test

# Run everything including instrumented (slow)
./gradlew test connectedAndroidTest
```

## Understanding the Tests

### Fall Detection Algorithm Testing

The fall detection uses a 3-stage pipeline:

**Stage 1: Free-fall Detection**
- Threshold: < 3.0 m/s² (near 0G)
- Duration: ≥ 220ms
- Test: Simulates dropping motion with low acceleration magnitude

**Stage 2: Impact Detection**
- Threshold: > 22.0 m/s² (high G spike)
- Window: Within 1500ms after free-fall
- Test: Simulates hitting the ground with sudden high acceleration

**Stage 3: Stillness Verification**
- Duration: 5000ms observation window
- Metrics:
  - Peak linear acceleration ≤ 1.8 m/s²
  - RMS linear acceleration ≤ 1.0 m/s²
- Test: Simulates lying still after impact

**Complete Fall Test**:
```kotlin
// 1. Free-fall (15 samples × 20ms = 300ms)
for (i in 0..15) {
    simulateAccelerometer(0.5f, 0.5f, 0.5f)
}

// 2. Impact (5 samples of high G)
for (i in 0..5) {
    simulateAccelerometer(15f, 15f, 15f)
}

// 3. Stillness (250 samples × 20ms = 5000ms)
for (i in 0..250) {
    simulateLinearAcceleration(0.1f, 0.1f, 0.1f)
}
// Expected: Fall detected, broadcast sent
```

### Tremor Detection Algorithm Testing

The tremor detection analyzes sustained rhythmic oscillations:

**Requirements**:
- Sliding window: 3000ms
- RMS amplitude: ≥ 1.35 m/s²
- Zero crossings: 16-80 (indicates 4-16 Hz oscillation)
- Sustained: 3 consecutive windows

**Tremor Test**:
```kotlin
// Simulate 6 Hz rhythmic tremor for ~12 seconds
for (window in 0..3) {
    for (i in 0..150) {
        val t = i * 0.02f
        val amplitude = 2f * sin(2π * 6f * t)
        simulateLinearAcceleration(amplitude, ...)
    }
}
// Expected: Tremor detected after 3 windows
```

### False Positive Prevention

The tests verify these scenarios DON'T trigger alerts:

1. **High G without free-fall** (running, jumping)
   - Has impact but no free-fall phase
   
2. **Free-fall without impact** (dropped phone caught)
   - Has free-fall but no impact spike
   
3. **Impact but person recovers** (controlled fall)
   - Has free-fall + impact but high movement (not still)

## Test Utilities

### Mock Sensor Event Creation

```kotlin
private fun createSensorEvent(type: Int, values: FloatArray): SensorEvent {
    val event = mock(SensorEvent::class.java)
    val sensor = mock(Sensor::class.java)
    
    // Set sensor type using reflection
    val typeField = Sensor::class.java.getDeclaredField("mType")
    typeField.isAccessible = true
    typeField.setInt(sensor, type)
    
    // Set event fields
    // ... (see test code for details)
    
    return event
}
```

### Time Simulation

```kotlin
val shadowClock = shadowOf(SystemClock::class.java)
shadowClock.setCurrentTimeMillis(0)
// ... simulate events ...
shadowClock.setCurrentTimeMillis(1000) // Advance 1 second
```

## Debugging Failed Tests

### Common Issues

**1. Test fails: "Fall not detected"**
- Check timing: Free-fall must be ≥220ms
- Verify impact occurs within 1500ms of free-fall
- Ensure stillness lasts full 5000ms

**2. Test fails: "False positive triggered"**
- Review sequence: Ensure test doesn't accidentally include all 3 phases
- Check thresholds: Values must cross actual thresholds

**3. Instrumented test fails: "Sensor not available"**
- Emulator issue: Some emulators don't simulate sensors
- Use real device or configure sensor playback

**4. Robolectric shadow errors**
- Update Robolectric version
- Check SDK level compatibility in @Config

### Debug Logging

Add to service for debugging:

```kotlin
private fun processFallPipeline(rawMagnitude: Float, nowMs: Long) {
    Log.d("FallDetection", "rawMag=$rawMagnitude, freeFall=${freeFallStartMs != null}")
    // ... existing code ...
}
```

Run with logcat:
```bash
./gradlew test --info
```

## Test Maintenance

### When to Update Tests

- **New thresholds**: Update mock data in tests
- **Algorithm changes**: Add new test cases for new behavior
- **New sensor types**: Add sensor type handling tests
- **Performance changes**: Verify timing assumptions still hold

### Adding New Tests

Template for new test:

```kotlin
@Test
fun `test new behavior description`() {
    // Arrange: Set up test conditions
    val shadowClock = shadowOf(SystemClock::class.java)
    shadowClock.setCurrentTimeMillis(0)
    service.onStartCommand(null, 0, 1)
    
    // Act: Perform action
    simulateCondition()
    
    // Assert: Verify expected outcome
    val result = getServiceState()
    assertEquals(expectedValue, result)
}
```

## Performance Benchmarks

Typical test execution times:

- Single unit test: 50-200ms
- Full unit test suite: 5-10 seconds
- Integration test: 2-5 seconds each
- Instrumented test: 5-30 seconds each

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Android Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
      - name: Run Unit Tests
        run: ./gradlew test
      - name: Upload Test Report
        uses: actions/upload-artifact@v3
        with:
          name: test-results
          path: app/build/reports/tests/
```

## Next Steps

1. **Add more edge cases**:
   - Multiple rapid falls
   - Combined fall + tremor
   - Sensor accuracy changes

2. **Performance tests**:
   - Battery usage monitoring
   - CPU usage profiling
   - Memory leak detection

3. **Integration with other modules**:
   - Test broadcast receiver in MainActivity
   - Verify EmergencyViewModel state transitions
   - End-to-end flow testing

## Resources

- [Android Testing Fundamentals](https://developer.android.com/training/testing/fundamentals)
- [Robolectric Documentation](http://robolectric.org/)
- [Mockito Guide](https://site.mockito.org/)
- [Sensor Testing Best Practices](https://developer.android.com/guide/topics/sensors/sensors_overview#testing)
