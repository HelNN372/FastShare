#!/usr/bin/env python3
"""Pair two TLS clients and forward opaque FastShare frames."""
import argparse
import asyncio
import ssl
from dataclasses import dataclass, field

MAGIC = b"FSRELAY2"
ROOM_TTL = 300
IDLE_TTL = 3600
MAX_CLIENTS = 128


@dataclass
class Waiting:
    reader: asyncio.StreamReader
    writer: asyncio.StreamWriter
    paired: asyncio.Event = field(default_factory=asyncio.Event)
    ready: asyncio.Event = field(default_factory=asyncio.Event)
    done: asyncio.Event = field(default_factory=asyncio.Event)


class Relay:
    def __init__(self):
        self.waiting = {}
        self.active = set()
        self.clients = 0

    async def handle(self, reader, writer):
        self.clients += 1
        room = None
        entry = None
        try:
            if self.clients > MAX_CLIENTS:
                await self.reply(writer, 2)
                return
            registration = await asyncio.wait_for(reader.readexactly(40), 10)
            if not registration.startswith(MAGIC):
                return
            room = registration[len(MAGIC):]
            if room in self.active:
                await self.reply(writer, 2)
                return
            partner = self.waiting.pop(room, None)
            if partner is None:
                entry = Waiting(reader, writer)
                self.waiting[room] = entry
                await self.wait_for_partner(entry)
                return
            self.active.add(room)
            partner.paired.set()
            try:
                await partner.ready.wait()
                await self.reply(partner.writer, 1)
                await self.reply(writer, 1)
                await self.bridge(partner.reader, partner.writer, reader, writer)
            finally:
                self.active.discard(room)
                partner.done.set()
        except (OSError, asyncio.IncompleteReadError, asyncio.TimeoutError):
            pass
        finally:
            if entry is not None and self.waiting.get(room) is entry:
                self.waiting.pop(room, None)
            self.clients -= 1
            writer.close()
            try:
                await asyncio.wait_for(writer.wait_closed(), 5)
            except (OSError, asyncio.TimeoutError):
                pass

    async def wait_for_partner(self, entry):
        # The client stays silent until it receives a pairing response.
        disconnect = asyncio.create_task(entry.reader.read(1))
        pairing = asyncio.create_task(entry.paired.wait())
        try:
            await asyncio.wait((disconnect, pairing), timeout=ROOM_TTL,
                               return_when=asyncio.FIRST_COMPLETED)
        finally:
            for task in (disconnect, pairing):
                task.cancel()
            await asyncio.gather(disconnect, pairing, return_exceptions=True)
        if entry.paired.is_set():
            entry.ready.set()
            await entry.done.wait()

    @staticmethod
    async def reply(writer, value):
        writer.write(bytes([value]))
        await asyncio.wait_for(writer.drain(), 10)

    @staticmethod
    async def bridge(left_reader, left_writer, right_reader, right_writer):
        async def pipe(reader, writer):
            while True:
                data = await asyncio.wait_for(reader.read(128 * 1024), IDLE_TTL)
                if not data:
                    return
                writer.write(data)
                await asyncio.wait_for(writer.drain(), 60)

        tasks = (asyncio.create_task(pipe(left_reader, right_writer)),
                 asyncio.create_task(pipe(right_reader, left_writer)))
        try:
            await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)


async def main(args):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(args.cert, args.key)
    relay = Relay()
    server = await asyncio.start_server(relay.handle, args.host, args.port, ssl=context,
                                        ssl_handshake_timeout=10)
    print(f"FastShare relay listening on {args.host}:{args.port}", flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=47443)
    parser.add_argument("--cert", required=True, help="TLS fullchain PEM")
    parser.add_argument("--key", required=True, help="TLS private key PEM")
    try:
        asyncio.run(main(parser.parse_args()))
    except KeyboardInterrupt:
        pass
