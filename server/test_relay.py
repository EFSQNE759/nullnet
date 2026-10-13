#!/usr/bin/env python3
"""Relay sunucusunun uçtan uca testi (aynı kod, süreç içi)."""
import json
import os
import sys
import tempfile
import threading
import time
import urllib.request
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relay

SAMPLE = {
    "to": "ABCDEFGHIJKLMNOPK7XQ",
    "id": "mid-1",
    "ts": 1727000000000,
    "spk": "c2VuZGVyLXBr",
    "epk": "ZXBoZW1lcmFsLXBr",
    "s": "c2FsdA==",
    "n": "bm9uY2UtMTI=",
    "c": "Y2lwaGVydGV4dC1jcm9wcGVk",
}


def http_json(method, url, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


def main():
    tmp = tempfile.mktemp(suffix=".db")
    relay.init_db(tmp)
    srv = ThreadingHTTPServer(("127.0.0.1", 0), relay.Handler)
    thread = threading.Thread(target=srv.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{srv.server_address[1]}"

    # health
    st, h = http_json("GET", base + "/health")
    assert st == 200 and h["ok"]

    # send
    st, r = http_json("POST", base + "/send", SAMPLE)
    assert st == 200 and r["ok"]

    # pull for the intended user -> 1 msg, then empty (deleted)
    st, msgs = http_json("GET", base + f"/pull?u={SAMPLE['to']}")
    assert st == 200 and len(msgs) == 1 and msgs[0]["id"] == SAMPLE["id"]
    st, msgs2 = http_json("GET", base + f"/pull?u={SAMPLE['to']}")
    assert st == 200 and msgs2 == []

    # different user should see nothing (hedefe yonlendirme)
    st, msgs3 = http_json("GET", base + "/pull?u=OTHERUSER")
    assert st == 200 and msgs3 == []

    # invalid payload rejected
    bad = dict(SAMPLE); bad.pop("c")
    st, _ = http_json("POST", base + "/send", bad)
    assert st == 400

    print("RELAY TEST: ALL PASS")
    srv.shutdown()


if __name__ == "__main__":
    main()