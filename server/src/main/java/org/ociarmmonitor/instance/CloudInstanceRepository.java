package org.ociarmmonitor.instance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CloudInstanceRepository {

  private final JdbcTemplate jdbcTemplate;

  public CloudInstanceRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public long count() {
    Long count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM cloud_instance", Long.class);
    return count == null ? 0 : count;
  }

  public List<CloudInstance> findAll() {
    return jdbcTemplate.query("""
      SELECT id, display_name, region, compartment_id, shape, lifecycle_state, ocpus, memory_gb,
        boot_volume_gb, public_ip, private_ip, created_at, updated_at
      FROM cloud_instance
      ORDER BY display_name ASC
      """, (resultSet, rowNum) -> mapInstance(resultSet));
  }

  public void deleteByIds(List<String> ids) {
    String missing = ids.isEmpty() ? "1 = 1" :
      "id NOT IN (" + String.join(",", ids.stream().map(id -> "?").toList()) + ")";
    jdbcTemplate.update("""
      INSERT INTO instance_history(id, display_name, region, shape, lifecycle_state, ocpus, memory_gb, boot_volume_gb)
      SELECT id, display_name, region, shape, lifecycle_state, ocpus, memory_gb, boot_volume_gb FROM cloud_instance WHERE
      """ + missing + "\n" + """
      ON CONFLICT(id) DO UPDATE SET display_name = excluded.display_name, region = excluded.region,
        shape = excluded.shape, lifecycle_state = excluded.lifecycle_state, ocpus = excluded.ocpus,
        memory_gb = excluded.memory_gb, boot_volume_gb = excluded.boot_volume_gb
      """, ids.toArray());
    jdbcTemplate.update("DELETE FROM cloud_instance WHERE " + missing, ids.toArray());
  }

  public List<HistoricalInstance> listHistory() {
    return jdbcTemplate.query("""
      WITH sources AS (
        SELECT id AS instance_id FROM instance_history
        UNION SELECT instance_id FROM metric_point
        UNION SELECT instance_id FROM legacy_traffic_daily
        UNION SELECT instance_id FROM traffic_daily
      ), metric_dates AS (
        SELECT instance_id, substr(MIN(sampled_at), 1, 10) AS first_day, substr(MAX(sampled_at), 1, 10) AS last_day
        FROM metric_point GROUP BY instance_id
      )
      SELECT s.instance_id, COALESCE(h.display_name, s.instance_id), COALESCE(h.region, ''),
        COALESCE(h.shape, ''), d.first_day, d.last_day, h.ocpus, h.memory_gb, h.boot_volume_gb, h.lifecycle_state
      FROM sources s LEFT JOIN instance_history h ON h.id = s.instance_id
      LEFT JOIN metric_dates d ON d.instance_id = s.instance_id
      WHERE NOT EXISTS (SELECT 1 FROM cloud_instance i WHERE i.id = s.instance_id)
      ORDER BY 2
      """, (row, index) -> new HistoricalInstance(row.getString(1), row.getString(2), row.getString(3),
      row.getString(4), row.getString(5), row.getString(6), nullableSpec(row.getObject(7)),
      nullableSpec(row.getObject(8)), nullableSpec(row.getObject(9)), row.getString(10)));
  }

  private Double nullableSpec(Object value) {
    return value instanceof Number number && number.doubleValue() > 0 ? number.doubleValue() : null;
  }

  public record HistoricalInstance(String id, String displayName, String region, String shape, String firstDay,
                                   String lastDay, Double ocpus, Double memoryGb, Double bootVolumeGb, String lifecycleState) {}

  public double sumOcpus() {
    Double value = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(ocpus), 0) FROM cloud_instance", Double.class);
    return value == null ? 0 : value;
  }

  public double sumMemoryGb() {
    Double value = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(memory_gb), 0) FROM cloud_instance", Double.class);
    return value == null ? 0 : value;
  }

  public double sumBootVolumeGb() {
    Double value = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(boot_volume_gb), 0) FROM cloud_instance", Double.class);
    return value == null ? 0 : value;
  }

  public void save(CloudInstance instance) {
    jdbcTemplate.update("""
      INSERT INTO cloud_instance (
        id, display_name, region, compartment_id, shape, lifecycle_state, ocpus, memory_gb,
        boot_volume_gb, public_ip, private_ip, created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        display_name = excluded.display_name,
        region = excluded.region,
        compartment_id = excluded.compartment_id,
        shape = excluded.shape,
        lifecycle_state = excluded.lifecycle_state,
        ocpus = excluded.ocpus,
        memory_gb = excluded.memory_gb,
        boot_volume_gb = CASE WHEN excluded.boot_volume_gb > 0 THEN excluded.boot_volume_gb ELSE cloud_instance.boot_volume_gb END,
        public_ip = excluded.public_ip,
        private_ip = excluded.private_ip,
        updated_at = excluded.updated_at
      """,
      instance.id(),
      instance.displayName(),
      instance.region(),
      instance.compartmentId(),
      instance.shape(),
      instance.lifecycleState(),
      instance.ocpus(),
      instance.memoryGb(),
      instance.bootVolumeGb(),
      instance.publicIp(),
      instance.privateIp(),
      instance.createdAt(),
      instance.updatedAt()
    );
  }

  private CloudInstance mapInstance(ResultSet resultSet) throws SQLException {
    return new CloudInstance(
      resultSet.getString("id"),
      resultSet.getString("display_name"),
      resultSet.getString("region"),
      resultSet.getString("compartment_id"),
      resultSet.getString("shape"),
      resultSet.getString("lifecycle_state"),
      resultSet.getDouble("ocpus"),
      resultSet.getDouble("memory_gb"),
      resultSet.getDouble("boot_volume_gb"),
      resultSet.getString("public_ip"),
      resultSet.getString("private_ip"),
      resultSet.getString("created_at"),
      resultSet.getString("updated_at")
    );
  }
}
