# Relay Telemetry & Log Upload Worker (Optional, 3-Minute Setup)

Runs on Cloudflare's free tier (Workers + D1). No secrets or tokens ever go into the Relay APK: the APK only knows the public HTTPS Worker URL (`RELAY_TELEMETRY_URL`).

## 1. Create the Worker and D1 database

Requires Node.js 18+ and a free Cloudflare account (`npx wrangler login` once):

```bash
cd tools/telemetry-worker
npx wrangler d1 create relay-telemetry
```

Copy the `database_id` printed by that command into `tools/telemetry-worker/wrangler.toml`, then initialize the schema and set your dashboard password:

```bash
npx wrangler d1 execute relay-telemetry --remote --file=schema.sql
npx wrangler secret put OWNER_TOKEN
```

*(Optional)* To also open an Issue in a **private** GitHub repository whenever a screen uploads a crash or diagnostic report:

```bash
npx wrangler secret put GITHUB_LOG_REPO    # e.g. your-name/relay-logs
npx wrangler secret put GITHUB_LOG_TOKEN   # fine-grained PAT (Issues: Read & write on that repo)
```

Deploy the Worker:

```bash
npx wrangler deploy
```

Note the printed URL (e.g. `https://relay-telemetry.<your-subdomain>.workers.dev`).

## 2. Connect it to GitHub Actions builds

In your GitHub repository:

1. **Settings → Secrets and variables → Actions → Variables → New repository variable**
2. Name: `RELAY_TELEMETRY_URL`
3. Value: `https://relay-telemetry.<your-subdomain>.workers.dev`

Open `https://relay-telemetry.<your-subdomain>.workers.dev/dashboard` in any browser and sign in with your `OWNER_TOKEN` to view active screens, Live vs Movies/Series watch hours, and `#CODE` crash/diagnostic reports.

## Tests

```bash
node --test tools/telemetry-worker/test/worker.test.mjs
```
