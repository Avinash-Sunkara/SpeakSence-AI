# Test report - SpeakSense AI v1.1

Honest status. The changes were written in an environment with **no Android SDK, no Gradle and no network**, so the
app was **not compiled, not installed and not run on any phone**. Treat every Kotlin change as "needs a first build".

## What was actually executed
| Item | Result |
|---|---|
| `backend-streaming/tests` (5 tests: partial/final order with Telugu text, fatal error, reconnect + queued-audio replay, 5-minute rotation replay, queue drops oldest) | **Passed** (Python 3, fake recogniser) |
| `py_compile` of `server.py`, `google_backend.py`, `stt_session.py` | Passed (syntax only; FastAPI/Google libraries not installed there) |

## Verified only by reading code
Kotlin changes in `AudioCaptureManager`, `StreamingSpeechEngine`, `FinalDeduper`, `AndroidSpeechEngine`,
`SpeechRecognitionManager`, `CaptionForegroundService`, Settings UI, `AppSettings/SettingsRepository`, debug network config.
New JVM unit test `FinalDeduperTest` and the existing tests are written but **not run** (`gradlew testDebugUnitTest`).
Possible compile errors cannot be ruled out; please send me the first Gradle error output if any.

## Needs a physical device (your Vivo T3 5G, Android 16)
| Test | What to check | Pass when |
|---|---|---|
| 1 Telugu sentences | Mic test then app with speaker talking Telugu near phone | Telugu script, correct order |
| 2 Fast Telugu | Speak quickly | Partials keep updating, no missing words |
| 3 Telugu + English | "నేను College కి 10 PM కి వస్తాను" | Note script used for English words (unknown until tried) |
| 4 Incoming call | Speakerphone ON, answer | Overlay appears, real captions, or explicit "no sound reaching microphone" |
| 5 Outgoing call | Dial, other side answers | Same; note captions start at dial time (platform limit) |
| 6 Five minutes continuous | Talk or play speech 5+ min | No gap/duplicate around the ~4.5 min stream rotation (watch server log) |
| 7 Network interruption | Airplane-mode 10 s, then off | Status "waiting/reconnecting", captions resume, <8 s audio replayed once |
| 8 Call end | Hang up | Overlay, notification, mic indicator and socket all stop |
| 9 Device report | Vivo T3 5G (the brief says "IQOO Z10" in test 9 - a different phone; tell me which you have) | Record whether the mic is silent during calls, autostart/battery settings needed |
| Latency | Compare spoken word vs first partial | Target 300-700 ms is a **goal, not measured**; depends on network and Google |

## Blocked or limited by Android (cannot be fixed in the app)
* The other person's call audio is not accessible to normal apps; only the phone microphone with speakerphone is.
* The mic may be silenced during calls on some phones/Android versions; the app detects and reports it, nothing more.
* Outgoing-call "answered" moment is not exposed to apps.
* The overlay over the system dialer works only where Android/Vivo allow overlays there.
