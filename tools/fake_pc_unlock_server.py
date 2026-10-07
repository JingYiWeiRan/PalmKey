"""A protocol-accurate stand-in for the upstream PC-side `TCPUnlockServer`.

Why this exists: the Android client's TCP unlock handshake cannot be tested without a
PC running PC Bio Unlock. This script speaks the same wire protocol, so the handshake
can be verified on a real device in isolation -- and it reports each protocol
expectation explicitly, so a mismatch can never be mistaken for a pass.

It mirrors upstream (`common/src/connection/BaseConnection.cpp`,
`unlock/BaseUnlockConnection.cpp`, `utils/CryptUtils.cpp`):

    phone -> PC : PACKET_ID_DEVICE_ID      payload = PLAINTEXT device id (NOT encrypted)
    PC -> phone : PACKET_ID_UNLOCK_REQUEST payload = {protoVersion, deviceId, encData}
                                               encData = hex(IV|salt|AESGCM(...))
                                               inner  = {user, program, unlockToken}
    phone -> PC : PACKET_ID_UNLOCK_RESPONSE payload = {error, encData}
                                               inner  = {unlockToken, passwordKey}

The PC-generated `unlockToken` must be echoed back verbatim; building the request
packet's own token on the phone is a protocol error.

Usage (no secrets are stored in this file):

    1. Make the phone connect here. Simplest is the adb tunnel, which needs no Wi-Fi:
           adb reverse tcp:43296 tcp:43296
       then point the paired device's row at 127.0.0.1:43296.

    2. Read the pairing key from the device. NOTE: the app now encrypts it at rest, so
       for a current build you need it from before that change, or from a fresh pairing
       capture. On a debuggable build the old plaintext value could be read straight out
       of `databases/pcbu_database` (that is exactly the weakness the encryption fixes).

    3. Run:
           PCBU_ENCRYPTION_KEY=<the 64-char key> \
           PCBU_DEVICE_ID=<the device id> \
           python tools/fake_pc_unlock_server.py [port]

    Exit codes: 0 = all protocol checks passed, 1 = a check failed, 2 = nothing connected.
"""

import hashlib
import json
import os
import random
import socket
import string
import struct
import sys
import time

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = 0xDB065AC7AFDFA4CC
ID_PAIR_INIT = 0x50
ID_PAIR_RESPONSE = 0x51
ID_DEVICE_ID = 0xB0
ID_UNLOCK_REQUEST = 0xB1
ID_UNLOCK_RESPONSE = 0xB2

PAIRING_PROTOCOL_VERSION = "3.0.0"  # upstream AppInfo::GetPairingProtocolVersion()
DEFAULT_PORT = 43296  # upstream AppSettings default for unlockServerPort
PBKDF2_ITERATIONS = 65535
IV_LEN = 16
SALT_LEN = 16
TAG_LEN = 16
ACCEPT_TIMEOUT_S = 180

findings = []


def note(ok: bool, text: str) -> None:
    findings.append((ok, text))
    print(("  PASS  " if ok else "  FAIL  ") + text, flush=True)


def recv_exactly(conn: socket.socket, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            raise EOFError(f"connection closed after {len(buf)}/{n} bytes")
        buf += chunk
    return buf


def read_packet(conn: socket.socket):
    magic, packet_id, length = struct.unpack(">QHH", recv_exactly(conn, 12))
    if magic != MAGIC:
        raise ValueError(f"bad magic: 0x{magic:016X} (expected 0x{MAGIC:016X})")
    return packet_id, (recv_exactly(conn, length) if length else b"")


def write_packet(conn: socket.socket, packet_id: int, payload: bytes) -> None:
    conn.sendall(struct.pack(">QHH", MAGIC, packet_id, len(payload)) + payload)


def derive_key(password: str, salt: bytes) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt,
                               PBKDF2_ITERATIONS, dklen=32)


def encrypt_packet(data: bytes, password: str) -> bytes:
    iv, salt = os.urandom(IV_LEN), os.urandom(SALT_LEN)
    timestamp = struct.pack(">q", int(time.time() * 1000))
    return iv + salt + AESGCM(derive_key(password, salt)).encrypt(iv, timestamp + data, None)


