CREATE TABLE IF NOT EXISTS cloud_instance (
  id TEXT PRIMARY KEY,
  display_name TEXT NOT NULL,
  region TEXT NOT NULL,
  compartment_id TEXT,
  shape TEXT NOT NULL,
  lifecycle_state TEXT NOT NULL,
  ocpus REAL NOT NULL,
  memory_gb REAL NOT NULL,
  boot_volume_gb REAL NOT NULL,
  public_ip TEXT,
  private_ip TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS metric_point (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  instance_id TEXT NOT NULL,
  metric_name TEXT NOT NULL,
  metric_value REAL NOT NULL,
  unit TEXT NOT NULL,
  sampled_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_metric_instance_name_time
  ON metric_point(instance_id, metric_name, sampled_at);

DELETE FROM metric_point
WHERE rowid NOT IN (
  SELECT MIN(rowid)
  FROM metric_point
  GROUP BY instance_id, metric_name, sampled_at
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_metric_point_unique
  ON metric_point(instance_id, metric_name, sampled_at);

CREATE TABLE IF NOT EXISTS traffic_daily (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  instance_id TEXT NOT NULL,
  stat_date TEXT NOT NULL,
  ingress_gb REAL NOT NULL,
  egress_gb REAL NOT NULL,
  UNIQUE(instance_id, stat_date)
);

CREATE TABLE IF NOT EXISTS cost_daily (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  service_name TEXT NOT NULL,
  resource_id TEXT,
  stat_date TEXT NOT NULL,
  usage_amount REAL NOT NULL,
  usage_unit TEXT NOT NULL,
  cost_amount TEXT NOT NULL,
  currency TEXT NOT NULL
);

UPDATE cost_daily
SET resource_id = '-'
WHERE resource_id IS NULL OR resource_id = '';

DELETE FROM cost_daily
WHERE rowid NOT IN (
  SELECT MIN(rowid)
  FROM cost_daily
  GROUP BY service_name, resource_id, stat_date, usage_unit, currency
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_cost_daily_unique
  ON cost_daily(service_name, resource_id, stat_date, usage_unit, currency);

CREATE TABLE IF NOT EXISTS manual_cost (
  id TEXT PRIMARY KEY,
  cost_name TEXT NOT NULL,
  category TEXT NOT NULL,
  amount REAL NOT NULL,
  currency TEXT NOT NULL,
  occurred_on TEXT NOT NULL,
  note TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS free_quota (
  id TEXT PRIMARY KEY,
  ampere_ocpu_hours REAL NOT NULL,
  ampere_memory_gb_hours REAL NOT NULL,
  block_volume_gb REAL NOT NULL,
  outbound_data_transfer_gb REAL NOT NULL,
  monitoring_ingestion_points REAL NOT NULL,
  monitoring_retrieval_points REAL NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sync_run (
  id TEXT PRIMARY KEY,
  sync_type TEXT NOT NULL,
  status TEXT NOT NULL,
  message TEXT,
  started_at TEXT NOT NULL,
  finished_at TEXT,
  instance_count INTEGER NOT NULL DEFAULT 0,
  metric_count INTEGER NOT NULL DEFAULT 0,
  traffic_count INTEGER NOT NULL DEFAULT 0,
  cost_count INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_sync_run_type_started
  ON sync_run(sync_type, started_at);

CREATE TABLE IF NOT EXISTS sync_schedule (
  id TEXT PRIMARY KEY,
  enabled INTEGER NOT NULL,
  cron_expression TEXT NOT NULL,
  zone_id TEXT NOT NULL,
  sync_on_startup INTEGER NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS server_status_snapshot (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  sampled_at TEXT NOT NULL UNIQUE,
  cpu_usage_percent REAL NOT NULL,
  load_one REAL NOT NULL,
  load_five REAL NOT NULL,
  load_fifteen REAL NOT NULL,
  memory_total_bytes INTEGER NOT NULL,
  memory_available_bytes INTEGER NOT NULL,
  memory_usage_percent REAL NOT NULL,
  swap_total_bytes INTEGER NOT NULL,
  swap_free_bytes INTEGER NOT NULL,
  swap_usage_percent REAL NOT NULL,
  disk_total_bytes INTEGER NOT NULL,
  disk_usable_bytes INTEGER NOT NULL,
  disk_usage_percent REAL NOT NULL,
  network_rx_bytes INTEGER NOT NULL,
  network_tx_bytes INTEGER NOT NULL,
  network_rx_bytes_per_second REAL NOT NULL,
  network_tx_bytes_per_second REAL NOT NULL,
  uptime_seconds INTEGER NOT NULL,
  process_uptime_seconds INTEGER NOT NULL,
  jvm_memory_used_bytes INTEGER NOT NULL,
  jvm_memory_max_bytes INTEGER NOT NULL,
  jvm_thread_count INTEGER NOT NULL,
  database_size_bytes INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_server_status_sampled_at
  ON server_status_snapshot(sampled_at);

CREATE TABLE IF NOT EXISTS alert_rule (
  id TEXT PRIMARY KEY,
  metric_name TEXT NOT NULL,
  operator TEXT NOT NULL,
  threshold REAL NOT NULL,
  severity TEXT NOT NULL,
  enabled INTEGER NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS wechat_notification_setting (
  id TEXT PRIMARY KEY,
  enabled INTEGER NOT NULL,
  encrypted_app_id TEXT NOT NULL,
  encrypted_app_secret TEXT NOT NULL,
  encrypted_template_id TEXT NOT NULL,
  encrypted_open_ids TEXT NOT NULL,
  public_url TEXT NOT NULL,
  immediate_push_enabled INTEGER NOT NULL,
  daily_summary_enabled INTEGER NOT NULL,
  daily_summary_time TEXT NOT NULL,
  zone_id TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS wechat_cost_template_setting (
  id TEXT PRIMARY KEY,
  encrypted_template_id TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS alert_notification_state (
  metric_name TEXT PRIMARY KEY,
  active INTEGER NOT NULL,
  severity TEXT NOT NULL,
  title TEXT NOT NULL,
  description TEXT NOT NULL,
  current_value REAL NOT NULL,
  threshold REAL NOT NULL,
  unit TEXT NOT NULL,
  changed_at TEXT NOT NULL,
  last_notified_at TEXT
);

CREATE TABLE IF NOT EXISTS wechat_delivery_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  notification_type TEXT NOT NULL,
  metric_name TEXT NOT NULL,
  success_count INTEGER NOT NULL,
  failure_count INTEGER NOT NULL,
  message TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_wechat_delivery_created_at
  ON wechat_delivery_log(created_at);

CREATE TABLE IF NOT EXISTS wechat_daily_summary_state (
  id TEXT PRIMARY KEY,
  last_attempted_date TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS wechat_detail_page_setting (
  id TEXT PRIMARY KEY,
  enabled INTEGER NOT NULL,
  token_ttl_days INTEGER NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS public_report_snapshot (
  id TEXT PRIMARY KEY,
  report_type TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_public_report_snapshot_expires_at
  ON public_report_snapshot(expires_at);

CREATE TABLE IF NOT EXISTS public_report_access (
  id TEXT PRIMARY KEY,
  snapshot_id TEXT NOT NULL,
  token_hash TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  revoked_at TEXT,
  last_accessed_at TEXT,
  access_count INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(snapshot_id) REFERENCES public_report_snapshot(id)
);

CREATE INDEX IF NOT EXISTS idx_public_report_access_snapshot
  ON public_report_access(snapshot_id);

CREATE INDEX IF NOT EXISTS idx_public_report_access_expires_at
  ON public_report_access(expires_at);

INSERT OR IGNORE INTO sync_schedule(id, enabled, cron_expression, zone_id, sync_on_startup, updated_at)
VALUES ('default', 1, '0 0 8 * * *', 'Asia/Shanghai', 0, datetime('now'));

INSERT OR IGNORE INTO alert_rule(id, metric_name, operator, threshold, severity, enabled, created_at, updated_at)
VALUES
  ('cpu-high', 'cpu_usage_percent', 'GT', 90, 'danger', 1, datetime('now'), datetime('now')),
  ('memory-high', 'memory_usage_percent', 'GT', 85, 'warning', 1, datetime('now'), datetime('now')),
  ('disk-high', 'disk_usage_percent', 'GT', 80, 'warning', 1, datetime('now'), datetime('now')),
  ('sync-stale', 'sync_age_hours', 'GT', 26, 'warning', 1, datetime('now'), datetime('now'));

CREATE TABLE IF NOT EXISTS admin_user (
  id TEXT PRIMARY KEY,
  username TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  password_salt TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS oci_budget_usage (
  utc_day TEXT PRIMARY KEY, requests INTEGER NOT NULL, points INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS oci_target (
  id INTEGER PRIMARY KEY CHECK (id = 1), scope_key TEXT NOT NULL, verified_at TEXT
);

CREATE TABLE IF NOT EXISTS source_coverage (
  source TEXT NOT NULL, stat_date TEXT NOT NULL, status TEXT NOT NULL, message TEXT NOT NULL,
  PRIMARY KEY (source, stat_date)
);

CREATE TABLE IF NOT EXISTS sync_change (
  id INTEGER PRIMARY KEY, title TEXT NOT NULL, description TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS cost_budget (
  month TEXT NOT NULL, currency TEXT NOT NULL, amount TEXT NOT NULL, reached_threshold INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (month, currency)
);

CREATE TABLE IF NOT EXISTS maintenance_window (
  id TEXT PRIMARY KEY, reason TEXT NOT NULL, starts_at TEXT NOT NULL, ends_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS instance_history (
  id TEXT PRIMARY KEY, display_name TEXT NOT NULL, region TEXT NOT NULL, shape TEXT NOT NULL,
  lifecycle_state TEXT NOT NULL, ocpus REAL NOT NULL, memory_gb REAL NOT NULL, boot_volume_gb REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS legacy_traffic_daily (
  instance_id TEXT NOT NULL, stat_date TEXT NOT NULL, ingress_gb REAL NOT NULL, egress_gb REAL NOT NULL,
  PRIMARY KEY (instance_id, stat_date)
);

CREATE INDEX IF NOT EXISTS idx_legacy_traffic_date ON legacy_traffic_daily(stat_date);

CREATE TABLE IF NOT EXISTS legacy_host_snapshot (
  host_label TEXT NOT NULL, sampled_at TEXT NOT NULL, cpu_usage_percent REAL NOT NULL,
  memory_usage_percent REAL NOT NULL, disk_usage_percent REAL NOT NULL,
  network_rx_bytes_per_second REAL NOT NULL, network_tx_bytes_per_second REAL NOT NULL,
  PRIMARY KEY (host_label, sampled_at)
);

CREATE TABLE IF NOT EXISTS oci_volume (
  id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL,
  size_gb INTEGER, state TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS oci_volume_attachment (
  volume_id TEXT NOT NULL, instance_id TEXT NOT NULL, state TEXT NOT NULL,
  PRIMARY KEY (volume_id, instance_id)
);

CREATE INDEX IF NOT EXISTS idx_oci_volume_attachment_instance ON oci_volume_attachment(instance_id);

CREATE TABLE IF NOT EXISTS oci_volume_snapshot (
  id INTEGER PRIMARY KEY CHECK (id = 1), status TEXT NOT NULL, attempted_at TEXT NOT NULL,
  succeeded_at TEXT, region TEXT, compartment_id TEXT, home_region TEXT, incomplete INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS oci_usage_snapshot (
  month TEXT PRIMARY KEY, status TEXT NOT NULL, attempted_at TEXT NOT NULL,
  succeeded_at TEXT, requested_through TEXT, compute_region TEXT,
  ocpu_hours REAL, memory_gb_hours REAL, outbound_gb REAL
);
