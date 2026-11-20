# SpeakSense streaming proxy (Google Cloud Speech-to-Text, te-IN)

The Android app never holds a Google key. It streams microphone audio over a WebSocket to this small server, which
holds the credentials and streams to Google Speech-to-Text **v2** (`StreamingRecognize`, interim results on).

## 1. Google Cloud setup (once)
1. Create/choose a Google Cloud project and **enable the Cloud Speech-to-Text API**. Billing must be enabled on the
   project; check Google's current pricing page for the free allowance and per-minute cost before long tests.
2. Default model is `chirp_3` with `te-IN`. Per Google's docs Chirp 3 supports `StreamingRecognize` and lists
   `te-IN` as **Preview**; regional availability changes, so confirm your region with Google's *locations* API
   (`docs.cloud.google.com/speech-to-text/docs/locations`). `us` / `eu` multi-regions were listed as GA. Change with
   `STT_LOCATION` and `STT_MODEL`.
3. Credentials (server only): `gcloud auth application-default login` for local testing, or a service-account JSON
   via `GOOGLE_APPLICATION_CREDENTIALS`. Give that account only the *Cloud Speech Client* role.

## 2. Run on Windows 11 (PowerShell)
```powershell
cd backend-streaming
py -m venv .venv; .\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
$env:SPEAKSENSE_TOKEN = "put-a-long-random-string-here"
$env:GOOGLE_CLOUD_PROJECT = "your-project-id"
uvicorn server:app --host 0.0.0.0 --port 8000
```
Health check: `http://localhost:8000/healthz`.

## 3. Let the phone reach it
* **Debug APK on the same Wi-Fi (quickest):** use `ws://<laptop-LAN-IP>:8000/stream`. Only the *debug* build allows
  plain `ws://`; allow Python through the Windows firewall for private networks.
* **Anywhere / release build:** the phone needs `wss://`. A free tunnel works (for example `cloudflared tunnel --url
  http://localhost:8000` or ngrok); use the `wss://<tunnel-host>/stream` address it gives you.

In the app: Settings > Recognition engine > *Google Cloud streaming*, paste the address and the same token.

## 4. Protocol (for reference)
See the docstring at the top of `server.py`. Audio: PCM16 LE, 16 kHz, mono, sent continuously (silence included).

## 5. Tests
`python -m unittest discover -s tests` (stdlib only; uses a fake recogniser to check partial/final ordering,
reconnect-with-replay, 5-minute stream rotation, fatal-error handling, queue policy).
**These do not call Google.** The Google adapter (`google_backend.py`) is only syntax-checked until you run it
with real credentials.

## Known unknowns to check on first run
* Whether `chirp_3` returns *interim* results for `te-IN` in your region (the code requests them; if you only get
  finals, try another region/model via `STT_MODEL`).
* Whether Telugu speech with English words comes out in Telugu script, Latin script, or mixed. Multi-language
  hints are off by default (`STT_USE_ALTERNATE_LANGUAGES=1` sends `en-IN` as well; verify Google accepts that
  combination for your model/region).
