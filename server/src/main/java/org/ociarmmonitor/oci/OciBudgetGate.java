package org.ociarmmonitor.oci;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.YearMonth;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OciBudgetGate {
  private final JdbcTemplate jdbc;
  private final int requestsPerDay;
  private final long pointsPerMonth;

  public OciBudgetGate(JdbcTemplate jdbc, @Value("${monitor.oci.requests-per-day:80}") int requestsPerDay,
                       @Value("${monitor.oci.points-per-month:100000000}") long pointsPerMonth) {
    this.jdbc = jdbc;
    this.requestsPerDay = Math.max(0, requestsPerDay);
    this.pointsPerMonth = Math.max(0, pointsPerMonth);
  }

  @Transactional
  public void reserve(long estimatedPoints) {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate first = YearMonth.from(today).atDay(1);
    long used = jdbc.queryForObject("SELECT COALESCE(SUM(points), 0) FROM oci_budget_usage WHERE utc_day >= ? AND utc_day < ?", Long.class, first.toString(), first.plusMonths(1).toString());
    int count = jdbc.queryForObject("SELECT COALESCE(MAX(requests), 0) FROM oci_budget_usage WHERE utc_day = ?", Integer.class, today.toString());
    if (count >= requestsPerDay || estimatedPoints > pointsPerMonth - used) throw new BudgetExceeded();
    jdbc.update("""
      INSERT INTO oci_budget_usage(utc_day, requests, points) VALUES (?, 1, ?)
      ON CONFLICT (utc_day) DO UPDATE SET requests = requests + 1, points = points + excluded.points
      """, today.toString(), estimatedPoints);
  }

  public static long estimatePoints(Instant start, Instant end) {
    // Each query is scoped to one metric and resource. Reserve one point per second.
    return Math.max(1, Duration.between(start, end).toSeconds() + 1);
  }

  public static final class BudgetExceeded extends IllegalStateException {
    public BudgetExceeded() { super("OCI 读取达到本应用的用量上限，已保留上次成功数据。"); }
  }
}
