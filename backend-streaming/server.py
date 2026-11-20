"""SpeakSense AI streaming proxy: Android (WebSocket, PCM16 16 kHz) -> Google Cloud Speech-to-Text v2 (te-IN).

    pip install -r requirements.txt
    set SPEAKSENSE_TOKEN=a-long-random-string        (PowerShell: $env:SPEAKSENSE_TOKEN="...")
    set GOOGLE_CLOUD_PROJECT=your-project-id
    uvicorn server:app --host 0.0.0.0 --port 8000

Protocol (WebSocket /stream, header "Authorization: Bearer <token>"):
  client -> server  text   {"type":"config","language":"te-IN","alternateLanguages":["en-IN"],"sampleRate":16000,"interimResults":true}
  server -> client  text   {"type":"ready"}
  client -> server  binary PCM16 little-endian mono frames (any size up to 64 KB), sent continuously
  server -> client  text   {"type":"partial"|"final","text":"..."} / {"type":"status","message":"..."} / {"type":"error",...}
Audio and transcripts are never written to disk or logged (set LOG_TRANSCRIPTS=1 to log text while debugging).
"""
from __future__ import annotations

import asyncio
import hmac
import json
import logging
import os

from fastapi import FastAPI, WebSocket, WebSocketDisconnect

from stt_session import SttSession

TOKEN = os.environ.get("SPEAKSENSE_TOKEN", "")
ALLOW_NO_TOKEN = os.environ.get("ALLOW_NO_TOKEN", "0") == "1"
MAX_SESSIONS = int(os.environ.get("MAX_SESSIONS", "3"))
LOG_TRANSCRIPTS = os.environ.get("LOG_TRANSCRIPTS", "0") == "1"
ALLOWED_LANGUAGES = {"te-IN", "en-IN", "en-US", "hi-IN"}

if not TOKEN and not ALLOW_NO_TOKEN:
    raise RuntimeError("Set SPEAKSENSE_TOKEN (recommended) or ALLOW_NO_TOKEN=1 for a throw-away local test.")

log = logging.getLogger("speaksense")
logging.basicConfig(level=logging.INFO)
app = FastAPI()
_active = 0
_backend = None


def backend():
    global _backend
    if _backend is None:
        from google_backend import GoogleV2Backend

        _backend = GoogleV2Backend()
    return _backend


@app.get("/healthz")
def healthz():
    return {"ok": True, "active_sessions": _active}


@app.websocket("/stream")
async def stream(ws: WebSocket):
    global _active
    if TOKEN:
        supplied = ws.headers.get("authorization", "")
        if not hmac.compare_digest(supplied.encode(), f"Bearer {TOKEN}".encode()):
            await ws.close(code=1008)  # rejected before accept -> HTTP 403 to the client
            return
    if _active >= MAX_SESSIONS:
        await ws.close(code=1013)
        return
    await ws.accept()
    _active += 1
    session = None
    try:
        try:
            config = json.loads(await asyncio.wait_for(ws.receive_text(), timeout=5))
        except Exception:
            await ws.send_json({"type": "error", "message": "Expected a config message first.", "fatal": True})
            return
        if config.get("type") != "config" or config.get("language") not in ALLOWED_LANGUAGES \
                or int(config.get("sampleRate", 0)) != 16000 or config.get("encoding") != "LINEAR16":
            await ws.send_json({"type": "error", "message": "Unsupported config (need LINEAR16, 16000 Hz, known language).", "fatal": True})
            return
        try:
            session = SttSession(backend(), asyncio.get_running_loop(), config)
        except Exception as e:  # for example missing GOOGLE_CLOUD_PROJECT or credentials
            await ws.send_json({"type": "error", "message": f"Server speech setup failed: {e}", "fatal": True})
            return
        session.start()
        await ws.send_json({"type": "ready"})

        async def pump_out():
            async for event in session.events():
                if LOG_TRANSCRIPTS and event.get("text"):
                    log.info("%s: %s", event["type"], event["text"])
                await ws.send_json(event)
                if event.get("fatal"):
                    await ws.close(code=1011)
                    return

        out_task = asyncio.create_task(pump_out())
        try:
            while True:
                msg = await ws.receive()
                if msg["type"] == "websocket.disconnect":
                    break
                data = msg.get("bytes")
                if data:
                    if len(data) <= 65_536:
                        session.feed(data)
                elif msg.get("text") == "stop":
                    break
                if out_task.done():
                    break
        finally:
            session.close()
            out_task.cancel()
    except WebSocketDisconnect:
        pass
    finally:
        if session:
            session.close()
        _active -= 1
