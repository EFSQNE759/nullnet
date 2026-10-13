#!/usr/bin/env python3
"""
Gizli - Şifreli Mesaj Aktarma Sunucusu (Relay)

Bu sunucu YALNIZCA şifreli zarfları saklar ve iletir.
İçeriği GÖREMEZ, ÇÖZEMEZ. Mesajlar UÇTAN UCA şifrelidir.

Sunucu, mesajları alıcı tarafından çekilene kadar saklar; çekilince siler.
Abonelik/hesap YOKTUR -> kimlik tamamen anonimdir (rastgele kullanıcı kodu).

Başlatma:
    python relay.py                     # 0.0.0.0:8787
    python relay.py --port 9000 -b 127.0.0.1
    python relay.py --cert fullchain.pem --key privkey.pem   # direkt TLS

Güvenlik notu: HTTPS şarttır. Ücretsiz TLS için sunucunun önüne
Caddy/nginx koyun ya da yukarıdaki --cert/--key ile direkt TLS kullanın.

Uç noktalar:
    POST /send    {"to":..,"id":..,"ts":..,"spk":..,"epk":..,"s":..,"n":..,"c":..}
    GET  /pull?u=<kullanici kodi>   -> [ {zarf}, ... ]  (çekilince silinir)
    GET  /health
"""
import argparse
import json
import logging
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

logging.basicConfig(level=logging.INFO, format="%(asctime)s relay %(levelname)s %(message)s")
log = logging.getLogger("relay")

MAX_BODY = 2 * 1024 * 1024  # 2 MB zarf üst sınırı (medya ileride ayrı kanaldan)
TTL_SECONDS = 30 * 24 * 3600  # 30 gün sonra eski mesajlar temizlenir

DB = None
DB_LOCK = threading.Lock()
RATE = {}  # ip -> list of timestamps
RATE_LIMIT = {"send": (30, 60), "pull": (120, 60)}  # (sayı, saniye)


def init_db(path):
    global DB
    DB = sqlite3.connect(path, check_same_thread=False)
    DB.execute(
        "CREATE TABLE IF NOT EXISTS msgs (id TEXT, to_user TEXT, payload TEXT, ts INTEGER)"
    )
    DB.execute("CREATE INDEX IF NOT EXISTS idx_to ON msgs(to_user)")
    DB.commit()


def store(to_user, payload, ts):
    _purge()
    with DB_LOCK:
        DB.execute(
            "INSERT INTO msgs (id, to_user, payload, ts) VALUES (?,?,?,?)",
            (payload.get("id", ""), to_user, json.dumps(payload, ensure_ascii=False), ts),
        )
        DB.commit()


def fetch_and_delete(to_user):
    with DB_LOCK:
        rows = DB.execute(
            "SELECT id, payload FROM msgs WHERE to_user=? ORDER BY ts", (to_user,)
        ).fetchall()
        if rows:
            ids = [r[0] for r in rows]
            placeholders = ",".join("?" for _ in ids)
            DB.execute(f"DELETE FROM msgs WHERE id IN ({placeholders})", ids)
            DB.commit()
    return [json.loads(r[1]) for r in rows]


def _purge():
    try:
        with DB_LOCK:
            DB.execute("DELETE FROM msgs WHERE ts < ?", (int(time.time()) - TTL_SECONDS,))
            DB.commit()
    except Exception:
        pass


def allowed(ip, kind):
    limit, window = RATE_LIMIT[kind]
    now = time.time()
    with threading.Lock():
        bucket = RATE.setdefault(ip, [])
        bucket[:] = [t for t in bucket if now - t < window]
        if len(bucket) >= limit:
            return False
        bucket.append(now)
        return True


class Handler(BaseHTTPRequestHandler):
    server_version = "GizliRelay/1.0"

    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _client_ip(self):
        x = self.headers.get("X-Forwarded-For")
        if x:
            return x.split(",")[0].strip()
        return self.client_address[0]

    def do_GET(self):
        ip = self._client_ip()
        path = urlparse(self.path).path
        if path == "/health":
            self._json(200, {"ok": True, "time": int(time.time())})
            return
        if path == "/pull":
            if not allowed(ip, "pull"):
                self._json(429, {"error": "rate_limited"})
                return
            u = parse_qs(urlparse(self.path).query).get("u", [""])[0]
            if not u:
                self._json(400, {"error": "u gerekli"})
                return
            msgs = fetch_and_delete(u)
            self._json(200, msgs)
            return
        self._json(404, {"error": "not_found"})

    def do_POST(self):
        ip = self._client_ip()
        if urlparse(self.path).path != "/send":
            self._json(404, {"error": "not_found"})
            return
        if not allowed(ip, "send"):
            self._json(429, {"error": "rate_limited"})
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            if length <= 0 or length > MAX_BODY:
                self._json(413, {"error": "too_large"})
                return
            body = self.rfile.read(length)
            data = json.loads(body.decode("utf-8"))
            to_user = str(data.get("to", "")).strip()
            for key in ("spk", "epk", "s", "n", "c"):
                if not isinstance(data.get(key), str) or not data[key]:
                    self._json(400, {"error": f"eksik alan: {key}"})
                    return
            if not to_user:
                self._json(400, {"error": "to gerekli"})
                return
            store(to_user, data, int(time.time()))
            log.info("kabul: alici_kodu_uzunluk=%d", len(to_user))
            self._json(200, {"ok": True})
        except Exception as e:
            log.warning("kabul hatasi: %s", e)
            self._json(400, {"error": "json gecersiz"})

    def log_message(self, fmt, *args):
        log.info("%s %s", self.address_string(), fmt % args)


def main():
    ap = argparse.ArgumentParser(description="Gizli relay sunucusu")
    ap.add_argument("-b", "--bind", default="0.0.0.0")
    ap.add_argument("-p", "--port", type=int, default=8787)
    ap.add_argument("--db", default="relay.db")
    ap.add_argument("--cert", default=None, help="TLS sertifika (fullchain.pem)")
    ap.add_argument("--key", default=None, help="TLS özel anahtar (privkey.pem)")
    args = ap.parse_args()

    init_db(args.db)
    httpd = ThreadingHTTPServer((args.bind, args.port), Handler)
    if args.cert and args.key:
        import ssl

        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(args.cert, args.key)
        httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
        log.info("TLS etkin (%s)", args.cert)
    log.info("Gizli relay dinliyor: http://%s:%d  (veritabani: %s)", args.bind, args.port, args.db)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()