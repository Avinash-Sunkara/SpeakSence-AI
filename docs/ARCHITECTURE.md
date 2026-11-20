# Architecture

Single Gradle module, strictly separated packages (`com.speaksense.ai.*`).

```
PHONE_STATE broadcast -> CallStateReceiver -> CallStateMonitor -> CallStateMachine
                                   TelephonyCallback (live) --^         |
                                                                  Listener
                                                                       v
                                                              SessionController
                                        shows FloatingCaptionManager, starts CaptionForegroundService
                                                                       v
 CaptionForegroundService  --> SpeechRecognitionManager --> AndroidSpeechEngine | RemoteSpeechEngine | WhisperCppEngine
        |                              |                         (EngineCallback, main thread)
        |                              +--> AudioCaptureManager (backend engine, mic test)
        +--> SessionStateHolder (StateFlow) --> overlay, notification, Compose UI
        +--> CaptionRepository (Room + Keystore AES-GCM) when saving is on
```

| Package | Classes |
|---|---|
| `call` | `CallStateMonitor`, `CallStateReceiver`, `CallStateMachine` (pure, tested) |
| `service` | `CaptionForegroundService`, `SessionController`, `SessionStateHolder`, `NotificationHelper`, `NetworkMonitor` |
| `overlay` | `FloatingCaptionManager` (View-based `TYPE_APPLICATION_OVERLAY`) |
| `audio` | `AudioCaptureManager` |
| `speech` | `SpeechEngine`, `AndroidSpeechEngine`, `RemoteSpeechEngine`, `WhisperCppEngine`, `SpeechRecognitionManager`, `LanguageDetectionManager`, `OfflineModelChecker`, `AudioSegmenter` |
| `data` | `SettingsRepository`, `CaptionRepository`, `CryptoManager`, `db/*` |
| `permissions`, `system` | `PermissionManager`, `BatteryOptimizationHelper`, `SystemCaptionIntegration` |
| `ui` | Compose screens + ViewModels (MVVM, StateFlow) |

## Key decisions
- **minSdk 31**: `TelephonyCallback`, on-device `SpeechRecognizer` APIs and your Android 12+ test matrix. targetSdk/compileSdk 35 (matches the pinned AGP 8.7.3).
- **Overlay first, then service**: avoids relying on a background microphone start; one tap fallback.
- **Settings in SharedPreferences**: synchronous read inside the call receiver.
- **On-device first / cloud opt-in**: privacy default.
- **Field-level encryption** instead of SQLCipher: no native dependency; search is done in memory after decrypt.
- **No boot receiver / accessibility service**: not needed, fewer permissions.

## Lifecycle handling
- Duplicate services avoided: single service class, `sessionActive` guard, one overlay singleton.
- Process death: `START_STICKY` restart checks the live call state; otherwise stops.
- Permission revoked: recogniser error -> session error message; Android restarts the process anyway.
- Network loss: `NetworkMonitor`; engines report status and retry with backoff.
- Battery saver: detected at start and on change; shown in notification and dashboard.
