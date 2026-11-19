package org.ociarmmonitor.oci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.ociarmmonitor.common.HistoryWindow;
import org.ociarmmonitor.instance.CloudInstance;
import org.ociarmmonitor.instance.CloudInstanceRepository;
import org.ociarmmonitor.instance.MetricPoint;
import org.ociarmmonitor.instance.MetricRepository;
import org.ociarmmonitor.serverstatus.ServerStatusRepository;
import org.ociarmmonitor.traffic.TrafficRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class LensStorageTest {
  @Test
  void guardsRequestAndPointCapsAcrossServiceInstances() {
    try (var storage = storage()) {
      JdbcTemplate jdbc = new JdbcTemplate(storage);
      var tx = new TransactionTemplate(new DataSourceTransactionManager(storage));
      tx.executeWithoutResult(status -> new OciBudgetGate(jdbc, 2, 5).reserve(3));
      assertThatThrownBy(() -> tx.executeWithoutResult(status -> new OciBudgetGate(jdbc, 2, 5).reserve(3)))
        .isInstanceOf(OciBudgetGate.BudgetExceeded.class);
      tx.executeWithoutResult(status -> new OciBudgetGate(jdbc, 2, 5).reserve(1));
      assertThatThrownBy(() -> tx.executeWithoutResult(status -> new OciBudgetGate(jdbc, 2, 5).reserve(0)))
        .isInstanceOf(OciBudgetGate.BudgetExceeded.class);
      assertThat(jdbc.queryForObject("SELECT requests FROM oci_budget_usage", Integer.class)).isEqualTo(2);
      assertThat(jdbc.queryForObject("SELECT points FROM oci_budget_usage", Long.class)).isEqualTo(4L);
    }
  }

  @Test
  void keepsCurrenciesSeparateAndRestoresSnapshotOnFailedPublish() {
    try (var storage = storage()) {
      JdbcTemplate jdbc = new JdbcTemplate(storage);
      var ledger = new LensLedger(jdbc);
      var tx = new TransactionTemplate(new DataSourceTransactionManager(storage));
      LocalDate date = LocalDate.now(ZoneOffset.UTC).minusDays(40);
      var rows = List.of(new LensLedger.CostEntry("Compute", "-", date.toString(), "USD", "0.10"),
        new LensLedger.CostEntry("Compute", "-", date.toString(), "CNY", "0.20"));
      tx.executeWithoutResult(status -> ledger.publishCosts(date, date.plusDays(1), rows));
      assertThat(ledger.costs(YearMonth.from(date))).containsExactlyInAnyOrderElementsOf(rows);
      assertThatThrownBy(() -> tx.executeWithoutResult(status -> ledger.publishCosts(date, date.plusDays(1), List.of(rows.get(0), rows.get(0)))))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasRootCauseInstanceOf(org.sqlite.SQLiteException.class);
      assertThat(ledger.costs(YearMonth.from(date))).containsExactlyInAnyOrderElementsOf(rows);
    }
  }

  @Test
  void retainsArchivedInstanceNamesAndDateFilteredMetricsAfterInventoryRefresh() {
    try (var storage = storage()) {
      JdbcTemplate jdbc = new JdbcTemplate(storage);
      var instances = new CloudInstanceRepository(jdbc);
      var metrics = new MetricRepository(jdbc);
      instances.save(new CloudInstance("old-id", "Old host", "ap-tokyo-1", "scope", "VM.Standard.A1.Flex",
        "RUNNING", 4, 24, 50, "", "", "2026-04-01T00:00:00Z", "2026-04-01T00:00:00Z"));
      metrics.insert(new MetricPoint("old-id", "cpu_utilization", 20, "%", "2026-04-01T00:00:00.123Z"));
      metrics.insert(new MetricPoint("old-id", "cpu_utilization", 40, "%", "2026-04-01T03:00:00Z"));
      metrics.insert(new MetricPoint("old-id", "cpu_utilization", 75, "%", "2026-05-01T00:00:00Z"));
      instances.deleteByIds(List.of());
      assertThat(instances.findAll()).isEmpty();
      assertThat(instances.listHistory()).singleElement().satisfies(item -> {
        assertThat(item.displayName()).isEqualTo("Old host");
        assertThat(item.firstDay()).isEqualTo("2026-04-01");
        assertThat(item.lastDay()).isEqualTo("2026-05-01");
      });
      var window = HistoryWindow.parse("2026-04-01", "2026-04-30");
      assertThat(metrics.findSeries("old-id", "cpu_utilization", window)).singleElement()
        .satisfies(item -> assertThat(item.value()).isEqualTo(30));
    }
  }

  @Test
  void showsImportedHostAndTrafficInOrdinaryHistoryWithoutCountingDuplicates() {
    try (var storage = storage()) {
      JdbcTemplate jdbc = new JdbcTemplate(storage);
      jdbc.update("INSERT INTO legacy_host_snapshot VALUES ('旧站主机', '2026-02-01T08:00:00Z', 20, 30, 40, 50, 60)");
      jdbc.update("INSERT INTO legacy_traffic_daily VALUES ('old-id', '2026-02-01', 2, 3)");
      var host = new ServerStatusRepository(jdbc, 72);
      var traffic = new TrafficRepository(jdbc);
      host.deleteExpired();
      assertThat(host.listHistorySources()).singleElement().satisfies(item -> assertThat(item.host()).isEqualTo("旧站主机"));
      var window = HistoryWindow.parse("2026-02-01", "2026-02-01");
      assertThat(host.listHistory(window, "旧站主机")).hasSize(1);
      assertThat(host.listHistory(window, "")).singleElement().satisfies(sample ->
        assertThat(sample.cpuUsagePercent()).isEqualTo(20.0));
      var month = YearMonth.of(2026, 2);
      assertThat(traffic.listLegacyMonth(month)).hasSize(1);
      assertThat(traffic.listMonth(month)).singleElement().satisfies(row -> assertThat(row.egressGb()).isEqualTo(3));
      assertThat(traffic.listMonths(false)).containsExactly("2026-02");
      assertThat(traffic.instanceMonth(month, "old-id").completeDays()).isZero();
      traffic.save(new org.ociarmmonitor.traffic.TrafficDaily("old-id", "2026-02-01", 5, 6));
      var monthUsage = traffic.instanceMonth(month, "old-id");
      assertThat(monthUsage.egressGiB()).isEqualTo(6);
      assertThat(monthUsage.observedDays()).isEqualTo(1);
      assertThat(monthUsage.completeDays()).isEqualTo(1);
      assertThat(traffic.instanceMonth(month, "unknown-id").egressGiB()).isNull();
    }
  }

  private SingleConnectionDataSource storage() {
    var storage = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(storage);
    return storage;
  }
}
