package org.ociarmmonitor.oci;

import java.time.Instant;
import org.ociarmmonitor.config.OciSettingsProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OciTarget implements ApplicationRunner {
  private final JdbcTemplate jdbc;
  private final OciSettingsProvider settings;

  public OciTarget(JdbcTemplate jdbc, OciSettingsProvider settings) {
    this.jdbc = jdbc;
    this.settings = settings;
  }

  @Override
  public void run(ApplicationArguments args) {
    var value = settings.getSettings();
    if (!settings.isConfigured(value)) return;
    String key = String.join("|", value.tenancyOcid(), value.region(), value.compartmentOcid(), settings.networkCompartment());
    jdbc.update("INSERT OR IGNORE INTO oci_target(id, scope_key) VALUES (1, ?)", key);
    String stored = jdbc.queryForObject("SELECT scope_key FROM oci_target WHERE id = 1", String.class);
    if (!key.equals(stored)) throw new IllegalStateException("OCI 采集范围已变化。请给新范围使用独立数据卷。");
  }

  public void verified() { jdbc.update("UPDATE oci_target SET verified_at = ? WHERE id = 1", Instant.now().toString()); }

  public boolean isVerified() { return jdbc.queryForObject("SELECT COUNT(*) FROM oci_target WHERE verified_at IS NOT NULL", Integer.class) > 0; }
}
