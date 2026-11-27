import assert from "node:assert/strict";
import test from "node:test";
import worker from "../src/index.js";

function fakeDb() {
  const installs = new Map();
  const daily = new Map();
  const reports = new Map();

  function stmt(sql) {
    let args = [];
    const api = {
      bind(...v) { args = v; return api; },
      async first() {
        if (sql.includes("COUNT(*) AS total_screens")) {
          const [cut24, cut7] = args;
          let active_24h = 0; let active_7d = 0;
          for (const row of installs.values()) {
            if (row.last_seen >= cut24) active_24h++;
            if (row.last_seen >= cut7) active_7d++;
          }
          return { total_screens: installs.size, active_24h, active_7d };
        }
        if (sql.includes("COALESCE(SUM(app_sec)")) {
          let app_sec = 0, live_sec = 0, vod_sec = 0, crashes = 0;
          for (const row of daily.values()) {
            app_sec += row.app_sec; live_sec += row.live_sec; vod_sec += row.vod_sec; crashes += row.crashes;
          }
          return { app_sec, live_sec, vod_sec, crashes };
        }
        if (sql.includes("FROM reports WHERE code")) {
          return reports.get(args[0]) || null;
        }
        return null;
      },
      async all() {
        if (sql.includes("FROM installs i")) {
          const results = [];
          for (const ins of installs.values()) {
            let total_live = 0, total_vod = 0, crashes = 0;
            for (const u of daily.values()) {
              if (u.install_id === ins.install_id) {
                total_live += u.live_sec; total_vod += u.vod_sec; crashes += u.crashes;
              }
            }
            results.push({ ...ins, total_live, total_vod, week_watch: total_live + total_vod, today_watch: total_live + total_vod, crashes });
          }
          return { results };
        }
        if (sql.includes("FROM reports")) {
          const rows = Array.from(reports.values()).sort((a, b) => b.created_at - a.created_at);
          return { results: args.length ? rows.filter((r) => r.code === args[0]) : rows };
        }
        return { results: [] };
      },
      exec() {
        if (sql.startsWith("INSERT INTO installs")) {
          const [install_id, device_type, device_model, android_sdk, app_version, version_code, now] = args;
          const prev = installs.get(install_id);
          installs.set(install_id, {
            install_id, device_type, device_model, android_sdk, app_version, version_code,
            first_seen: prev ? prev.first_seen : now,
            last_seen: Math.max(prev ? prev.last_seen : 0, now),
          });
        } else if (sql.startsWith("INSERT INTO daily_usage")) {
          const [install_id, day, sessions, app_sec, live_sec, vod_sec, crashes, updated_at] = args;
          const k = `${install_id}|${day}`;
          const prev = daily.get(k) || { install_id, day, sessions: 0, app_sec: 0, live_sec: 0, vod_sec: 0, crashes: 0 };
          daily.set(k, {
            install_id, day,
            sessions: Math.max(prev.sessions, sessions),
            app_sec: Math.max(prev.app_sec, app_sec),
            live_sec: Math.max(prev.live_sec, live_sec),
            vod_sec: Math.max(prev.vod_sec, vod_sec),
            crashes: Math.max(prev.crashes, crashes),
            updated_at,
          });
        } else if (sql.startsWith("INSERT INTO reports")) {
          const [code, created_at, install_id, category, note, app_version, version_code, device_type, device_model, has_crash, log_text] = args;
          reports.set(code, { code, created_at, install_id, category, note, app_version, version_code, device_type, device_model, has_crash, log_text });
        }
      },
    };
    return api;
  }

  return {
    prepare(sql) { return stmt(sql); },
    async batch(list) { for (const s of list) s.exec(); },
    installs,
    daily,
    reports,
  };
}

test("heartbeat upserts idempotently and rejects invalid installIds", async () => {
  const db = fakeDb();
  const env = { DB: db, OWNER_TOKEN: "secret123" };

  const bad = await worker.fetch(new Request("https://w.example/v1/heartbeat", {
    method: "POST",
    body: JSON.stringify({ installId: "not-hex", days: [] }),
  }), env, { waitUntil() {} });
  assert.equal(bad.status, 400);

  const payload = {
    installId: "0123456789abcdef",
    deviceType: "tv",
    deviceModel: "Google onn. 4K",
    androidSdk: 34,
    appVersion: "1.0.140",
    versionCode: 140,
    days: [{ day: "2026-11-27", sessions: 2, appSec: 600, liveSec: 500, vodSec: 120, crashes: 0 }],
  };
  const ok = await worker.fetch(new Request("https://w.example/v1/heartbeat", {
    method: "POST",
    body: JSON.stringify(payload),
  }), env, { waitUntil() {} });
  assert.equal(ok.status, 200);

  // Sending lower numbers in a retry does not overwrite higher values.
  payload.days[0].liveSec = 100;
  await worker.fetch(new Request("https://w.example/v1/heartbeat", {
    method: "POST",
    body: JSON.stringify(payload),
  }), env, { waitUntil() {} });
  assert.equal(db.daily.get("0123456789abcdef|2026-11-27").live_sec, 500);
});

test("report upload returns 6-char code and dashboard requires auth + escapes HTML", async () => {
  const db = fakeDb();
  const env = { DB: db, OWNER_TOKEN: "secret123" };

  const res = await worker.fetch(new Request("https://w.example/v1/report", {
    method: "POST",
    body: JSON.stringify({
      installId: "abcdef0123456789",
      category: "Playback",
      note: "<script>alert(1)</script>",
      appVersion: "1.0.140",
      versionCode: 140,
      deviceType: "phone",
      deviceModel: "Pixel 8 <script>",
      hasCrash: true,
      log: "Line 1\n<img src=x onerror=alert(1)>",
    }),
  }), env, { waitUntil() {} });
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.match(body.code, /^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{6}$/);

  const unauth = await worker.fetch(new Request("https://w.example/dashboard"), env, { waitUntil() {} });
  assert.equal(unauth.status, 401);

  const form = new FormData();
  form.set("token", "secret123");
  const login = await worker.fetch(new Request("https://w.example/dashboard/login", {
    method: "POST",
    body: form,
  }), env, { waitUntil() {} });
  assert.equal(login.status, 303);
  const cookie = login.headers.get("set-cookie").split(";")[0];

  const dash = await worker.fetch(new Request("https://w.example/dashboard", {
    headers: { cookie },
  }), env, { waitUntil() {} });
  assert.equal(dash.status, 200);
  const html = await dash.text();
  assert.ok(html.includes(`#${body.code}`));
  assert.ok(!html.includes("<script>alert(1)</script>"));
  assert.ok(html.includes("&lt;script&gt;alert(1)&lt;/script&gt;"));
});
