#!/usr/bin/env python3
"""
Gizli - İmzalı güncelleme bildirimi (update.json) üretici.

Bu araç, yayınladığın APK için SHA-256 özetini hesaplar ve
gizli Ed25519 anahtarınla imzalar. Uygulama, içindeki açık anahtarla
imzayı doğrular; böylece SCAM/oynanyan bir sunucu bile sahte
güncelleme YAYINLAYAMAZ.

Kullanım (her yeni sürümde):
    python tools/make_update.py --apk app/build/outputs/apk/release/app-release.apk ^
        --url https://github.com/SENIN/REPO/releases/latest/download/app-release.apk ^
        --version-code 2 --version-name 1.1.0 --notes "Ilk hata duzeltmesi" ^
        --out update.json

Ortaya çıkan update.json'ı GitHub Release'inize dosya olarak ekleyin.
Uygulamanın güncelleme adresi ayarlardaki build.gradle.kts'ten gelir.

GİZLİ ANAHTAR (update_key_private.b64) KİMSEYLE PAYLAŞILMAZ. GitHub Action
bunu secret olarak kullanır. Açık anahtar(public key) uygulamaya gömülüdür.
"""
import argparse
import base64
import hashlib
import json
import os

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

CANON = "gizli-update-v1"


def canonical_payload(code, name, sha, url, min_sdk, notes):
    return ("%s\n%d\n%s\n%s\n%s\n%d\n%s" % (CANON, code, name, sha, url, min_sdk, notes)).encode()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True, help="release APK yolu")
    ap.add_argument("--url", required=True, help="APK'nın indirileceği tam URL")
    ap.add_argument("--version-code", type=int, required=True)
    ap.add_argument("--version-name", required=True)
    ap.add_argument("--min-sdk", type=int, default=26)
    ap.add_argument("--notes", default="")
    ap.add_argument("--out", default="update.json")
    ap.add_argument("--key", default=None, help="ozel anahtar dosyasi (varsayilan tools/update_key_private.b64)")
    args = ap.parse_args()

    sha = hashlib.sha256(open(args.apk, "rb").read()).hexdigest()

    key_path = args.key or os.path.join(os.path.dirname(os.path.abspath(__file__)), "update_key_private.b64")
    if not os.path.exists(key_path):
        raise SystemExit(f"Ozel anahtar bulunamadi: {key_path}")
    priv_raw = base64.b64decode(open(key_path, "rb").read().strip())
    priv = Ed25519PrivateKey.from_private_bytes(priv_raw)

    payload = canonical_payload(args.version_code, args.version_name, sha, args.url, args.min_sdk, args.notes)
    sig = base64.b64encode(priv.sign(payload)).decode()

    doc = {
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "apkUrl": args.url,
        "sha256": sha,
        "minSdk": args.min_sdk,
        "notes": args.notes,
        "signature": sig,
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
    print("update.json yazildi:", args.out)
    print("  version :", args.version_name, "(kod", args.version_code, ")")
    print("  sha256  :", sha)
    print("  url     :", args.url)


if __name__ == "__main__":
    main()