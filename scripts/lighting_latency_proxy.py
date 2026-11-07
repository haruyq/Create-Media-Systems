import argparse
import asyncio
import json
import math
import random
import time
from pathlib import Path


CHUNK = 64 * 1024
QUEUED_BYTES = 1024 * 1024


class EventLog:
    def __init__(self, path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.output = path.open("a", encoding="utf-8", buffering=1)

    def emit(self, event, **values):
        self.output.write(json.dumps({"time": time.time(), "event": event, **values}, separators=(",", ":")) + "\n")

    def close(self):
        self.output.close()


class Conditions:
    def __init__(self, delay_ms, jitter_ms):
        self.delay_ms = self.valid(delay_ms)
        self.jitter_ms = self.valid(jitter_ms)

    @staticmethod
    def valid(value):
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not 0 <= value <= 60_000:
            raise ValueError("delay and jitter must be finite numbers between 0 and 60000 ms")
        return float(value)

    def delay(self):
        return max(0, self.delay_ms + random.uniform(-self.jitter_ms, self.jitter_ms)) / 1000

    async def watch(self, path, log):
        previous_error = None
        while True:
            try:
                values = json.loads(path.read_text(encoding="utf-8-sig"))
                delay = self.valid(values.get("delay_ms", self.delay_ms))
                jitter = self.valid(values.get("jitter_ms", self.jitter_ms))
                if (delay, jitter) != (self.delay_ms, self.jitter_ms):
                    self.delay_ms, self.jitter_ms = delay, jitter
                    log.emit("delay_change", delay_ms=delay, jitter_ms=jitter)
                previous_error = None
            except (OSError, ValueError, AttributeError) as error:
                message = str(error)
                if message != previous_error:
                    log.emit("control_error", reason=message)
                    previous_error = message
            await asyncio.sleep(0.25)


class Budget:
    def __init__(self):
        self.queued = 0
        self.changed = asyncio.Condition()

    async def reserve(self, size):
        async with self.changed:
            await self.changed.wait_for(lambda: self.queued + size <= QUEUED_BYTES)
            self.queued += size

    async def release(self, size):
        async with self.changed:
            self.queued -= size
            self.changed.notify_all()


async def forward(reader, writer, conditions, counts, direction):
    pending = asyncio.Queue(maxsize=64)
    budget = Budget()

    async def receive():
        previous = 0
        while data := await reader.read(CHUNK):
            await budget.reserve(len(data))
            previous = max(previous, asyncio.get_running_loop().time() + conditions.delay())
            await pending.put((previous, data))
        await pending.put((previous, None))

    async def send():
        while True:
            scheduled, data = await pending.get()
            await asyncio.sleep(max(0, scheduled - asyncio.get_running_loop().time()))
            if data is None:
                if writer.can_write_eof():
                    writer.write_eof()
                    await writer.drain()
                return
            writer.write(data)
            await writer.drain()
            counts[direction] += len(data)
            await budget.release(len(data))

    async with asyncio.TaskGroup() as tasks:
        tasks.create_task(receive())
        tasks.create_task(send())


class Proxy:
    def __init__(self, target_port, conditions, log):
        self.target_port = target_port
        self.conditions = conditions
        self.log = log
        self.connections = set()
        self.sequence = 0

    async def connect(self, reader, writer):
        task = asyncio.current_task()
        self.connections.add(task)
        self.sequence += 1
        connection = self.sequence
        started = time.monotonic()
        counts = {"up": 0, "down": 0}
        target_writer = None
        self.log.emit("connection_open", connection=connection, peer=writer.get_extra_info("peername"),
                      delay_ms=self.conditions.delay_ms, jitter_ms=self.conditions.jitter_ms)
        try:
            target_reader, target_writer = await asyncio.wait_for(
                asyncio.open_connection("127.0.0.1", self.target_port), 10)
            async with asyncio.TaskGroup() as tasks:
                tasks.create_task(forward(reader, target_writer, self.conditions, counts, "up"))
                tasks.create_task(forward(target_reader, writer, self.conditions, counts, "down"))
        except asyncio.CancelledError:
            raise
        except Exception as error:
            self.log.emit("connection_error", connection=connection, reason=str(error))
        finally:
            for stream in (writer, target_writer):
                if stream is not None:
                    stream.close()
                    try:
                        await asyncio.wait_for(stream.wait_closed(), 2)
                    except (OSError, TimeoutError):
                        stream.transport.abort()
            self.log.emit("connection_close", connection=connection,
                          seconds=round(time.monotonic() - started, 3), **counts)
            self.connections.discard(task)

    async def close(self):
        tasks = list(self.connections)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)


async def run(args):
    log = EventLog(args.log)
    conditions = Conditions(args.delay_ms, args.jitter_ms)
    proxy = Proxy(args.target_port, conditions, log)
    watch = asyncio.create_task(conditions.watch(args.control, log)) if args.control else None
    try:
        async with await asyncio.start_server(proxy.connect, "127.0.0.1", args.listen) as server:
            log.emit("listening", listen=args.listen, target_port=args.target_port,
                     delay_ms=conditions.delay_ms, jitter_ms=conditions.jitter_ms)
            print(f"127.0.0.1:{args.listen} -> 127.0.0.1:{args.target_port}; one-way delay {conditions.delay_ms:g} ms", flush=True)
            await server.serve_forever()
    finally:
        if watch is not None:
            watch.cancel()
            await asyncio.gather(watch, return_exceptions=True)
        await proxy.close()
        log.emit("stopped")
        log.close()


def port(value):
    number = int(value)
    if not 1 <= number <= 65535:
        raise argparse.ArgumentTypeError("port must be between 1 and 65535")
    return number


def main():
    parser = argparse.ArgumentParser(description="Local TCP proxy with bounded, ordered one-way delay in both directions.")
    parser.add_argument("--listen", type=port, default=25566, metavar="PORT")
    parser.add_argument("--target-port", type=port, default=25565)
    parser.add_argument("--delay-ms", type=float, default=0)
    parser.add_argument("--jitter-ms", type=float, default=0)
    parser.add_argument("--control", type=Path, help="JSON file polled every 250 ms for delay_ms and jitter_ms")
    parser.add_argument("--log", type=Path, default=Path("lighting-latency.jsonl"))
    args = parser.parse_args()
    try:
        Conditions(args.delay_ms, args.jitter_ms)
    except ValueError as error:
        parser.error(str(error))
    if args.listen == args.target_port:
        parser.error("listen and target ports must differ")
    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