def decrypt_packet(blob: bytes, password: str) -> bytes:
    if len(blob) < IV_LEN + SALT_LEN + TAG_LEN:
        raise ValueError(f"envelope too short: {len(blob)}")
    iv, salt, body = blob[:IV_LEN], blob[IV_LEN:IV_LEN + SALT_LEN], blob[IV_LEN + SALT_LEN:]
    plain = AESGCM(derive_key(password, salt)).decrypt(iv, body, None)
    skew = abs(int(time.time() * 1000) - struct.unpack(">q", plain[:8])[0])
    if skew > 120_000:  # upstream CRYPT_PACKET_TIMEOUT = 60000 * 2
        raise ValueError(f"timestamp skew {skew}ms exceeds upstream tolerance")
    return plain[8:]


def handle_pairing(conn: socket.socket, payload: bytes, key: str, port: int) -> None:
    """PAIR_INIT -> PAIR_RESPONSE.

    Mirrors upstream `common/src/connection/pairing/PairingServer.cpp` (lines 150-190):
    the request is an encrypted `PacketPairInit`, the reply an encrypted `PacketPairResponse`
    whose `data` carries deviceId / deviceName / userName / passwordKey / pairingMethod / port.

    Note on the encryption shape: unlike the unlock flow (which wraps the ciphertext in a
    JSON field `encData` as hex), the pairing flow puts the RAW envelope straight into the
    packet payload. The app decrypts `response.second` directly, so this must not hex-wrap it.

    Upstream derives `deviceId = Sha256(machineID + deviceUUID + userName)` on the PC side.
    A stand-in has no such machine identity, so it returns a fixed id from
    `PCBU_PAIR_DEVICE_ID` -- which is also what makes this useful: it can reproduce a
    specific pairing (and therefore a specific device id) on demand.
    """
    init = json.loads(decrypt_packet(payload, key).decode("utf-8"))
    note(init.get("protoVersion") == PAIRING_PROTOCOL_VERSION,
         f"PAIR_INIT protoVersion = {init.get('protoVersion')!r} "
         f"(expected {PAIRING_PROTOCOL_VERSION!r})")
    note(bool(init.get("deviceUUID")), "PAIR_INIT carries a deviceUUID")
    print(f"  pair request: deviceName={init.get('deviceName')!r} udpPort={init.get('udpPort')}", flush=True)

    device_id = os.environ.get("PCBU_PAIR_DEVICE_ID", "").strip()
    if not device_id:
        note(False, "PCBU_PAIR_DEVICE_ID is not set - cannot decide the device id to return")
        return

    # upstream hands out a fresh random 64-char password key per pairing
    password_key = "".join(random.choice(string.ascii_letters + string.digits) for _ in range(64))
    response = {
        "data": {
            "deviceId": device_id,
            "deviceName": os.environ.get("PCBU_PAIR_DEVICE_NAME", "FAKE-PC"),
            "deviceOS": "Linux",
            "ipAddress": "127.0.0.1",
            "port": port,
            # "UDP" so the app routes later unlocks through the TCP unlock server
            # (PairingMethods.usesTcpServer). A bluetooth pairing would never come back here.
            "pairingMethod": "UDP",
            "macAddress": "",
            "userName": os.environ.get("PCBU_PAIR_USER_NAME", "test"),
            "passwordKey": password_key,
        }
    }
    write_packet(conn, ID_PAIR_RESPONSE, encrypt_packet(json.dumps(response).encode("utf-8"), key))
    note(True, f"sent PAIR_RESPONSE for device id {device_id}")


def handle_unlock(conn: socket.socket, packet_id: int, payload: bytes, key: str,
                  device_id_expected: str, token: str) -> None:
    """DEVICE_ID -> UNLOCK_REQUEST -> UNLOCK_RESPONSE (the original flow)."""
    # 1. phone sends the plaintext device id
    note(packet_id == ID_DEVICE_ID,
         f"DEVICE_ID packet id = 0x{packet_id:04X} (expected 0x{ID_DEVICE_ID:04X})")
    got_id = payload.decode("utf-8", "replace")
    note(got_id == device_id_expected,
         f"DEVICE_ID payload is the PLAINTEXT device id (got {got_id!r})")
    # No "looks encrypted" heuristic: the device id is itself a 64-char hex digest,
    # so such a heuristic fires on the correct payload. The exact match above is the test.

    # 2. PC sends the unlock request carrying a PC-generated token
    inner = json.dumps({"user": "test", "program": "", "unlockToken": token})
    request = json.dumps({
        "protoVersion": "3.0.0",
        "deviceId": device_id_expected,
        "encData": encrypt_packet(inner.encode("utf-8"), key).hex().upper(),
    })
    write_packet(conn, ID_UNLOCK_REQUEST, request.encode("utf-8"))
    print("sent UNLOCK_REQUEST with a PC-generated token", flush=True)

    # 3. phone replies with the echoed token
    packet_id, payload = read_packet(conn)
    note(packet_id == ID_UNLOCK_RESPONSE,
         f"UNLOCK_RESPONSE packet id = 0x{packet_id:04X} (expected 0x{ID_UNLOCK_RESPONSE:04X})")

    response = json.loads(payload.decode("utf-8"))
    note(response.get("error", "") == "",
         f"response error field is empty (got {response.get('error')!r})")

    enc_hex = response.get("encData", "")
    note(bool(enc_hex), "response carries encData")
    if enc_hex:
        data = json.loads(decrypt_packet(bytes.fromhex(enc_hex), key).decode("utf-8"))
        note(data.get("unlockToken") == token,
             "echoed unlockToken matches the token the PC generated")
        note("passwordKey" in data,
             "passwordKey present (upstream requires the key to exist)")


