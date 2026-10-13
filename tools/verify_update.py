#!/usr/bin/env python3
"""update.json imzasini dogrular. Uygulamadaki ayni mantik (Kotlin) ile birebir ayni."""
import argparse
import base64
import json
import sys

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

CANON = "gizli-update-v1"


def canonical_payload(code, name, sha, url, min_sdk, notes):
    return ("%s\n%d\n%s\n%s\n%s\n%d\n%s" % (CANON, code, name, sha, url, min_sdk, notes)).encode()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--update-json", required=True)
    ap.add_argument("--public-key", default=None)
    args = ap.parse_args()

    doc = json.load(open(args.update_json, encoding="utf-8"))
    pub_path = args.public_key
    if not pub_path:
        import os
        pub_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "update_key_public.b64")
    pub = Ed25519PublicKey.from_public_bytes(base64.b64decode(open(pub_path, "rb").read().strip()))

    payload = canonical_payload(
        doc["versionCode"], doc["versionName"], doc["sha256"], doc["apkUrl"], doc["minSdk"], doc["notes"]
    )
    sig = base64.b64decode(doc["signature"])
    try:
        pub.verify(sig, payload)
        print("IMZA GECERLI. version:", doc["versionName"], "sha256:", doc["sha256"])
    except Exception:
        print("IMZA GECERSIZ!")
        sys.exit(1)


if __name__ == "__main__":
    main()