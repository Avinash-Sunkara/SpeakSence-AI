"""Streaming session core for SpeakSense AI (no web framework and no Google imports here, so it is unit-testable).

One SttSession = one phone call. The phone sends continuous PCM16/16 kHz/mono; this class feeds it to a
recogniser backend as ONE streaming request and turns the results into small JSON events:

    {"type": "partial", "text": "..."}   current, still-changing text (interim result)
    {"type": "final",   "text": "..."}   finished sentence
    {"type": "status",  "message": "..."}  recoverable condition (for example "reconnecting")
    {"type": "error",   "message": "...", "fatal": true}

Reliability rules implemented here:
  * Google streams last about 5 minutes. The stream is rotated at a natural boundary (right after a final) once it
    is older than ROTATE_SOFT_S, or forcibly at ROTATE_HARD_S. Audio that arrived after the last final is replayed
    into the new stream, so rotation does not lose the first words of the next sentence.
  * Transient errors (network, 5xx, quota blips) re-open the stream with exponential back-off, replaying the audio
    since the last final. Audio keeps being queued meanwhile (bounded to MAX_QUEUE_S).
  * Fatal errors (bad credentials, bad config) are reported once and the session ends.
Audio is held only in memory and is never written to disk.
"""
from __future__ import annotations

import asyncio
import collections
import queue
import threading
import time
from dataclasses import dataclass
from typing import AsyncIterator, Callable, Iterable, Iterator, Optional, Protocol

BYTES_PER_SECOND = 16_000 * 2  # PCM16 mono 16 kHz


@dataclass
class Result:
    is_final: bool
    text: str


class FatalBackendError(Exception):
    """Credentials, configuration or permission problem: retrying cannot help."""


class RecognizerBackend(Protocol):
    def run(self, config: dict, audio: Iterator[bytes]) -> Iterator[Result]:
        """Consume `audio` (ends when the generator returns) and yield results until the stream ends."""
        ...


class SttSession:
    def __init__(
        self,
        backend: RecognizerBackend,
        loop: asyncio.AbstractEventLoop,
        config: dict,
        *,
        rotate_soft_s: float = 270.0,
        rotate_hard_s: float = 290.0,
        backoff_s: tuple = (0.5, 1.0, 2.0, 4.0, 8.0),
        max_queue_s: float = 10.0,
        max_recent_s: float = 8.0,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._backend = backend
        self._loop = loop
        self._config = config
        self._soft = rotate_soft_s
        self._hard = rotate_hard_s
        self._backoff = backoff_s
        self._max_queue_bytes = int(max_queue_s * BYTES_PER_SECOND)
        self._max_recent_bytes = int(max_recent_s * BYTES_PER_SECOND)
        self._clock = clock

        self._q: "queue.Queue[bytes]" = queue.Queue()
        self._q_bytes = 0
        self._q_lock = threading.Lock()

        self._recent = bytearray()  # audio already handed to the stream (for replay)
        self._total = 0  # bytes ever handed to a stream
        self._final_mark = 0  # value of _total when the last final arrived
        self._boundary = threading.Event()  # set when a final arrived since this stream started

        self._out: "asyncio.Queue[Optional[dict]]" = asyncio.Queue()
        self._closed = threading.Event()
        self._thread = threading.Thread(target=self._worker, name="stt-worker", daemon=True)
        self.streams_opened = 0  # for tests / diagnostics

    # ---- public API (called from the event loop) --------------------------------------------
    def start(self) -> None:
        self._thread.start()

    def feed(self, pcm: bytes) -> None:
        if self._closed.is_set() or not pcm:
            return
        with self._q_lock:
            self._q.put(pcm)
            self._q_bytes += len(pcm)
            while self._q_bytes > self._max_queue_bytes:  # drop the OLDEST audio, never the newest
                try:
                    dropped = self._q.get_nowait()
                except queue.Empty:
                    break
                self._q_bytes -= len(dropped)

    async def events(self) -> AsyncIterator[dict]:
        while True:
            item = await self._out.get()
            if item is None:
                return
            yield item

    def close(self) -> None:
        self._closed.set()

    def join(self, timeout: float = 5.0) -> None:
        self._thread.join(timeout)

    # ---- worker thread ----------------------------------------------------------------------
    def _emit(self, event: Optional[dict]) -> None:
        try:
            self._loop.call_soon_threadsafe(self._out.put_nowait, event)
        except RuntimeError:
            pass  # loop already closed

    def _worker(self) -> None:
        failures = 0
        replay_since_final = False
        try:
            while not self._closed.is_set():
                try:
                    self.streams_opened += 1
                    got_result = False
                    for res in self._backend.run(self._config, self._audio(replay_since_final)):
                        got_result = True
                        failures = 0
                        self._on_result(res)
                    replay_since_final = True  # normal end = rotation: replay audio after the last final
                    if self._closed.is_set():
                        break
                except FatalBackendError as e:
                    self._emit({"type": "error", "message": str(e), "fatal": True})
                    break
                except Exception as e:  # transient
                    if self._closed.is_set():
                        break
                    failures += 1
                    delay = self._backoff[min(failures - 1, len(self._backoff) - 1)]
                    self._emit({"type": "status", "message": "Speech service reconnecting..."})
                    replay_since_final = True
                    self._sleep(delay)
        finally:
            self._emit(None)

    def _sleep(self, seconds: float) -> None:
        end = self._clock() + seconds
        while not self._closed.is_set() and self._clock() < end:
            time.sleep(min(0.05, max(0.0, end - self._clock())))

    def _on_result(self, res: Result) -> None:
        text = (res.text or "").strip()
        if not text:
            return
        if res.is_final:
            self._final_mark = self._total
            self._boundary.set()
            self._emit({"type": "final", "text": text})
        else:
            self._emit({"type": "partial", "text": text})

    def _audio(self, replay: bool) -> Iterator[bytes]:
        """Audio for ONE Google stream. Ends (returns) when it is time to rotate or the session closes."""
        started = self._clock()
        self._boundary.clear()
        if replay:
            pending = self._total - self._final_mark
            if 0 < pending <= len(self._recent):
                yield bytes(self._recent[-pending:])
            elif pending > len(self._recent) and self._recent:
                yield bytes(self._recent[-int(2 * BYTES_PER_SECOND):])
        while not self._closed.is_set():
            age = self._clock() - started
            if age >= self._hard or (age >= self._soft and self._boundary.is_set()):
                return
            try:
                chunk = self._q.get(timeout=0.1)
            except queue.Empty:
                continue
            with self._q_lock:
                self._q_bytes -= len(chunk)
            self._recent += chunk
            if len(self._recent) > self._max_recent_bytes:
                del self._recent[: len(self._recent) - self._max_recent_bytes]
            self._total += len(chunk)
            yield chunk
