"""Does this client mod work on older RE:Morph servers? Run it before every release.

For each server plugin jar given, starts the local test server (test-server/, port 25599) with that jar and a fresh
plugin data folder, starts the dev client (fabric-client runClient2, player MorphTester2) built from this checkout,
and drives the server over RCON so it sends every kind of packet: hello (and a repeated hello on a mode switch), the
collection, morph states with transitions, client-side traits, an acquisition, Survival status, morph prices and the
upgrade tree. Then it reads both logs for problems and prints one line per server.

    py -3 tools/compat_check.py path/to/RE-Morph-paper-6.0.0.jar path/to/RE-Morph-paper-7.1.2.jar ...

The plugin's data folder and the jar that was installed are put back afterwards. Needs JAVA_HOME (Java 25+), the
test server set up as in docs/TESTING.md (Paper, Lib's Disguises, PacketEvents, RCON on 25598, MorphTester2 an
operator) and runs on Windows (processes are stopped through PowerShell). docs/PROTOCOL.md has the rules this checks.
"""
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SERVER = ROOT / "test-server"
PLUGINS = SERVER / "plugins"
DATA = PLUGINS / "RE-Morph"
LOGS = SERVER / "compat"
PLAYER = "MorphTester2"

# Packets each command makes the server send, in the order they run; seconds to wait after each.
DRIVE = [
    ("morph clients", 1),
    ("morph grant {p} pig", 1), ("morph grant {p} spider", 1), ("morph grant {p} chicken", 1),
    ("morph grant {p} sheep {{Color:5b}}", 1),                          # collection with a variant
    ("morph force {p} spider", 7),                                       # state + transition, climb trait
    ("morph force {p} chicken", 7),                                      # slow-fall trait
    ("execute at {p} run summon cow ~2 ~ ~ {{NoAI:1b}}", 1),
    ("damage @e[type=cow,sort=nearest,limit=1] 100 minecraft:player_attack by {p}", 6),  # acquisition + unlock
    ("morph mode survival", 3),                                          # repeated hello, Survival status, prices
    ("morph survival {p} give 500", 2),
    ("morph survival {p} unlock", 2),
    ("morph mode classic", 3),
    ("morph force {p} off", 6),                                          # back to the player: forget
    ("morph info {p}", 1),
]


def rcon(*commands: str) -> list[str]:
    out = []
    with socket.create_connection(("127.0.0.1", 25598), timeout=10) as s:
        _packet(s, 1, 3, "morphtest")
        for c in commands:
            out.append(_packet(s, 2, 2, c))
    return out


def _packet(sock, req_id, kind, body):
    data = struct.pack("<ii", req_id, kind) + body.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(data)) + data)
    size = struct.unpack("<i", _recv(sock, 4))[0]
    return _recv(sock, size)[8:-2].decode("utf-8", "replace")


