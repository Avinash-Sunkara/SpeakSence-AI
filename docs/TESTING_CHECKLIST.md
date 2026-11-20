# Mandatory testing checklist

Test on a real phone with a SIM. Use a second phone to call. Record the result (Pass / Fail / Notes) for each line,
then copy the summary into `DEVICE_COMPATIBILITY_REPORT.md`.

Before starting: finish the setup wizard; Dashboard shows microphone, overlay, call-state allowed.

| # | Test | Steps | Expected | Result |
|---|---|---|---|---|
| 1 | Incoming call detection | Call the phone, do not answer | Dashboard says "Incoming call ringing"; no captions yet | |
| 2 | Call answered | Answer it | Overlay appears; status moves to Listening (or Start button) | |
| 3 | Outgoing call detection | Dial the other phone | Overlay appears on dial; direction "Outgoing" | |
| 4 | Call disconnected | Hang up from either side | Overlay, notification and microphone indicator vanish within a few seconds | |
| 5 | Speakerphone audio capture | Speakerphone ON, other person speaks | Mic level non-zero; "Mode B is WORKING" after text appears | |
| 6 | Mic silent case | Speakerphone OFF | After ~25 s dashboard/overlay explains the mic looks silent | |
| 7 | English recognition | Other person says "I will call you tomorrow." | Caption in English | |
| 8 | Telugu recognition | "మీరు రేపు కాలేజీకి వస్తారా?" | Caption in Telugu script | |
| 9 | Mixed language | "రేపు morning 10 AM కి college కి రావాలి." | Best effort; record exactly what appears | |
| 10 | Floating caption visibility | Over the in-call screen | Readable, draggable, resizable via Settings | |
| 11 | Pause / resume / close | Tap each | Pause releases mic; Resume restarts; Close ends captions for this call | |
| 12 | Screen locked | Lock screen mid-call | Note behaviour (overlay may hide; service should continue) | |
| 13 | App in background | Home button during call | Captions continue | |
| 14 | Network disconnected | Airplane mode with Wi-Fi/data off (offline model) | Offline captions continue; with backend engine a "no network" status | |
| 15 | Battery saver enabled | Turn on battery saver | Warning shown; note whether captions survive | |
| 16 | App restarted | Swipe away the app during a call | Service restarts only if call still active; otherwise stops | |
| 17 | Permissions revoked | Revoke mic in App info during a call | Clear error, no crash | |
| 18 | Revoke overlay | Remove overlay permission | No crash; notification prompt path works | |
| 19 | Auto start blocked | Force-stop app, then call | Captions start after first call event or with one tap | |
| 20 | Reboot | Reboot, make a call | App wakes on call; note whether auto mic start was allowed | |
| 21 | History off | Default settings | Nothing saved | |
| 22 | History on | Enable saving, do a call | Transcript appears, searchable, deletable, clear-all works | |
| 23 | Privacy | Check `adb shell run-as com.speaksense.ai ls files` | No audio files | |

Useful commands: `adb logcat -s CaptionService SessionController CallStateMonitor AndroidSpeechEngine FloatingCaption`
