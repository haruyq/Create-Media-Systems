import asyncio
import contextlib
import json
import tempfile
import time
import unittest
from pathlib import Path

from lighting_latency_proxy import Conditions, EventLog, Proxy


class LatencyProxyTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.log = EventLog(Path(self.temp.name) / "proxy.jsonl")

        async def echo(reader, writer):
            try:
                while data := await reader.read(4096):
                    writer.write(data)
                    await writer.drain()
            finally:
                writer.close()
                with contextlib.suppress(OSError):
                    await writer.wait_closed()

        self.echo = await asyncio.start_server(echo, "127.0.0.1", 0)
        self.conditions = Conditions(200, 0)
        self.proxy = Proxy(self.echo.sockets[0].getsockname()[1], self.conditions, self.log)
        self.server = await asyncio.start_server(self.proxy.connect, "127.0.0.1", 0)
        self.reader, self.writer = await asyncio.open_connection("127.0.0.1", self.server.sockets[0].getsockname()[1])

    async def asyncTearDown(self):
        self.writer.close()
        with contextlib.suppress(OSError):
            await self.writer.wait_closed()
        self.server.close()
        await self.server.wait_closed()
        await self.proxy.close()
        self.echo.close()
        await self.echo.wait_closed()
        self.log.close()
        self.temp.cleanup()

    async def roundtrip(self):
        start = time.monotonic()
        self.writer.write(b"ping")
        await self.writer.drain()
        self.assertEqual(b"ping", await asyncio.wait_for(self.reader.readexactly(4), 3))
        return time.monotonic() - start

    async def test_roundtrip_stream_order_and_half_close_without_chunk_accumulation(self):
        rtt = await self.roundtrip()
        self.assertGreater(rtt, 0.38)
        self.assertLess(rtt, 1)
        chunks = [bytes([index]) * 4096 for index in range(40)]
        start = time.monotonic()

        async def transmit():
            for chunk in chunks:
                self.writer.write(chunk)
                await self.writer.drain()
                await asyncio.sleep(0.002)
            self.writer.write_eof()

        send = asyncio.create_task(transmit())
        received = await asyncio.wait_for(self.reader.read(), 3)
        await send
        elapsed = time.monotonic() - start
        self.assertEqual(b"".join(chunks), received)
        self.assertLess(elapsed, 2, "delay accumulated once per chunk instead of scheduling packets independently")
        await asyncio.wait_for(asyncio.gather(*list(self.proxy.connections)), 2)
        events = [json.loads(line) for line in (Path(self.temp.name) / "proxy.jsonl").read_text().splitlines()]
        closed = next(event for event in events if event["event"] == "connection_close")
        self.assertEqual((len(received) + 4, len(received) + 4), (closed["up"], closed["down"]))
        print(json.dumps({"one_way_ms": 200, "rtt_ms": round(rtt * 1000, 1),
                          "chunks": len(chunks), "stream_ms": round(elapsed * 1000, 1)}))

    async def test_control_updates_existing_connection_and_jitter_keeps_byte_order(self):
        path = Path(self.temp.name) / "control.json"
        path.write_text('{"delay_ms":20,"jitter_ms":0}', encoding="utf-8")
        watch = asyncio.create_task(self.conditions.watch(path, self.log))
        try:
            await asyncio.sleep(0.03)
            rtt = await self.roundtrip()
            self.assertGreater(rtt, 0.03)
            self.assertLess(rtt, 0.3)
            path.write_text('{"delay_ms":40,"jitter_ms":30}', encoding="utf-8")
            await asyncio.sleep(0.3)
            self.assertEqual((40, 30), (self.conditions.delay_ms, self.conditions.jitter_ms))
            payload = bytes(range(256)) * 8192
            self.writer.write(payload)
            await self.writer.drain()
            self.assertEqual(payload, await asyncio.wait_for(self.reader.readexactly(len(payload)), 5))
            print(json.dumps({"updated_rtt_ms": round(rtt * 1000, 1), "ordered_jitter_bytes": len(payload)}))
        finally:
            watch.cancel()
            await asyncio.gather(watch, return_exceptions=True)


if __name__ == "__main__":
    unittest.main()
