"""Host-side Casimir preflight, NOT an Android/MCP test. Uses a fresh simulator and image."""
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import uuid
from ndef_tag import receive, send


@contextmanager
def process(args, **kw):
    proc = subprocess.Popen(args, **kw)
    try:
        yield proc
    finally:
        proc.terminate()
        proc.wait(timeout=5)


def main(casimir, directory):
    directory.mkdir(mode=0o700, exist_ok=False)
    image = directory / 'tag.bin'
    text = 'OpenCode NFC ' + uuid.uuid4().hex[:12]
    payload = b'\x02en' + text.encode()
    record = b'\xd1\x01' + bytes([len(payload)]) + b'T' + payload
    nlen = len(record).to_bytes(2, 'big')
    # Reserve two currently unused loopback ports for this short-lived daemon.
    with socket.socket() as a, socket.socket() as b:
        a.bind(('127.0.0.1', 0)); b.bind(('127.0.0.1', 0))
        nci, rf = a.getsockname()[1], b.getsockname()[1]
    with process([casimir, '--nci-port', str(nci), '--rf-port', str(rf)], stderr=subprocess.PIPE,
                 text=True, env={**os.environ, 'RUST_LOG': 'info'}) as server:
        for line in server.stderr:
            if f'Listening for RF connections at address 127.0.0.1:{rf}' in line:
                break
        else:
            raise RuntimeError('Casimir did not start')
        with socket.create_connection(('127.0.0.1', rf), timeout=4) as poller, poller.makefile('rb') as f:
            slots = []
            for first in (True, False):
                with process([sys.executable, Path(__file__).with_name('ndef_tag.py'), str(rf), image], stdout=subprocess.PIPE, text=True) as tag:
                    assert tag.stdout.readline().strip() == 'READY 084f4301'
                    send(poller, 65535, 1, b'\0', 0)
                    header, data = receive(f)
                    slot = header[0]; slots.append(slot)
                    assert header[4] == 2 and data[1:5].hex() == '084f4301'
                    send(poller, slot, 3, b'\0', 4)
                    header, _ = receive(f); assert header[0] == slot and header[4] == 4

                    def command(apdu):
                        send(poller, slot, 0, apdu, 4)
                        h, response = receive(f)
                        assert h[0] == slot and h[4] == 0 and response[-2:] == b'\x90\0', response.hex()
                        return response[:-2]

                    command(bytes.fromhex('00a4040007d276000085010100'))
                    command(bytes.fromhex('00a4000c02e103'))
                    assert command(bytes.fromhex('00b000000f')).hex() == '000f2000ff00ff0406e10410000000'
                    command(bytes.fromhex('00a4000c02e104'))
                    if first:
                        assert command(bytes.fromhex('00b0000002')) == bytes(2)
                        command(bytes.fromhex('00d60000020000'))
                        command(bytes.fromhex('00d60002') + bytes([len(record)]) + record)
                        command(bytes.fromhex('00d6000002') + nlen)
                        digest = hashlib.sha256(image.read_bytes()).hexdigest()
                    else:
                        assert command(bytes.fromhex('00b0000002')) == nlen
                        readback = command(bytes.fromhex('00b00002') + bytes([len(record)]))
                        assert readback == record and image.read_bytes()[:2+len(record)] == nlen + readback
                        assert hashlib.sha256(image.read_bytes()).hexdigest() == digest
                h, data = receive(f)
                assert h[0] == slot and h[4] == 5 and data[-1] == 2, 'No RF link loss detected'
            assert slots[0] != slots[1]
            print(json.dumps(dict(source='Casimir RF host test; no Android or MCP', uid='084f4301',
                                  rfConnections=slots, linkLossObserved=True, bytes=len(record),
                                  write=text, reTapRead=readback[7:].decode(), imageSHA256=digest)))


if __name__ == '__main__':
    main(sys.argv[1], Path(sys.argv[2]))
