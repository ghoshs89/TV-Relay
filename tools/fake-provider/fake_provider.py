#!/usr/bin/env python3
"""A made-up Xtream Codes provider for testing Relay on an emulator. No real provider data.

Serves a generated library (channels, movies, series, guide, small images) and, if given an
MPEG-TS file, plays it as every live channel. Used by .github/bench.sh; also fine to run by hand:

    python3 tools/fake-provider/fake_provider.py --port 8099 --ts test.ts

Sign in from Relay with host http://<this machine>:8099, user "bench", password "bench".
"""
import argparse
import base64
import json
import random
import struct
import threading
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

COUNTRIES = ["US", "UK", "IN", "BD", "CA", "ES", "FR", "DE", "PK", "AU"]
LANGS = ["EN", "HI", "BN", "TA", "ES", "FR", "DE", "UR"]
TOPICS = ["News", "Sports", "Movies", "Kids", "Music", "Entertainment", "Documentary", "Regional"]
GENRES = ["Action", "Comedy", "Drama", "Thriller", "Family", "Romance", "Horror", "Sci-Fi", "Animation", "Crime"]
WORDS = ("Silent River Golden Harbor Midnight Garden Broken Compass Winter Light Hidden Valley Red Horizon "
         "Paper Moon Electric Dreams Last Train Burning Sky Quiet Storm Iron Crown Lost Island Velvet Night "
         "Crimson Tide Distant Shore Falling Stars Wild Road Secret Orchard Glass City Northern Wind").split()
QUALITY = ["", " HD", " FHD", " 4K", " HEVC"]
STYLES = ["{c}: ", "{c}| ", "{c} - "]


def build(channels, movies, series, seed=7):
    rnd = random.Random(seed)
    live_cats, vod_cats, series_cats = [], [], []
    for i, c in enumerate(COUNTRIES):
        for j, t in enumerate(TOPICS):
            live_cats.append({"category_id": str(i * 10 + j + 1), "category_name": f"{c}| {t}"})
    for i, l in enumerate(LANGS):
        for j, g in enumerate(GENRES):
            vod_cats.append({"category_id": str(i * 100 + j + 1), "category_name": f"{l} MOVIES {g}"})
            series_cats.append({"category_id": str(i * 100 + j + 1), "category_name": f"{l} SERIES {g}"})

    live = []
    base = None
    for n in range(1, channels + 1):
        cat = rnd.choice(live_cats)
        country = cat["category_name"].split("|")[0]
        if base is not None and rnd.random() < 0.25:
            name = base  # another version of the same channel (HD, FHD, backup)
        else:
            base = f"{rnd.choice(STYLES).format(c=country)}{rnd.choice(WORDS)} {rnd.choice(WORDS)} {rnd.randint(1, 99)}"
            name = base
        live.append({
            "num": n, "name": name + rnd.choice(QUALITY), "stream_id": n, "stream_icon": f"@IMG@/img/logo/{n % 24}.png",
            "epg_channel_id": f"ch{n}", "category_id": cat["category_id"],
            "tv_archive": 1 if rnd.random() < 0.2 else 0, "tv_archive_duration": "3" if rnd.random() < 0.2 else "0",
        })

    vod = []
    for n in range(1, movies + 1):
        cat = rnd.choice(vod_cats)
        lang = cat["category_name"].split(" ")[0]
        year = rnd.randint(1985, 2026)
        vod.append({
            "name": f"{lang} - {rnd.choice(WORDS)} {rnd.choice(WORDS)} {n} ({year})", "stream_id": 100000 + n,
            "stream_icon": f"@IMG@/img/poster/{n % 24}.png", "rating": f"{rnd.uniform(3, 9):.1f}",
            "category_id": cat["category_id"], "container_extension": "mp4",
            "added": str(1_600_000_000 + rnd.randint(0, 150_000_000)), "tmdb": str(rnd.randint(1000, 999999)), "year": str(year),
        })

    ser = []
    for n in range(1, series + 1):
        cat = rnd.choice(series_cats)
        lang = cat["category_name"].split(" ")[0]
        year = rnd.randint(1995, 2026)
        ser.append({
            "name": f"{lang} - {rnd.choice(WORDS)} {rnd.choice(WORDS)} S{n} ({year})", "series_id": 200000 + n,
            "cover": f"@IMG@/img/poster/{n % 24}.png", "rating": f"{rnd.uniform(3, 9):.1f}",
            "category_id": cat["category_id"], "releaseDate": f"{year}-01-01",
            "last_modified": str(1_600_000_000 + rnd.randint(0, 150_000_000)), "tmdb": str(rnd.randint(1000, 999999)),
        })
    return {
        "get_live_categories": live_cats, "get_live_streams": live,
        "get_vod_categories": vod_cats, "get_vod_streams": vod,
        "get_series_categories": series_cats, "get_series": ser,
    }


