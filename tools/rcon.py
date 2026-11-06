"""Tiny RCON client for the local test server: py -3 -I tools/rcon.py "command" ["command" ...]"""
import socket, struct, sys

def packet(sock, req_id, kind, body):
    data = struct.pack('<ii', req_id, kind) + body.encode('utf-8') + b'\x00\x00'
    sock.sendall(struct.pack('<i', len(data)) + data)
    size = struct.unpack('<i', recv(sock, 4))[0]
    resp = recv(sock, size)
    return resp[8:-2].decode('utf-8', 'replace')

def recv(sock, n):
    buf = b''
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError('closed')
        buf += chunk
    return buf

with socket.create_connection(('127.0.0.1', 25598), timeout=10) as s:
    packet(s, 1, 3, 'morphtest')
    for cmd in sys.argv[1:]:
        print('>', cmd)
        print(packet(s, 2, 2, cmd))
