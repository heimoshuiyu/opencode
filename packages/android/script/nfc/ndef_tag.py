"""File-backed NFC Forum Type 4 test tag, only for a local Casimir RF simulator.

Run with a private fixture directory's image path and the simulator RF port.
Stop to remove, restart with the same file to re-present. No Android APIs.
Wire format: google/casimir@50476e8 src/rf_packets.pdl.
"""
import argparse
import socket
import struct
from pathlib import Path

UID = bytes.fromhex('084f4301')  # synthetic fixture only
CAPACITY = 4096
CC = bytes.fromhex('000f 20 00ff 00ff 0406 e104 1000 0000')
AID = bytes.fromhex('d2760000850101')
OK = bytes.fromhex('9000')
HEADER = struct.Struct('<HHBBBBB')
POLL, POLLED, SELECT, SELECTED, DEACTIVATE = range(1, 6)
DATA, ISO_DEP = 0, 4


class NdefFiles:
    def __init__(self, path):
        if not path.exists():
            with path.open('xb') as f:
                f.write(bytes(CAPACITY))
        if path.stat().st_size != CAPACITY:
            raise ValueError('Not a fixture image')
        self.path = path
        self.active = False
        self.file = None

    def exchange(self, cmd):
        if len(cmd) < 5 or cmd[0] != 0:
            return b'\x67\x00'
        _, ins, p1, p2, count = cmd[:5]
        body = cmd[5:]
        if ins == 0xa4:
            self.file = None
            if p1 == 4:
                self.active = count == len(AID) and body in (AID, AID + b'\0')
                return OK if self.active else b'\x6a\x82'
            if self.active and p1 == 0 and count == 2 and body in (b'\xe1\x03', b'\xe1\x04'):
                self.file = body
                return OK
            return b'\x6a\x82'
        if not self.file:
            return b'\x69\x86'
        offset = (p1 << 8) | p2
        if ins == 0xb0 and not body:
            content = CC if self.file == b'\xe1\x03' else self.path.read_bytes()
            end = offset + (count or 256)
            if end > len(content):
                return b'\x6b\x00'
            return content[offset:end] + OK
        if ins == 0xd6 and self.file == b'\xe1\x04' and len(body) == count and count > 0:
            if offset + count > CAPACITY:
                return b'\x6b\x00'
            with self.path.open('r+b') as f:
                f.seek(offset)
                f.write(body)
            return OK
        return b'\x6d\x00'


def receive(f):
    size = f.read(2)
    if len(size) != 2:
        raise EOFError('Casimir closed the link')
    packet = f.read(int.from_bytes(size, 'little'))
    return HEADER.unpack_from(packet), packet[HEADER.size:]


def send(sock, peer, kind, payload, proto):
    packet = HEADER.pack(0, peer, 0, proto, kind, 0, 12) + payload
    sock.sendall(len(packet).to_bytes(2, 'little') + packet)


def listen(port, path):
    tag = NdefFiles(path)
    peer = None
    with socket.create_connection(('127.0.0.1', port)) as sock, sock.makefile('rb') as f:
        print('READY', UID.hex(), flush=True)
        while True:
            (sender, _, tech, proto, kind, _, _), data = receive(f)
            if tech != 0:
                continue
            if kind == POLL:
                send(sock, sender, POLLED, bytes([len(UID)]) + UID + b'\x01\x04', 0)
            elif kind == SELECT and proto == ISO_DEP:
                peer, tag.active, tag.file = sender, False, None
                send(sock, peer, SELECTED, b'\x01\x08', ISO_DEP)
            elif kind == DATA and sender == peer and proto == ISO_DEP:
                send(sock, peer, DATA, tag.exchange(data), ISO_DEP)
            elif kind == DEACTIVATE and sender == peer:
                peer, tag.active, tag.file = None, False, None


if __name__ == '__main__':
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('rf_port', type=int)
    cli.add_argument('image', type=Path)
    args = cli.parse_args()
    listen(args.rf_port, args.image)
