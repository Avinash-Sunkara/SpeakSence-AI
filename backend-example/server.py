"""Reference backend for SpeakSense AI (optional). Run behind HTTPS (e.g. a reverse proxy or tunnel).

    pip install -r requirements.txt
    SPEAKSENSE_TOKEN=change-me uvicorn server:app --host 0.0.0.0 --port 8000

Contract: POST audio/wav -> {"text": "..."}. Header X-Language-Hint: te | en | auto.
Audio is processed in memory and never written to disk.
"""
import io
import os

from fastapi import FastAPI, Header, HTTPException, Request
from faster_whisper import WhisperModel

TOKEN = os.environ.get("SPEAKSENSE_TOKEN", "")
MODEL_NAME = os.environ.get("WHISPER_MODEL", "small")
model = WhisperModel(MODEL_NAME, device="cpu", compute_type="int8")
app = FastAPI()


@app.post("/")
async def transcribe(
    request: Request,
    authorization: str = Header(default=""),
    x_language_hint: str = Header(default="auto"),
):
    if TOKEN and authorization != f"Bearer {TOKEN}":
        raise HTTPException(status_code=401, detail="unauthorised")
    wav = await request.body()
    language = None if x_language_hint == "auto" else x_language_hint
    segments, _info = model.transcribe(io.BytesIO(wav), language=language, vad_filter=True)
    return {"text": " ".join(s.text.strip() for s in segments).strip()}
