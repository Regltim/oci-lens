package org.ociarmmonitor.oci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
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

  private SingleConnectionDataSource storage() {
    var storage = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(storage);
    return storage;
  }
}