def main() -> int:
    key = os.environ.get("PCBU_ENCRYPTION_KEY", "").strip()
    device_id_expected = os.environ.get("PCBU_DEVICE_ID", "").strip()
    if not key:
        print(__doc__)
        print("ERROR: set PCBU_ENCRYPTION_KEY", flush=True)
        return 2
    # 配对流程由 PCBU_PAIR_DEVICE_ID 决定回什么设备 ID，不需要 PCBU_DEVICE_ID；
    # 解锁流程才必须知道期望的设备 ID（用来校验手机发来的是明文 device id）。
    if not device_id_expected and not os.environ.get("PCBU_PAIR_DEVICE_ID", "").strip():
        print(__doc__)
        print("ERROR: set PCBU_DEVICE_ID (unlock flow) or PCBU_PAIR_DEVICE_ID (pairing flow)",
              flush=True)
        return 2

    port = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PORT
    token = "".join(random.choice("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")
                    for _ in range(64))

    print(f"fake TCPUnlockServer listening on 0.0.0.0:{port}", flush=True)
    # 按配置说清楚这个实例能服务哪个流程：只配了 PCBU_DEVICE_ID 就只认解锁，
    # 只配了 PCBU_PAIR_DEVICE_ID 就只认配对。以前无条件打印 "expecting device id"，
    # 配对模式下那行是空的，会让人以为配置没生效。
    if device_id_expected:
        print(f"unlock flow: expecting device id {device_id_expected}", flush=True)
    pair_dev_id = os.environ.get("PCBU_PAIR_DEVICE_ID", "").strip()
    if pair_dev_id:
        print(f"pairing flow: will hand out device id {pair_dev_id}", flush=True)

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", port))
    server.listen(1)
    server.settimeout(ACCEPT_TIMEOUT_S)

    try:
        conn, peer = server.accept()
    except TimeoutError:
        print(f"RESULT: nothing connected within {ACCEPT_TIMEOUT_S}s - the app never reached this server",
              flush=True)
        server.close()
        return 2

    conn.settimeout(30)
    print(f"connection from {peer[0]}:{peer[1]}", flush=True)

    try:
        # The first packet tells us which flow this connection is:
        #   PAIR_INIT (0x50) -> pairing, then the app closes
        #   DEVICE_ID (0xB0) -> unlock handshake
        first_id, first_payload = read_packet(conn)
        if first_id == ID_PAIR_INIT:
            handle_pairing(conn, first_payload, key, port)
        elif first_id == ID_DEVICE_ID:
            handle_unlock(conn, first_id, first_payload, key, device_id_expected, token)
        else:
            note(False, f"unexpected first packet id 0x{first_id:04X} "
                        f"(expected PAIR_INIT 0x50 or DEVICE_ID 0xB0)")
    except Exception as exc:  # noqa: BLE001 - surface anything unexpected
        note(False, f"exception during handshake: {type(exc).__name__}: {exc}")
    finally:
        conn.close()
        server.close()

    failed = [f for ok, f in findings if not ok]
    print(flush=True)
    print(f"RESULT: {len(findings) - len(failed)}/{len(findings)} checks passed", flush=True)
    if failed:
        print("FAILED CHECKS:", flush=True)
        for f in failed:
            print(f"  - {f}", flush=True)
        return 1
    print("ALL CHECKS PASSED: the TCP unlock handshake matches upstream", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
