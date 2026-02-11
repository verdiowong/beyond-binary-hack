# Quick Start: Testing FallDetectionService

## 🚀 5-Minute Setup

### Step 1: Add Dependencies

Add to `app/build.gradle.kts`:

```kotlin
dependencies {
    // ... existing dependencies ...
    
    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.22")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.robolectric:robolectric:4.11.1")
    
    // Instrumented Testing
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
```

### Step 2: Sync Gradle

```bash
./gradlew sync
```

Or in Android Studio: **File → Sync Project with Gradle Files**

### Step 3: Run Tests

```bash
# Run all unit tests (fast, ~10 seconds)
./gradlew test

# Or in Android Studio:
# Right-click on test file → Run
```

## ✅ What You Just Got

### Test Files Created

```
app/src/
├── test/java/com/example/emergencyresponse/
│   ├── FallDetectionServiceTest.kt              (13 unit tests)
│   └── FallDetectionServiceIntegrationTest.kt   (9 integration tests)
└── androidTest/java/com/example/emergencyresponse/
    └── FallDetectionServiceInstrumentedTest.kt  (7 device tests)
```

### Documentation Created

```
docs/
├── testing-guide.md       (Comprehensive guide)
├── test-summary.md        (Quick reference)
└── test-quick-start.md    (This file)
```

### What's Tested

- ✅ **Fall Detection Algorithm**: Free-fall → Impact → Stillness
- ✅ **Tremor Detection**: Sustained rhythmic oscillations
- ✅ **False Positive Prevention**: Won't trigger on normal activities
- ✅ **Service Lifecycle**: Start, stop, bind, unbind
- ✅ **Sensor Management**: Registration, unregistration
- ✅ **Alert System**: Cooldown, broadcast intents

## 🎯 Try It Now

### Run Your First Test

```bash
# Run a simple unit test
./gradlew test --tests FallDetectionServiceTest."vectorMagnitude calculates correct magnitude"
```

**Expected output:**
```
✅ vectorMagnitude calculates correct magnitude PASSED
```

### Run a Fall Detection Test

```bash
# Test complete fall sequence
./gradlew test --tests FallDetectionServiceIntegrationTest."complete fall sequence triggers emergency broadcast"
```

### View Results

```bash
# Generate HTML report
./gradlew test

# Open report (location will be shown in output)
# Usually: app/build/reports/tests/testDebugUnitTest/index.html
```

## 📊 Understanding Test Results

### All Green ✅
```
BUILD SUCCESSFUL
Task :app:testDebugUnitTest
29 tests completed, 29 passed
```
**You're good to go!**

### Some Red ❌
```
29 tests completed, 27 passed, 2 failed
```
**Check the HTML report for details**

## 🐛 Common Issues & Fixes

### Issue 1: "Cannot resolve symbol 'robolectric'"

**Fix**: Sync Gradle again
```bash
./gradlew --refresh-dependencies
```

### Issue 2: "SDK not found"

**Fix**: Update test annotation
```kotlin
@Config(sdk = [28])  // or [30], [31], etc.
```

### Issue 3: Tests are slow

**Fix**: You're probably running instrumented tests. Use unit tests for speed:
```bash
./gradlew test         # Fast (10s)
# NOT: ./gradlew connectedAndroidTest  # Slow (5min)
```

### Issue 4: "Reflection error"

**Fix**: Ensure FallDetectionService code hasn't changed field names. Update tests if needed.

## 🎓 Next Steps

### For Developers

1. **Run tests before committing**:
   ```bash
   git add .
   ./gradlew test && git commit -m "Your message"
   ```

2. **Add tests for new features**:
   - Copy existing test structure
   - Follow naming: `test new feature description`
   - Use AAA pattern: Arrange, Act, Assert

3. **Check coverage**:
   ```bash
   ./gradlew jacocoTestReport
   ```

### For Team Lead

1. **Add to CI/CD**: See `testing-guide.md` for GitHub Actions example

2. **Set coverage requirements**: e.g., "All PRs must maintain >80% coverage"

3. **Review test report in PR reviews**: Check HTML report for new code coverage

### For QA Team

1. **Run instrumented tests** (on real device):
   ```bash
   # Connect Android device
   adb devices
   
   # Run tests
   ./gradlew connectedAndroidTest
   ```

2. **Manual test checklist**: See `README.md` "Manual Test Checklist"

3. **Report bugs**: Include test case that reproduces the bug

## 📚 Learn More

- **Full testing guide**: `docs/testing-guide.md`
- **Test reference**: `docs/test-summary.md`
- **Project overview**: `README.md`

## 💡 Tips

### Write Good Tests

```kotlin
// ✅ Good: Descriptive name
@Test
fun `fall detection requires all three phases`() { ... }

// ❌ Bad: Vague name
@Test
fun testFall() { ... }
```

### Test One Thing

```kotlin
// ✅ Good: Single assertion
@Test
fun `vectorMagnitude calculates correct magnitude`() {
    val result = service.vectorMagnitude(3f, 4f, 0f)
    assertEquals(5f, result, 0.001f)
}

// ❌ Bad: Multiple unrelated assertions
@Test
fun testEverything() {
    // tests magnitude, fall detection, tremor, lifecycle...
}
```

### Use Clear Arrange-Act-Assert

```kotlin
@Test
fun `service starts successfully`() {
    // Arrange
    val intent = Intent(context, FallDetectionService::class.java)
    
    // Act
    val result = service.onStartCommand(intent, 0, 1)
    
    // Assert
    assertEquals(START_STICKY, result)
}
```

## ⚡ Performance Tips

- Run unit tests during development (fast)
- Run integration tests before commits (medium)
- Run instrumented tests in CI only (slow)
- Use `--tests` flag to run specific tests

## 🎉 You're Ready!

Run this command to verify everything works:

```bash
./gradlew test
```

If you see **BUILD SUCCESSFUL** and **29 tests passed**, you're all set! 🎊

## Need Help?

- Check `docs/testing-guide.md` for detailed explanations
- Review existing tests for examples
- Ask team members familiar with Android testing

---

**Happy Testing! 🧪**
