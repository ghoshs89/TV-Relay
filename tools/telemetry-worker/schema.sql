CREATE TABLE IF NOT EXISTS installs (
    install_id TEXT PRIMARY KEY,
    device_type TEXT NOT NULL,
    device_model TEXT NOT NULL,
    android_sdk INTEGER NOT NULL,
    app_version TEXT NOT NULL,
    version_code INTEGER NOT NULL,
    first_seen INTEGER NOT NULL,
    last_seen INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS daily_usage (
    install_id TEXT NOT NULL,
    day TEXT NOT NULL,
    sessions INTEGER NOT NULL DEFAULT 0,
    app_sec INTEGER NOT NULL DEFAULT 0,
    live_sec INTEGER NOT NULL DEFAULT 0,
    vod_sec INTEGER NOT NULL DEFAULT 0,
    crashes INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (install_id, day)
);

CREATE INDEX IF NOT EXISTS idx_daily_day ON daily_usage(day);

CREATE TABLE IF NOT EXISTS reports (
    code TEXT PRIMARY KEY,
    created_at INTEGER NOT NULL,
    install_id TEXT NOT NULL,
    category TEXT NOT NULL,
    note TEXT NOT NULL,
    app_version TEXT NOT NULL,
    version_code INTEGER NOT NULL,
    device_type TEXT NOT NULL,
    device_model TEXT NOT NULL,
    has_crash INTEGER NOT NULL DEFAULT 0,
    log_text TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_reports_created ON reports(created_at DESC);
