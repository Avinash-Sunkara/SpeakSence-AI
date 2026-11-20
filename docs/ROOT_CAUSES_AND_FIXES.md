# Root causes and fixes (v1.1)

Found by reading the source. **None of this has been run on a device or compiled in the environment that made
the changes** (no Android SDK there); see `TEST_REPORT.md`.

## Why Telugu captions did not appear

| # | Cause (file) | Effect | Fix |
|---|---|---|---|
| 1 | `AppSettings.allowOnlineRecognition = false` and `AndroidSpeechEngine` created the **on-device** recogniser first, otherwise refused to run | Phones without an on-device Telugu pack (typical) ended in a fatal "no recogniser" error: **no captions at all** | Online allowed by default; online recogniser tried first; `EXTRA_PREFER_OFFLINE` only when the user turned online off |
| 2 | `EXTRA_PREFER_OFFLINE = true` always | Even with online allowed, Telugu was pushed to a missing/weak offline model | Conditional (above) |
| 3 | `SpeechRecognizer` is single-utterance: stop, wait 120 ms+, restart after **every** sentence | Words spoken in the gap are lost, fast speech truncated, no continuous session | New `StreamingSpeechEngine`: one long WebSocket, continuous audio, server-side end-pointing |
| 4 | Backend engine sent whole phrases as HTTP POST after 700 ms of silence, **min 300 ms speech**, 8 s max, energy threshold ≥350 RMS | Latency of seconds, no partial captions, short words ("అవును") and quiet far-field speakerphone speech dropped | Streaming engine sends **every** frame (silence included), no client VAD; legacy segmenter now frame-size aware with 160 ms minimum |
| 5 | Capture used one source (`VOICE_RECOGNITION`) and never checked whether audio was real | If Android silenced the mic during the call the UI still said "listening" | Source fallback (`VOICE_RECOGNITION` → `MIC` → `UNPROCESSED`), `setPrivacySensitive(false)`, built-in mic preferred, digital-silence detector that raises an explicit "audio blocked" state |
| 6 | 100 ms frames, 1 s buffer | Added latency and overrun risk | 40 ms frames, ≥1 s ring buffer |

## Android platform facts that bound the result
* Normal apps cannot capture the other party's cellular audio (`VOICE_CALL`/`DOWNLINK` are privileged). The app does
  not try. The only route is the **microphone with speakerphone on**; it hears the caller plus you.
* Whether the microphone delivers real audio during a call is decided by Android and the phone maker. Some phones
  return silence. The app now **detects** this (3 s of digital silence on every source) and tells the user.
* A foreground service with the microphone type may only start while the app is "in use" (e.g. visible overlay); the
  existing overlay-first flow is kept. Android 14+ rules are unchanged and not worked around.
* Public APIs cannot tell "dialling" from "answered" for outgoing calls; captions start when the call goes off-hook.
* No audio focus is requested and the audio mode is never changed: either could disturb the call itself.

## Provider choice
Google Cloud Speech-to-Text v2 `StreamingRecognize`, model `chirp_3`, `te-IN` (listed **Preview** in Google's docs
when checked, with interim results requested). Chirp 2 streaming has a limited language list that must be checked
per region. Verify Telugu streaming and interim output on your project before relying on it (`backend-streaming/README.md`).
Not evaluated in practice: other providers (no ability to test any here).

## Security
No Google key in the APK. The proxy holds credentials; the app holds only a user-entered bearer token (AES-GCM via
Android Keystore, existing `CryptoManager`). Release builds allow `wss://` only; the debug build alone permits `ws://` for LAN testing.
