# /// script
# requires-python = ">=3.10"
# dependencies = ["cryptography"]
# ///
"""uv run test_crypto.py — same vector is checked in app/src/test/.../CoreTest.kt."""
from send_report import decrypt, encrypt

KEY, NONCE, PLAIN = bytes(range(32)), bytes(12), "Γειά <b>σου</b>".encode()
VECTOR = "000000000000000000000000c02f7b6b7b954d112894cb0bd7af5f261dc66a7c4abfb6e31775cc60c3988b031d8dca805ff8"

assert encrypt(KEY, PLAIN, NONCE).hex() == VECTOR
assert decrypt(KEY, bytes.fromhex(VECTOR)) == PLAIN
blob = encrypt(KEY, PLAIN)
assert blob[:12] != NONCE and decrypt(KEY, blob) == PLAIN
print("ok")
