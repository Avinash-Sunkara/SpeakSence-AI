# Technical feasibility (Phase 1)

## Possible with ordinary third-party APIs
- Call detection (incoming/outgoing/active/ended) via `READ_PHONE_STATE` + `TelephonyCallback`.
- Floating overlay captions (`SYSTEM_ALERT_WINDOW`).
- Microphone capture while a foreground service is allowed to use the microphone.
- On-device speech recognition through `SpeechRecognizer` where the phone has the models.
- Encrypted local storage, privacy controls, setup guidance.

## Needs privileged / system access (NOT possible, and not attempted)
- Capturing the remote caller's audio directly (`VOICE_CALL`, `VOICE_DOWNLINK`, `CAPTURE_AUDIO_OUTPUT`).
- Reading Android's private Live Caption output.
- Starting the microphone from the background after boot with no user-visible trigger.
- Separating the caller's voice from the user's voice on a single microphone.

## Device-dependent
- Whether the microphone returns real audio during a call (varies by maker/model).
- Whether Telugu offline models exist; whether language detection/switching exists (Android 13/14+, some recognisers).
- Whether Android allows an automatic microphone start (foreground-service rules, Android 14/15).

## Closest functional alternative
Mode B (speakerphone + microphone) with honest status, plus guidance to Android Live Caption (Mode A).
