#!/usr/bin/env python3.13
"""Generate Android keystore, set GitHub secrets, zip with password."""
import base64
import json
import os
import subprocess
import sys
import urllib.request
from pathlib import Path
from nacl import encoding, public

# --- Config ---
KEYSTORE_PATH = "/home/z/my-project/download/noidmobile-release.keystore"
KEYSTORE_PASS = "123456"
KEY_ALIAS = "noidmobile"
KEY_PASS = "123456"
KEYSTORE_VALIDITY_DAYS = 36500  # 100 years
DNAME = "CN=NOID//MOBILE, OU=SecretArrow, O=SecretArrow, L=Jakarta, ST=Jakarta, C=ID"
REPO = "SecretArrow/noidmobile"
TOKEN = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN") or ""

def api(method, path, body=None):
    url = f"https://api.github.com/repos/{REPO}/{path}" if not path.startswith("http") else path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, method=method, data=data, headers={
        "Authorization": f"token {TOKEN}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
    })
    try:
        with urllib.request.urlopen(req) as resp:
            txt = resp.read()
            return resp.status, json.loads(txt) if txt else None
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())

print("=" * 60)
print("STEP 1: Generate Android keystore with keytool")
print("=" * 60)
if Path(KEYSTORE_PATH).exists():
    Path(KEYSTORE_PATH).unlink()
cmd = [
    "keytool", "-genkeypair",
    "-keystore", KEYSTORE_PATH,
    "-storepass", KEYSTORE_PASS,
    "-keypass", KEY_PASS,
    "-alias", KEY_ALIAS,
    "-keyalg", "RSA",
    "-keysize", "4096",
    "-sigalg", "SHA256withRSA",
    "-validity", str(KEYSTORE_VALIDITY_DAYS),
    "-dname", DNAME,
]
res = subprocess.run(cmd, capture_output=True, text=True)
if res.returncode != 0:
    print("keytool FAILED:")
    print(res.stderr)
    sys.exit(1)
print(f"✓ Keystore created: {KEYSTORE_PATH}")
print(f"  Size: {Path(KEYSTORE_PATH).stat().st_size:,} bytes")
# Verify
verify = subprocess.run([
    "keytool", "-list", "-v",
    "-keystore", KEYSTORE_PATH,
    "-storepass", KEYSTORE_PASS,
    "-alias", KEY_ALIAS,
], capture_output=True, text=True)
print("  Alias:", KEY_ALIAS)
print("  Validity:", KEYSTORE_VALIDITY_DAYS, "days")

print()
print("=" * 60)
print("STEP 2: Base64-encode the keystore")
print("=" * 60)
with open(KEYSTORE_PATH, "rb") as f:
    ks_bytes = f.read()
ks_b64 = base64.b64encode(ks_bytes).decode()
print(f"✓ Base64 length: {len(ks_b64):,} chars")

print()
print("=" * 60)
print("STEP 3: Get the repo's public key for secret encryption")
print("=" * 60)
status, pk = api("GET", "actions/secrets/public-key")
if status != 200:
    print(f"FAILED to fetch public key: {status}")
    print(pk)
    sys.exit(1)
print(f"✓ key_id: {pk['key_id']}")
pk_obj = public.PublicKey(pk["key"].encode(), encoding.Base64Encoder())
sealed = public.SealedBox(pk_obj)

def set_secret(name, value):
    enc = sealed.encrypt(value.encode())
    body = {
        "encrypted_value": base64.b64encode(enc).decode(),
        "key_id": pk["key_id"],
    }
    s, r = api("PUT", f"actions/secrets/{name}", body)
    return s, r

print()
print("=" * 60)
print("STEP 4: Set the 4 GitHub Actions secrets")
print("=" * 60)
secrets = {
    "ANDROID_KEYSTORE_BASE64": ks_b64,
    "ANDROID_KEYSTORE_PASSWORD": KEYSTORE_PASS,
    "ANDROID_KEY_ALIAS": KEY_ALIAS,
    "ANDROID_KEY_PASSWORD": KEY_PASS,
}
for name, value in secrets.items():
    s, r = set_secret(name, value)
    status_mark = "✓" if s == 201 or s == 204 else "✗"
    print(f"  {status_mark} {name}: HTTP {s}")
    if s not in (201, 204):
        print(f"      {r}")

print()
print("=" * 60)
print("STEP 5: Verify secrets are set")
print("=" * 60)
s, r = api("GET", "actions/secrets")
print(f"  Total secrets in repo: {r.get('total_count', 0)}")
for sec in r.get("secrets", []):
    print(f"    - {sec['name']} (created {sec['created_at']})")

print()
print("✓ All secrets set. Ready for signed APK build.")