def png(w, h, shade):
    rows = bytearray()
    for y in range(h):
        rows.append(0)
        for x in range(w):
            rows += bytes(((shade * 10 + y * 120 // h) % 256, (shade * 7 + x * 120 // w) % 256, (shade * 13) % 256))
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) +
            chunk(b"IDAT", zlib.compress(bytes(rows), 6)) + chunk(b"IEND", b""))


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    data = {}
    blobs = {}
    ts_path = None
    ts_rate = 250_000  # bytes per second the stream is paced at

    def log_message(self, *a):
        pass

    def send_bytes(self, body, ctype):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        host = "http://" + self.headers.get("Host", "10.0.2.2:8099")
        try:
            if u.path == "/player_api.php":
                self.api(q, host)
            elif u.path.startswith("/img/"):
                kind, n = u.path.split("/")[2], int(u.path.split("/")[3].split(".")[0])
                key = (kind, n)
                if key not in self.blobs:
                    self.blobs[key] = png(128, 80, n) if kind == "logo" else png(300, 450, n)
                self.send_bytes(self.blobs[key], "image/png")
            elif u.path.split("/")[1] in ("live", "movie", "series", "timeshift"):
                self.stream()
            else:
                self.send_error(404)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def api(self, q, host):
        action = q.get("action")
        if action is None:
            body = {"user_info": {"auth": 1, "status": "Active", "exp_date": "4102444800", "max_connections": "1", "active_cons": "0"},
                    "server_info": {"timezone": "UTC"}}
        elif action in self.data:
            text = json.dumps(self.data[action]).replace("@IMG@", host)
            return self.send_bytes(text.encode(), "application/json")
        elif action in ("get_short_epg", "get_simple_data_table"):
            now = int(time.time()) // 1800 * 1800
            n = 10 if action == "get_short_epg" else 48
            first = now - (0 if action == "get_short_epg" else 24 * 1800)
            sid = q.get("stream_id", "0")
            body = {"epg_listings": [{
                "start_timestamp": str(first + i * 1800), "stop_timestamp": str(first + (i + 1) * 1800),
                "title": base64.b64encode(f"Programme {sid}-{i}".encode()).decode(),
                "description": base64.b64encode(b"A made-up programme for testing.").decode(),
                "has_archive": 1,
            } for i in range(n)]}
        elif action == "get_vod_info":
            body = {"info": {"plot": "A made-up film.", "genre": "Drama", "duration_secs": 5400, "rating": "7.0"},
                    "movie_data": {"container_extension": "mp4"}}
        elif action == "get_series_info":
            sid = int(q.get("series_id", "0"))
            body = {"info": {"plot": "A made-up series.", "genre": "Drama", "rating": "7.0"},
                    "episodes": {"1": [{"id": str(sid * 100 + e), "episode_num": e, "title": f"Episode {e}", "season": 1,
                                        "container_extension": "mp4", "info": {"duration_secs": 2700}} for e in range(1, 9)]}}
        else:
            body = []
        self.send_bytes(json.dumps(body).encode(), "application/json")

    def stream(self):
        if not self.ts_path:
            return self.send_error(404)
        self.send_response(200)
        self.send_header("Content-Type", "video/mp2t")
        self.send_header("Connection", "close")
        self.end_headers()
        chunk = 188 * 400
        with open(self.ts_path, "rb") as f:
            while True:
                b = f.read(chunk)
                if not b:
                    break
                self.wfile.write(b)
                time.sleep(len(b) / self.ts_rate)
        self.close_connection = True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8099)
    ap.add_argument("--ts", help="MPEG-TS file played as every live channel")
    ap.add_argument("--ts-rate", type=int, default=250_000, help="bytes per second to send the stream at")
    ap.add_argument("--channels", type=int, default=12_000)
    ap.add_argument("--movies", type=int, default=40_000)
    ap.add_argument("--series", type=int, default=8_000)
    a = ap.parse_args()
    Handler.data = build(a.channels, a.movies, a.series)
    Handler.ts_path = a.ts
    Handler.ts_rate = a.ts_rate
    srv = ThreadingHTTPServer(("0.0.0.0", a.port), Handler)
    srv.daemon_threads = True
    print(f"Fake provider on port {a.port}: {a.channels} channels, {a.movies} movies, {a.series} series", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
