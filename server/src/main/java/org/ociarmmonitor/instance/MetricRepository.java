package org.ociarmmonitor.instance;

import java.sql.ResultSet;
import org.ociarmmonitor.common.HistoryWindow;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MetricRepository {

  private final JdbcTemplate jdbcTemplate;

  public MetricRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void insert(MetricPoint metricPoint) {
    jdbcTemplate.update("""
      INSERT INTO metric_point(instance_id, metric_name, metric_value, unit, sampled_at)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT(instance_id, metric_name, sampled_at) DO UPDATE SET
        metric_value = excluded.metric_value,
        unit = excluded.unit
      """,
      metricPoint.instanceId(),
      metricPoint.metricName(),
      metricPoint.value(),
      metricPoint.unit(),
      metricPoint.sampledAt()
    );
  }

  public void deleteSince(String sampledAt) {
    jdbcTemplate.update("DELETE FROM metric_point WHERE sampled_at >= ?", sampledAt);
  }

  public List<MetricPoint> findSeries(String instanceId, String metricName, int limit) {
    List<MetricPoint> points = jdbcTemplate.query("""
      SELECT instance_id, metric_name, metric_value, unit, sampled_at
      FROM metric_point
      WHERE instance_id = ? AND metric_name = ?
      ORDER BY sampled_at DESC
      LIMIT ?
      """, (resultSet, rowNum) -> mapMetric(resultSet), instanceId, metricName, limit);
    Collections.reverse(points);
    return points;
  }

  public List<MetricPoint> findSeries(String instanceId, String metricName, HistoryWindow window) {
    String sql = window.useDailyAverages() ? """
      SELECT instance_id, metric_name, AVG(metric_value) AS metric_value, unit,
        substr(sampled_at, 1, 10) || 'T00:00:00Z' AS sampled_at
      FROM metric_point WHERE instance_id = ? AND metric_name = ? AND sampled_at >= ? AND sampled_at < ?
      GROUP BY instance_id, metric_name, unit, substr(sampled_at, 1, 10) ORDER BY sampled_at
      """ : """
      SELECT instance_id, metric_name, metric_value, unit, sampled_at
      FROM metric_point WHERE instance_id = ? AND metric_name = ? AND sampled_at >= ? AND sampled_at < ?
      ORDER BY sampled_at
      """;
    return jdbcTemplate.query(sql, (row, index) -> mapMetric(row), instanceId, metricName, window.start(), window.endExclusive());
  }

  public MetricDates findDates(String instanceId) {
    return jdbcTemplate.queryForObject("""
      SELECT substr(MIN(sampled_at), 1, 10), substr(MAX(sampled_at), 1, 10)
      FROM metric_point WHERE instance_id = ?
      """, (row, index) -> new MetricDates(row.getString(1), row.getString(2)), instanceId);
  }

  public record MetricDates(String firstDay, String lastDay) {}

  public double average(String metricName) {
    Double value = jdbcTemplate.queryForObject("""
      SELECT COALESCE(AVG(metric_value), 0)
      FROM metric_point
      WHERE metric_name = ?
      """, Double.class, metricName);
    return value == null ? 0 : value;
  }

  public double latest(String instanceId, String metricName) {
    return latestOptional(instanceId, metricName).orElse(0);
  }

  public OptionalDouble latestOptional(String instanceId, String metricName) {
    List<Double> values = jdbcTemplate.query("""
      SELECT metric_value
      FROM metric_point
      WHERE instance_id = ? AND metric_name = ?
      ORDER BY sampled_at DESC
      LIMIT 1
      """, (resultSet, rowNum) -> resultSet.getDouble("metric_value"), instanceId, metricName);
    return values.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(values.get(0));
  }

  private MetricPoint mapMetric(ResultSet resultSet) throws SQLException {
    return new MetricPoint(
      resultSet.getString("instance_id"),
      resultSet.getString("metric_name"),
      resultSet.getDouble("metric_value"),
      resultSet.getString("unit"),
      resultSet.getString("sampled_at")
    );
  }
}
