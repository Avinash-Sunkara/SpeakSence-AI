# Device compatibility report

## Expected behaviour by Android version (design expectations, not measured results)
| Android | Call detection | Floating overlay | Auto mic start | Offline model query | Language switch |
|---|---|---|---|---|---|
| 12 (API 31) | Yes | Yes | Mic may be silenced if service started from background | No (cannot list models) | No |
| 13 (API 33) | Yes | Yes | Same as 12; needs notification permission | Yes | Detection where supported |
| 14 (API 34) | Yes | Yes | Needs while-in-use state; overlay/tap fallback | Yes | Switching where supported |
| 15 (API 35) | Yes | Yes | Stricter background rules; tap fallback likely | Yes | Same |
| Newer | Re-test | Re-test | Re-test | Re-test | Re-test |

## Measured on my device (fill in)
- Phone model / Android / skin: 
- Call detection incoming / outgoing / end: 
- Overlay over call screen: 
- Automatic microphone start (zero tap): yes / no
- Microphone during call with speakerphone: real audio / silent
- Telugu offline model installed: yes / no
- English / Telugu / mixed recognition quality: 
- Battery saver / reboot / force-stop behaviour: 
- Verdict: Mode B works / use Mode A