def _recv(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("closed")
        buf += chunk
    return buf


def java_running(pattern: str) -> bool:
    ps = ("if (Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -match '%s' })"
          " { exit 1 } else { exit 0 }") % pattern
    return subprocess.run(["powershell", "-NoProfile", "-Command", ps]).returncode == 1


def kill_java(pattern: str):
    ps = ("Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -match '%s' }"
          " | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }") % pattern
    subprocess.run(["powershell", "-NoProfile", "-Command", ps])


def stop_all():
    try:
        rcon("stop")
    except OSError:
        pass
    kill_java(PLAYER)
    for _ in range(60):
        if not java_running("paper.jar"):
            return
        time.sleep(1)
    kill_java("paper.jar")


def wait_for(path: Path, needle: str, timeout: float, fail: str | None = None) -> bool:
    end = time.time() + timeout
    while time.time() < end:
        text = path.read_text("utf-8", "replace") if path.exists() else ""
        if needle in text:
            return True
        if fail and fail in text:
            return False
        time.sleep(2)
    return False


def own_version() -> str:
    for line in (ROOT / "fabric-client" / "gradle.properties").read_text("utf-8").splitlines():
        if line.startswith("mod_version="):
            return line.split("=", 1)[1].split("+")[0]
    return "?"


PROBLEM_CLIENT = re.compile(r"malformed|/(WARN|ERROR)\] \(RE:Morph\)|at .*dev\.morphreborn|Crash Report", re.I)
PROBLEM_SERVER = re.compile(r"malformed RE:Morph|Unknown packet|at .*dev\.morphreborn|\[RE-Morph\].*(exception|error)", re.I)


def check(jar: Path, java: str) -> str:
    name = jar.stem.replace("RE-Morph-paper-", "")
    stop_all()
    for old in PLUGINS.glob("RE-Morph-paper-*.jar"):
        old.unlink()
    shutil.copy2(jar, PLUGINS / jar.name)
    shutil.rmtree(DATA, ignore_errors=True)  # an old plugin starts fresh, as on a server that never ran a newer one
    server_log = LOGS / f"{name}-server.log"
    client_log = LOGS / f"{name}-client.log"
    with open(server_log, "w", encoding="utf-8") as out:
        subprocess.Popen([java, "-Xms1G", "-Xmx3G", "-jar", "paper.jar", "--nogui"], cwd=SERVER, stdout=out,
                         stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
    if not wait_for(server_log, "Done (", 180):
        return f"{name:14} server did not start"
    gradlew = str(ROOT / "fabric-client" / "gradlew.bat")
    with open(client_log, "w", encoding="utf-8") as out:
        subprocess.Popen([gradlew, "runClient2", "-q"], cwd=ROOT / "fabric-client", stdout=out,
                         stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
    if not wait_for(server_log, f"{PLAYER} joined", 300, None):
        return f"{name:14} client did not join (see {client_log.name})"
    time.sleep(12)  # the handshake, the version notice and the first sync
    replies = []
    with open(LOGS / f"{name}-rcon.log", "w", encoding="utf-8") as log:
        for command, pause in DRIVE:
            command = command.format(p=PLAYER)
            replies += rcon(command)
            log.write("> " + command + "\n" + re.sub("§.", "", replies[-1]).strip() + "\n")
            time.sleep(pause)
    time.sleep(3)
    stop_all()
    client = client_log.read_text("utf-8", "replace")
    server = server_log.read_text("utf-8", "replace")
    connected = "Connected to a RE:Morph server" in client
    notice = re.search(r"\[CHAT\] (This server runs[^\r\n]*)", client)
    seen = re.search(r"client (\S+)", re.sub("§.", "", replies[-1]))
    problems = [l.strip() for l in client.splitlines() if PROBLEM_CLIENT.search(l)]
    problems += ["server: " + l.strip() for l in server.splitlines() if PROBLEM_SERVER.search(l)]
    verdict = "OK" if connected and not problems else "PROBLEM"
    line = (f"{name:14} {verdict:8} handshake {'yes' if connected else 'NO'}, server saw client "
            f"{seen.group(1) if seen else '?'}; notice: {notice.group(1)[:60] + '...' if notice else 'none'}")
    for p in problems[:8]:
        line += "\n" + " " * 15 + p[:200]
    return line


def main():
    jars = [Path(a).resolve() for a in sys.argv[1:]]
    if not jars:
        sys.exit(__doc__)
    java = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java")
    LOGS.mkdir(exist_ok=True)
    installed = list(PLUGINS.glob("RE-Morph-paper-*.jar"))
    saved_jars = LOGS / "installed-jars"
    saved_data = LOGS / "data-backup"
    shutil.rmtree(saved_jars, ignore_errors=True)
    saved_jars.mkdir()
    for j in installed:
        shutil.copy2(j, saved_jars / j.name)
    shutil.rmtree(saved_data, ignore_errors=True)
    if DATA.exists():
        shutil.copytree(DATA, saved_data)
    print(f"Client mod {own_version()} against {len(jars)} server(s); logs in {LOGS.relative_to(ROOT)}")
    try:
        for jar in jars:
            print(check(jar, java), flush=True)
    finally:
        stop_all()
        for old in PLUGINS.glob("RE-Morph-paper-*.jar"):
            old.unlink()
        for j in saved_jars.iterdir():
            shutil.copy2(j, PLUGINS / j.name)
        shutil.rmtree(DATA, ignore_errors=True)
        if saved_data.exists():
            shutil.copytree(saved_data, DATA)
        print("Test server put back as it was.")


if __name__ == "__main__":
    main()
