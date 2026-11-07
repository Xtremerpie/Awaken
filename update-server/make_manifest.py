#!/usr/bin/env python3
"""Fill in size, SHA-256 and signer fingerprint of a release APK and print a manifest.

usage: make_manifest.py app-release.apk --version-code 2 --version-name 1.1.0 \
         --url https://updates.example.com/awaken/awaken-1.1.0.apk [--mandatory] [--min-supported 1] \
         [--changelog "Line one" --changelog "Line two"] [--release-date 2026-12-01]

The signer fingerprint is read with `apksigner verify --print-certs` (Android SDK build-tools) and must
match the certificate that signed the version users already have installed.
"""
import argparse, hashlib, json, os, re, shutil, subprocess, sys, datetime

ap = argparse.ArgumentParser()
ap.add_argument("apk")
ap.add_argument("--version-code", type=int, required=True)
ap.add_argument("--version-name", required=True)
ap.add_argument("--url", required=True)
ap.add_argument("--package", default="com.awaken.app")
ap.add_argument("--min-supported", type=int, default=0)
ap.add_argument("--mandatory", action="store_true")
ap.add_argument("--changelog", action="append", default=[])
ap.add_argument("--release-date", default=datetime.date.today().isoformat())
a = ap.parse_args()

if not a.url.startswith("https://"):
    sys.exit("The APK URL must be HTTPS.")
h = hashlib.sha256()
with open(a.apk, "rb") as f:
    for chunk in iter(lambda: f.read(1 << 20), b""):
        h.update(chunk)

signers = []
tool = shutil.which("apksigner")
if tool:
    out = subprocess.run([tool, "verify", "--print-certs", a.apk], capture_output=True, text=True).stdout
    signers = sorted(set(re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", out)))
if not signers:
    print("warning: apksigner not found or APK unsigned; signerSha256 left empty (the app still checks the "
          "installed signer itself).", file=sys.stderr)

print(json.dumps({
    "schemaVersion": 1, "packageName": a.package, "versionCode": a.version_code, "versionName": a.version_name,
    "minSupportedVersionCode": a.min_supported, "mandatory": a.mandatory, "releaseDate": a.release_date,
    "sizeBytes": os.path.getsize(a.apk), "sha256": h.hexdigest(), "apkUrl": a.url,
    "signerSha256": signers, "changelog": a.changelog,
}, indent=2))
