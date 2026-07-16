## New Feature: Previous Run Log Persistence

This release adds disk persistence to the log buffer so logs survive app crashes.

### What's New

**Log Persistence & Crash Recovery**
- Log buffer now writes logs to disk (`session.log`) as they happen
- On app startup, current session logs are rotated to `previous_run.log`
- Diagnostic reports now include previous run logs if available
- When the app crashes and restarts, you can now see what happened right before the crash

**How It Works**
- Every log entry is appended to disk immediately (no loss between flushes)
- `LogBuffer.init(context)` initializes persistence in `IoniqApplication.onCreate()`
- `SupportEmailCollector` includes a new "PREVIOUS RUN LOGS" section in diagnostics
- Graceful handling of missing previous logs (first run or clean install)

### Files Changed
- `LogBuffer.kt` — added disk persistence with session rotation
- `IoniqApplication.kt` — calls `LogBuffer.init()` on startup
- `SupportEmailCollector.kt` — includes previous run logs in diagnostics
- `build.gradle.kts` — bumped to 1.0.0-beta.6 (versionCode 6)

### Downloads
Install the APK on your Android device. Make sure to enable "Install from unknown sources" if not already enabled.
