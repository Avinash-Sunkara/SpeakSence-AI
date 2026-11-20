import asyncio
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from stt_session import BYTES_PER_SECOND, FatalBackendError, Result, SttSession  # noqa: E402

CHUNK = b"\x01\x00" * 640  # 40 ms


class ScriptedBackend:
    """Each run() call pops one behaviour from `plans`; records every audio chunk it consumed per stream."""

    def __init__(self, plans):
        self.plans = list(plans)
        self.streams = []

    def run(self, config, audio):
        consumed = []
        self.streams.append(consumed)
        plan = self.plans.pop(0) if self.plans else "idle"
        if plan == "fatal":
            raise FatalBackendError("bad credentials")
        if plan == "transient":
            raise ConnectionError("network down")
        for chunk in audio:
            consumed.append(chunk)
            if plan == "echo" and len(consumed) == 3:
                yield Result(False, "నేను")
            if plan == "echo" and len(consumed) == 5:
                yield Result(False, "నేను కాలేజీకి")
            if plan == "echo" and len(consumed) == 7:
                yield Result(True, "నేను College కి 10 PM కి వస్తాను.")


async def collect(session, n, timeout=5):
    out = []

    async def go():
        async for e in session.events():
            out.append(e)
            if len(out) >= n:
                return

    await asyncio.wait_for(go(), timeout)
    return out


class SessionTests(unittest.IsolatedAsyncioTestCase):
    async def test_partials_then_final_in_order_and_telugu_preserved(self):
        be = ScriptedBackend(["echo"])
        s = SttSession(be, asyncio.get_running_loop(), {})
        s.start()
        for _ in range(8):
            s.feed(CHUNK)
        events = await collect(s, 3)
        s.close()
        self.assertEqual([e["type"] for e in events], ["partial", "partial", "final"])
        self.assertEqual(events[2]["text"], "నేను College కి 10 PM కి వస్తాను.")

    async def test_fatal_error_is_reported_once_and_ends(self):
        s = SttSession(ScriptedBackend(["fatal"]), asyncio.get_running_loop(), {})
        s.start()
        s.feed(CHUNK)
        events = await collect(s, 1)
        self.assertTrue(events[0]["fatal"])
        s.join()

    async def test_transient_error_reconnects_and_keeps_audio(self):
        be = ScriptedBackend(["transient", "echo"])
        s = SttSession(be, asyncio.get_running_loop(), {}, backoff_s=(0.05,))
        s.start()
        for _ in range(8):
            s.feed(CHUNK)  # queued while the first stream is failing
        events = await collect(s, 4)
        s.close()
        self.assertEqual(events[0]["type"], "status")
        self.assertEqual(events[-1]["type"], "final")
        self.assertEqual(be.streams[1][:3], [CHUNK] * 3)  # queued audio reached the second stream
        self.assertEqual(s.streams_opened, 2)

    async def test_rotation_after_final_replays_audio_since_final(self):
        clock = {"t": 0.0}
        be = ScriptedBackend(["echo", "idle"])
        s = SttSession(be, asyncio.get_running_loop(), {}, rotate_soft_s=1.0, rotate_hard_s=100.0,
                       clock=lambda: clock["t"])
        s.start()
        for _ in range(7):
            s.feed(CHUNK)
        await collect(s, 3)  # final arrived after 7 chunks
        clock["t"] = 2.0  # stream older than soft limit AND a final was seen -> rotate
        s.feed(CHUNK)  # audio after the final
        s.feed(CHUNK)
        for _ in range(100):
            if s.streams_opened >= 2 and be.streams[1]:
                break
            await asyncio.sleep(0.05)
        s.close()
        self.assertGreaterEqual(s.streams_opened, 2)
        self.assertTrue(len(be.streams[1]) >= 1, "new stream must receive the audio that followed the final")

    async def test_queue_drops_oldest_not_newest(self):
        be = ScriptedBackend(["transient"] * 50)
        s = SttSession(be, asyncio.get_running_loop(), {}, max_queue_s=0.1, backoff_s=(5,))
        # do not start the worker: inspect the queue policy directly
        for i in range(20):
            s.feed(bytes([i]) * 640)
        kept = []
        while not s._q.empty():
            kept.append(s._q.get_nowait()[0])
        self.assertEqual(kept[-1], 19)
        self.assertLess(len(kept), 20)


if __name__ == "__main__":
    unittest.main()
