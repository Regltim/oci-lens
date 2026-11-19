package org.ociarmmonitor.traffic;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.stereotype.Repository;

@Repository
public class TrafficRepository {

  private final JdbcTemplate jdbcTemplate;

  public TrafficRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void save(TrafficDaily trafficDaily) {
    jdbcTemplate.update("""
      INSERT INTO traffic_daily(instance_id, stat_date, ingress_gb, egress_gb)
      VALUES (?, ?, ?, ?)
      ON CONFLICT(instance_id, stat_date) DO UPDATE SET
        ingress_gb = excluded.ingress_gb,
        egress_gb = excluded.egress_gb
      """,
      trafficDaily.instanceId(),
      trafficDaily.statDate(),
      trafficDaily.ingressGb(),
      trafficDaily.egressGb()
    );
  }

  public void deleteSince(String statDate) {
    jdbcTemplate.update("DELETE FROM traffic_daily WHERE stat_date >= ?", statDate);
  }

  public List<TrafficDaily> listCurrentMonth() {
    return listMonth(YearMonth.now(ZoneOffset.UTC));
  }

  public List<TrafficDaily> listMonth(YearMonth month) {
    String monthPrefix = month.toString();
    return jdbcTemplate.query("""
      SELECT instance_id, stat_date, ingress_gb, egress_gb
      FROM traffic_metric_history
      WHERE stat_date LIKE ?
      ORDER BY stat_date ASC
      """, (resultSet, rowNum) -> mapTraffic(resultSet), monthPrefix + "%");
  }

  public List<TrafficDaily> listLegacyMonth(YearMonth month) {
    return jdbcTemplate.query("""
      SELECT instance_id, stat_date, ingress_gb, egress_gb FROM legacy_traffic_daily
      WHERE stat_date >= ? AND stat_date < ? ORDER BY stat_date
      """, (row, index) -> mapTraffic(row), month.atDay(1).toString(), month.plusMonths(1).atDay(1).toString());
  }

  public List<String> listMonths(boolean legacy) {
    String table = legacy ? "legacy_traffic_daily" : "traffic_metric_history";
    return jdbcTemplate.queryForList("SELECT DISTINCT substr(stat_date, 1, 7) AS month FROM "
      + table + " ORDER BY month DESC", String.class);
  }

  public record InstanceMonth(String instanceId, Double ingressGiB, Double egressGiB,
                              int observedDays, int completeDays, int elapsedDays) {}

  public InstanceMonth instanceMonth(YearMonth month, String instanceId) {
    LocalDate start = month.atDay(1);
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate end = month.plusMonths(1).atDay(1);
    if (end.isAfter(today)) end = today;
    int elapsedDays = (int) Math.max(0, ChronoUnit.DAYS.between(start, end));
    return jdbcTemplate.queryForObject("""
      SELECT SUM(ingress_gb), SUM(egress_gb), COUNT(*), COALESCE(SUM(complete), 0)
      FROM traffic_metric_history WHERE instance_id = ? AND stat_date >= ? AND stat_date < ?
      """, (row, index) -> new InstanceMonth(instanceId, (Double) JdbcUtils.getResultSetValue(row, 1, Double.class),
      (Double) JdbcUtils.getResultSetValue(row, 2, Double.class), row.getInt(3), row.getInt(4), elapsedDays),
      instanceId, start.toString(), end.toString());
  }

  public double sumIngressForCurrentMonth() {
    return sumMonth("ingress_gb", YearMonth.now(ZoneOffset.UTC));
  }

  public double sumEgressForCurrentMonth() {
    return sumMonth("egress_gb", YearMonth.now(ZoneOffset.UTC));
  }

  public double sumIngressForMonth(YearMonth month) {
    return sumMonth("ingress_gb", month);
  }

  public double sumEgressForMonth(YearMonth month) {
    return sumMonth("egress_gb", month);
  }

  public double egressByInstanceAndDate(String instanceId, LocalDate date) {
    Double value = egressSnapshot(instanceId, date);
    return value == null ? 0 : value;
  }

  public Double egressSnapshot(String instanceId, LocalDate date) {
    return jdbcTemplate.queryForObject("""
      SELECT SUM(egress_gb)
      FROM traffic_metric_history
      WHERE instance_id = ? AND stat_date = ?
      """, Double.class, instanceId, date.toString());
  }

  private double sumMonth(String columnName, YearMonth month) {
    String monthPrefix = month.toString();
    Double value = jdbcTemplate.queryForObject("""
      SELECT COALESCE(SUM(%s), 0)
      FROM traffic_metric_history
      WHERE stat_date LIKE ?
      """.formatted(columnName), Double.class, monthPrefix + "%");
    return value == null ? 0 : value;
  }

  private TrafficDaily mapTraffic(ResultSet resultSet) throws SQLException {
    return new TrafficDaily(
      resultSet.getString("instance_id"),
      resultSet.getString("stat_date"),
      resultSet.getDouble("ingress_gb"),
      resultSet.getDouble("egress_gb")
    );
  }
}
