const INSTALL_ID_RE = /^[0-9a-f]{16,32}$/;
const DAY_RE = /^\d{4}-\d{2}-\d{2}$/;
const CODE_RE = /^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{6}$/;
const CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
const MAX_HEARTBEAT_BYTES = 8 * 1024;
const MAX_REPORT_BYTES = 384 * 1024;
const MAX_LOG_CHARS = 260 * 1024;
const RETENTION_MS = 90 * 86_400_000;
const COOKIE_NAME = "relay_owner";

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const { pathname } = url;

    if (request.method === "GET" && pathname === "/health") {
      return json({ ok: true });
    }
    if (request.method === "POST" && pathname === "/v1/heartbeat") {
      return handleHeartbeat(request, env);
    }
    if (request.method === "POST" && pathname === "/v1/report") {
      return handleReport(request, env, ctx);
    }
    if (pathname === "/dashboard/login" && request.method === "POST") {
      return handleLogin(request, env);
    }
    if (pathname === "/dashboard/logout" && request.method === "POST") {
      return redirect("/dashboard", `${COOKIE_NAME}=; HttpOnly; Secure; SameSite=Strict; Path=/dashboard; Max-Age=0`);
    }
    if (request.method === "GET" && pathname === "/dashboard") {
      return handleDashboard(request, env, url);
    }
    if (request.method === "GET" && pathname.startsWith("/dashboard/report/")) {
      const code = pathname.slice("/dashboard/report/".length).trim().toUpperCase();
      return handleReportDetail(request, env, url, code);
    }
    return new Response("Not found", { status: 404 });
  },
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function htmlPage(title, body, status = 200) {
  const doc = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>${escapeHtml(title)}</title>
<style>
:root{color-scheme:dark;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#0e1218;color:#e6edf3}
body{margin:0;padding:24px;max-width:1100px;margin-inline:auto;line-height:1.45}
a{color:#6cb6ff;text-decoration:none}a:hover{text-decoration:underline}
h1,h2,h3{margin:0 0 12px}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin:16px 0 24px}
.card{background:#161c24;border:1px solid #26303d;border-radius:10px;padding:14px}
.card .v{font-size:24px;font-weight:700;margin-top:4px}
.muted{color:#8b949e;font-size:13px}
table{width:100%;border-collapse:collapse;background:#161c24;border:1px solid #26303d;border-radius:10px;overflow:hidden;margin-bottom:24px}
th,td{padding:10px 12px;border-bottom:1px solid #26303d;text-align:left;font-size:14px}
th{background:#1b232e;color:#9fb0c4;font-weight:600}
tr:last-child td{border-bottom:none}
.pill{display:inline-block;padding:2px 8px;border-radius:999px;background:#223042;font-size:12px}
.crash{background:#4b1d24;color:#ff9ba6}
form.inline{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin:12px 0}
input[type=text],input[type=password]{background:#0e1218;color:#e6edf3;border:1px solid #303b4a;border-radius:8px;padding:8px 10px;font-size:14px}
button{background:#2f81f7;color:#fff;border:none;border-radius:8px;padding:8px 14px;font-size:14px;font-weight:600;cursor:pointer}
button.secondary{background:#212936;color:#c9d1d9}
pre{background:#0b0f14;border:1px solid #26303d;border-radius:10px;padding:14px;overflow:auto;font-size:12.5px;white-space:pre-wrap;word-break:break-word;max-height:70vh}
.top{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:12px}
</style>
</head>
<body>${body}</body>
</html>`;
  return new Response(doc, {
    status,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
      "x-frame-options": "DENY",
    },
  });
}

function escapeHtml(s) {
  return String(s ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function redirect(location, cookie = null) {
  const headers = new Headers({ location, "cache-control": "no-store" });
  if (cookie) headers.set("set-cookie", cookie);
  return new Response(null, { status: 303, headers });
}

async function readJsonLimited(request, maxBytes) {
  const cl = Number(request.headers.get("content-length") || "0");
  if (Number.isFinite(cl) && cl > maxBytes) return { error: 413 };
  const text = await request.text();
  if (new TextEncoder().encode(text).length > maxBytes) return { error: 413 };
  try {
    return { data: JSON.parse(text) };
  } catch {
    return { error: 400 };
  }
}

function cleanStr(v, maxLen) {
  return typeof v === "string" ? v.replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, maxLen) : "";
}

function clampInt(v, min, max) {
  const n = Number(v);
  if (!Number.isFinite(n)) return min;
  return Math.max(min, Math.min(max, Math.trunc(n)));
}

function randomCode() {
  const bytes = new Uint8Array(6);
  crypto.getRandomValues(bytes);
  let out = "";
  for (let i = 0; i < 6; i++) out += CODE_ALPHABET[bytes[i] % CODE_ALPHABET.length];
  return out;
}

async function hmacCookie(secret, payload) {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(payload));
  return Array.from(new Uint8Array(sig)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

function timingSafeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  const ab = new TextEncoder().encode(a);
  const bb = new TextEncoder().encode(b);
  if (ab.length !== bb.length) return false;
  let diff = 0;
  for (let i = 0; i < ab.length; i++) diff |= ab[i] ^ bb[i];
  return diff === 0;
}

async function isAuthorized(request, env) {
  const token = env.OWNER_TOKEN || "";
  if (!token) return false;
  const cookieHeader = request.headers.get("cookie") || "";
  const raw = cookieHeader
    .split(";")
    .map((p) => p.trim())
    .find((p) => p.startsWith(`${COOKIE_NAME}=`))
    ?.slice(COOKIE_NAME.length + 1);
  if (!raw) return false;
  const dot = raw.indexOf(".");
  if (dot <= 0) return false;
  const expStr = raw.slice(0, dot);
  const mac = raw.slice(dot + 1);
  const exp = Number(expStr);
  if (!Number.isFinite(exp) || exp < Date.now()) return false;
  const expected = await hmacCookie(token, expStr);
  return timingSafeEqual(mac, expected);
}

async function handleHeartbeat(request, env) {
  const { data, error } = await readJsonLimited(request, MAX_HEARTBEAT_BYTES);
  if (error) return json({ error: error === 413 ? "Payload too large" : "Invalid JSON" }, error);

  const installId = typeof data?.installId === "string" ? data.installId.trim().toLowerCase() : "";
  if (!INSTALL_ID_RE.test(installId)) return json({ error: "Invalid installId" }, 400);

  const deviceType = data.deviceType === "tv" ? "tv" : "phone";
  const deviceModel = cleanStr(data.deviceModel, 80) || "Unknown";
  const androidSdk = clampInt(data.androidSdk, 1, 99);
  const appVersion = cleanStr(data.appVersion, 32) || "unknown";
  const versionCode = clampInt(data.versionCode, 0, 1_000_000);
  const now = Date.now();

  const days = Array.isArray(data.days) ? data.days.slice(0, 14) : [];
  const statements = [
    env.DB.prepare(
      `INSERT INTO installs (install_id, device_type, device_model, android_sdk, app_version, version_code, first_seen, last_seen)
       VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?7)
       ON CONFLICT(install_id) DO UPDATE SET
         device_type = excluded.device_type,
         device_model = excluded.device_model,
         android_sdk = excluded.android_sdk,
         app_version = excluded.app_version,
         version_code = excluded.version_code,
         last_seen = MAX(installs.last_seen, excluded.last_seen)`,
    ).bind(installId, deviceType, deviceModel, androidSdk, appVersion, versionCode, now),
  ];

  for (const d of days) {
    const day = typeof d?.day === "string" ? d.day.trim() : "";
    if (!DAY_RE.test(day)) continue;
    const sessions = clampInt(d.sessions, 0, 10_000);
    const appSec = clampInt(d.appSec, 0, 86_400);
    const liveSec = clampInt(d.liveSec, 0, 86_400);
    const vodSec = clampInt(d.vodSec, 0, 86_400);
    const crashes = clampInt(d.crashes, 0, 1_000);
    statements.push(
      env.DB.prepare(
        `INSERT INTO daily_usage (install_id, day, sessions, app_sec, live_sec, vod_sec, crashes, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
         ON CONFLICT(install_id, day) DO UPDATE SET
           sessions = MAX(daily_usage.sessions, excluded.sessions),
           app_sec = MAX(daily_usage.app_sec, excluded.app_sec),
           live_sec = MAX(daily_usage.live_sec, excluded.live_sec),
           vod_sec = MAX(daily_usage.vod_sec, excluded.vod_sec),
           crashes = MAX(daily_usage.crashes, excluded.crashes),
           updated_at = excluded.updated_at`,
      ).bind(installId, day, sessions, appSec, liveSec, vodSec, crashes, now),
    );
  }

  await env.DB.batch(statements);
  return json({ ok: true });
}

async function handleReport(request, env, ctx) {
  const { data, error } = await readJsonLimited(request, MAX_REPORT_BYTES);
  if (error) return json({ error: error === 413 ? "Payload too large" : "Invalid JSON" }, error);

  const installId = typeof data?.installId === "string" ? data.installId.trim().toLowerCase() : "";
  if (!INSTALL_ID_RE.test(installId)) return json({ error: "Invalid installId" }, 400);

  const logText = typeof data.log === "string" ? data.log.slice(0, MAX_LOG_CHARS) : "";
  if (!logText.trim()) return json({ error: "Empty log" }, 400);

  const category = cleanStr(data.category, 40) || "Other";
  const note = cleanStr(data.note, 500);
  const appVersion = cleanStr(data.appVersion, 32) || "unknown";
  const versionCode = clampInt(data.versionCode, 0, 1_000_000);
  const deviceType = data.deviceType === "tv" ? "tv" : "phone";
  const deviceModel = cleanStr(data.deviceModel, 80) || "Unknown";
  const hasCrash = data.hasCrash ? 1 : 0;
  const now = Date.now();
  const code = randomCode();

  await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO reports (code, created_at, install_id, category, note, app_version, version_code, device_type, device_model, has_crash, log_text)
       VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)`,
    ).bind(code, now, installId, category, note, appVersion, versionCode, deviceType, deviceModel, hasCrash, logText),
    env.DB.prepare("DELETE FROM reports WHERE created_at < ?1").bind(now - RETENTION_MS),
  ]);

  if (env.GITHUB_LOG_REPO && env.GITHUB_LOG_TOKEN) {
    const dashboardBase = new URL(request.url).origin;
    ctx.waitUntil(notifyGitHub(env, {
      code,
      category,
      note,
      appVersion,
      deviceType,
      deviceModel,
      hasCrash,
      logText,
      installShort: installId.slice(0, 6),
      reportUrl: `${dashboardBase}/dashboard/report/${code}`,
    }));
  }

  return json({ ok: true, code });
}

async function notifyGitHub(env, r) {
  const title = `[#${r.code}] ${r.category} · ${r.deviceModel} (Relay ${r.appVersion})`;
  const preview = r.logText.slice(0, 20_000);
  const body = [
    `**Report:** \`#${r.code}\`  `,
    `**Category:** ${r.category}  `,
    `**Device:** ${r.deviceType} · ${r.deviceModel} (screen \`${r.installShort}\`)  `,
    `**Version:** ${r.appVersion} · **Crash trace attached:** ${r.hasCrash ? "yes" : "no"}`,
    r.note ? `\n**Viewer note:**\n> ${r.note.replaceAll("\n", "\n> ")}` : "",
    `\n[Open full log in dashboard](${r.reportUrl})`,
    `\n<details>\n<summary>Log preview</summary>\n\n\`\`\`text\n${preview.replaceAll("```", "'''")}\n\`\`\`\n</details>`,
  ].filter(Boolean).join("\n");

  try {
    await fetch(`https://api.github.com/repos/${env.GITHUB_LOG_REPO}/issues`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${env.GITHUB_LOG_TOKEN}`,
        accept: "application/vnd.github+json",
        "user-agent": "Relay-Telemetry-Worker",
        "content-type": "application/json",
      },
      body: JSON.stringify({ title, body }),
    });
  } catch {
    // Notification failure never fails the user's upload.
  }
}

async function handleLogin(request, env) {
  if (!env.OWNER_TOKEN) {
    return htmlPage("Relay Dashboard", "<h1>Worker not configured</h1><p class='muted'>Set OWNER_TOKEN via wrangler secret put OWNER_TOKEN.</p>", 503);
  }
  const form = await request.formData();
  const submitted = String(form.get("token") || "");
  if (!timingSafeEqual(submitted, env.OWNER_TOKEN)) {
    return htmlPage(
      "Relay Dashboard Sign In",
      "<h1>Relay Dashboard</h1><p class='muted'>Invalid token.</p>" + loginForm(),
      401,
    );
  }
  const exp = String(Date.now() + 7 * 86_400_000);
  const mac = await hmacCookie(env.OWNER_TOKEN, exp);
  const cookie = `${COOKIE_NAME}=${exp}.${mac}; HttpOnly; Secure; SameSite=Strict; Path=/dashboard; Max-Age=${7 * 86400}`;
  return redirect("/dashboard", cookie);
}

function loginForm() {
  return `<form method="post" action="/dashboard/login" class="inline">
    <input type="password" name="token" placeholder="Owner token" autocomplete="current-password" required>
    <button type="submit">Sign in</button>
  </form>`;
}

function fmtHours(sec) {
  const s = Number(sec) || 0;
  if (s < 60) return `${s}s`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m`;
  return `${(s / 3600).toFixed(1)}h`;
}

function fmtAgo(ms) {
  const diff = Math.max(0, Date.now() - Number(ms || 0));
  const min = Math.floor(diff / 60_000);
  if (min < 2) return "just now";
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 48) return `${hr}h ago`;
  return `${Math.floor(hr / 24)}d ago`;
}

async function handleDashboard(request, env, url) {
  if (!env.OWNER_TOKEN) {
    return htmlPage("Relay Dashboard", "<h1>Relay Dashboard</h1><p class='muted'>OWNER_TOKEN is not set yet.</p>", 503);
  }
  if (!(await isAuthorized(request, env))) {
    return htmlPage("Relay Dashboard Sign In", `<h1>Relay Dashboard</h1>${loginForm()}`, 401);
  }

  const now = Date.now();
  const day7 = new Date(now - 6 * 86_400_000).toISOString().slice(0, 10);
  const today = new Date(now).toISOString().slice(0, 10);
  const searchCode = cleanStr(url.searchParams.get("q") || "", 12).toUpperCase().replace(/^#/, "");

  const summary = await env.DB.prepare(
    `SELECT
       COUNT(*) AS total_screens,
       SUM(CASE WHEN last_seen >= ?1 THEN 1 ELSE 0 END) AS active_24h,
       SUM(CASE WHEN last_seen >= ?2 THEN 1 ELSE 0 END) AS active_7d
     FROM installs`,
  ).bind(now - 86_400_000, now - 7 * 86_400_000).first();

  const totals = await env.DB.prepare(
    `SELECT
       COALESCE(SUM(app_sec), 0) AS app_sec,
       COALESCE(SUM(live_sec), 0) AS live_sec,
       COALESCE(SUM(vod_sec), 0) AS vod_sec,
       COALESCE(SUM(crashes), 0) AS crashes
     FROM daily_usage`,
  ).first();

  const screens = (await env.DB.prepare(
    `SELECT
       i.install_id, i.device_type, i.device_model, i.app_version, i.last_seen,
       COALESCE(SUM(u.live_sec), 0) AS total_live,
       COALESCE(SUM(u.vod_sec), 0) AS total_vod,
       COALESCE(SUM(CASE WHEN u.day >= ?1 THEN u.live_sec + u.vod_sec ELSE 0 END), 0) AS week_watch,
       COALESCE(SUM(CASE WHEN u.day = ?2 THEN u.live_sec + u.vod_sec ELSE 0 END), 0) AS today_watch,
       COALESCE(SUM(u.crashes), 0) AS crashes
     FROM installs i
     LEFT JOIN daily_usage u ON u.install_id = i.install_id
     GROUP BY i.install_id
     ORDER BY i.last_seen DESC
     LIMIT 100`,
  ).bind(day7, today).all()).results || [];

  const reports = CODE_RE.test(searchCode)
    ? (await env.DB.prepare(
        `SELECT code, created_at, install_id, category, note, app_version, device_type, device_model, has_crash
         FROM reports WHERE code = ?1 ORDER BY created_at DESC LIMIT 50`,
      ).bind(searchCode).all()).results || []
    : (await env.DB.prepare(
        `SELECT code, created_at, install_id, category, note, app_version, device_type, device_model, has_crash
         FROM reports ORDER BY created_at DESC LIMIT 50`,
      ).all()).results || [];

  const screenRows = screens.map((s) => `<tr>
    <td><code>${escapeHtml(String(s.install_id).slice(0, 6))}</code></td>
    <td><span class="pill">${escapeHtml(s.device_type)}</span> ${escapeHtml(s.device_model)}</td>
    <td>${escapeHtml(s.app_version)}</td>
    <td>${escapeHtml(fmtAgo(s.last_seen))}</td>
    <td>${escapeHtml(fmtHours(s.today_watch))}</td>
    <td>${escapeHtml(fmtHours(s.week_watch))}</td>
    <td>${escapeHtml(fmtHours(Number(s.total_live) + Number(s.total_vod)))} <span class="muted">(${escapeHtml(fmtHours(s.total_live))} live / ${escapeHtml(fmtHours(s.total_vod))} VOD)</span></td>
    <td>${Number(s.crashes) > 0 ? `<span class="pill crash">${escapeHtml(s.crashes)}</span>` : "0"}</td>
  </tr>`).join("");

  const reportRows = reports.map((r) => `<tr>
    <td><a href="/dashboard/report/${escapeHtml(r.code)}"><code>#${escapeHtml(r.code)}</code></a></td>
    <td>${escapeHtml(fmtAgo(r.created_at))}</td>
    <td>${r.has_crash ? "<span class='pill crash'>Crash</span> " : ""}${escapeHtml(r.category)}</td>
    <td>${escapeHtml(r.device_model)} <span class="muted">(${escapeHtml(r.app_version)})</span></td>
    <td>${escapeHtml(r.note || "—")}</td>
  </tr>`).join("");

  const html = `
    <div class="top">
      <div>
        <h1>Relay Telemetry &amp; Reports</h1>
        <div class="muted">Anonymous device counts, watch hours &amp; scrubbed diagnostic logs (no logins, IPs or titles stored)</div>
      </div>
      <form method="post" action="/dashboard/logout"><button class="secondary" type="submit">Sign out</button></form>
    </div>
    <div class="cards">
      <div class="card"><div class="muted">Active (24h)</div><div class="v">${escapeHtml(summary?.active_24h ?? 0)}</div></div>
      <div class="card"><div class="muted">Active (7d)</div><div class="v">${escapeHtml(summary?.active_7d ?? 0)} / ${escapeHtml(summary?.total_screens ?? 0)}</div></div>
      <div class="card"><div class="muted">Live TV watched</div><div class="v">${escapeHtml(fmtHours(totals?.live_sec ?? 0))}</div></div>
      <div class="card"><div class="muted">Movies &amp; Series</div><div class="v">${escapeHtml(fmtHours(totals?.vod_sec ?? 0))}</div></div>
      <div class="card"><div class="muted">Logged crashes</div><div class="v">${escapeHtml(totals?.crashes ?? 0)}</div></div>
    </div>
    <h2>Screens</h2>
    <table>
      <thead><tr><th>ID</th><th>Device</th><th>Version</th><th>Last seen</th><th>Today</th><th>7 days</th><th>Total watched</th><th>Crashes</th></tr></thead>
      <tbody>${screenRows || "<tr><td colspan='8' class='muted'>No screens have checked in yet.</td></tr>"}</tbody>
    </table>
    <div class="top">
      <h2>Diagnostic &amp; crash reports</h2>
      <form method="get" action="/dashboard" class="inline">
        <input type="text" name="q" value="${escapeHtml(searchCode)}" placeholder="Find #CODE (e.g. K7P2M9)">
        <button type="submit">Find</button>
        ${searchCode ? "<a href='/dashboard'>Clear</a>" : ""}
      </form>
    </div>
    <table>
      <thead><tr><th>Code</th><th>Received</th><th>Category</th><th>Device</th><th>Viewer note</th></tr></thead>
      <tbody>${reportRows || "<tr><td colspan='5' class='muted'>No matching reports.</td></tr>"}</tbody>
    </table>`;
  return htmlPage("Relay Telemetry & Reports", html);
}

async function handleReportDetail(request, env, url, code) {
  if (!(await isAuthorized(request, env))) {
    return htmlPage("Relay Dashboard Sign In", `<h1>Relay Dashboard</h1>${loginForm()}`, 401);
  }
  if (!CODE_RE.test(code)) return htmlPage("Invalid report code", "<p>Invalid report code.</p><p><a href='/dashboard'>Back</a></p>", 400);

  const row = await env.DB.prepare("SELECT * FROM reports WHERE code = ?1").bind(code).first();
  if (!row) return htmlPage("Not found", `<p>Report #${escapeHtml(code)} was not found.</p><p><a href='/dashboard'>Back</a></p>`, 404);

  if (url.searchParams.get("raw") === "1") {
    return new Response(row.log_text, {
      status: 200,
      headers: {
        "content-type": "text/plain; charset=utf-8",
        "content-disposition": `attachment; filename="relay-report-${code}.txt"`,
        "cache-control": "no-store",
      },
    });
  }

  const html = `
    <div class="top">
      <div>
        <h1>Report #${escapeHtml(row.code)}</h1>
        <div class="muted">${escapeHtml(row.category)} · ${escapeHtml(row.device_type)} ${escapeHtml(row.device_model)} · Relay ${escapeHtml(row.app_version)} · ${escapeHtml(new Date(row.created_at).toISOString())}</div>
      </div>
      <div>
        <a href="/dashboard/report/${escapeHtml(row.code)}?raw=1"><button type="button">Download .txt</button></a>
        <a href="/dashboard" style="margin-left:10px">Back to dashboard</a>
      </div>
    </div>
    ${row.note ? `<div class="card" style="margin-bottom:16px"><strong>Viewer note:</strong> ${escapeHtml(row.note)}</div>` : ""}
    <pre>${escapeHtml(row.log_text)}</pre>`;
  return htmlPage(`Relay Report #${code}`, html);
}
