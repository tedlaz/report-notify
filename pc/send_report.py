# /// script
# requires-python = ">=3.10"
# dependencies = ["cryptography", "segno"]
# ///
"""Send an encrypted HTML report via ntfy to the Report Notify Android app.

    uv run send_report.py --genkey               # once: create config.json + show pairing QR
    uv run send_report.py --qr                   # show the pairing QR again
    uv run send_report.py report.html "Title"    # send a report
    some_cmd | uv run send_report.py - "Title"   # HTML from stdin
"""
import base64
import json
import os
import secrets
import sys
import tempfile
import urllib.parse
import urllib.request
import webbrowser
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

CONFIG = Path(__file__).with_name("config.json")
SERVER = "https://ntfy.sh"


def encrypt(key: bytes, data: bytes, nonce: bytes | None = None) -> bytes:
    """Wire format: nonce(12) + ciphertext + tag(16). Must match Core.kt decrypt()."""
    nonce = nonce or os.urandom(12)
    return nonce + AESGCM(key).encrypt(nonce, data, None)


def decrypt(key: bytes, blob: bytes) -> bytes:
    return AESGCM(key).decrypt(blob[:12], blob[12:], None)


def genkey() -> None:
    if CONFIG.exists():
        sys.exit(f"{CONFIG} already exists; delete it first to re-pair")
    cfg = {
        "server": SERVER,
        "topic": "r" + secrets.token_hex(16),  # the topic name is the only access control on ntfy.sh
        "key": base64.b64encode(os.urandom(32)).decode(),
    }
    CONFIG.write_text(json.dumps(cfg, indent=2))
    show_qr()


def show_qr() -> None:
    import segno

    cfg = json.loads(CONFIG.read_text())
    pairing = f"{cfg['server']}/{cfg['topic']}#{cfg['key']}"
    # PNG in the default image viewer: terminal QR rendering is unreliable on Windows consoles.
    fd, png = tempfile.mkstemp(suffix=".png")
    os.close(fd)
    try:
        segno.make(pairing, error="m").save(png, scale=10, border=4)
        webbrowser.open(Path(png).as_uri())
        print("Scan the QR with the phone app (\"Scan QR\"), or paste:\n" + pairing)
        input("\nPress Enter when done (the QR image is then deleted)...")
    finally:
        os.remove(png)  # it contains the key


def send(src: str, title: str) -> None:
    cfg = json.loads(CONFIG.read_text())
    html = sys.stdin.buffer.read() if src == "-" else Path(src).read_bytes()
    blob = encrypt(base64.b64decode(cfg["key"]), html)
    # Title goes as a query param so non-ASCII (Greek) works; it is NOT encrypted.
    qs = urllib.parse.urlencode({"title": title, "filename": "r.bin"})
    req = urllib.request.Request(f"{cfg['server']}/{cfg['topic']}?{qs}", data=blob, method="PUT")
    with urllib.request.urlopen(req, timeout=30) as r:
        print("sent", json.load(r)["id"])


if __name__ == "__main__":
    if sys.argv[1:] == ["--genkey"]:
        genkey()
    elif sys.argv[1:] == ["--qr"]:
        show_qr()
    elif len(sys.argv) == 3:
        send(sys.argv[1], sys.argv[2])
    else:
        sys.exit(__doc__)
