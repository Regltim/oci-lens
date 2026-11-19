package org.ociarmmonitor.oci;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.ociarmmonitor.instance.CloudInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LensLedger {
  private final JdbcTemplate jdbc;

  public LensLedger(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public record CoverageDay(String statDate, String status, String message) {}
  public record CostEntry(String serviceName, String resourceId, String statDate, String currency, String costAmount) {}
  public record Change(String title, String description) {}
  public record Budget(String month, String currency, String amount, String usedAmount, int reachedThreshold) {}

  public void coverage(String source, LocalDate date, String status, String message) {
    jdbc.update("""
      INSERT INTO source_coverage(source, stat_date, status, message) VALUES (?, ?, ?, ?)
      ON CONFLICT (source, stat_date) DO UPDATE SET status = excluded.status, message = excluded.message
      WHERE source_coverage.status != 'COVERED' OR excluded.status = 'COVERED'
      """, source, date.toString(), status, message);
  }

  public List<CoverageDay> coverage(String source, YearMonth month) {
    return jdbc.query("SELECT stat_date, status, message FROM source_coverage WHERE source = ? AND stat_date LIKE ? ORDER BY stat_date",
      (rs, i) -> new CoverageDay(rs.getString(1), rs.getString(2), rs.getString(3)), source, month + "%");
  }

  public boolean covered(String source, LocalDate day) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM source_coverage WHERE source = ? AND stat_date = ? AND status = 'COVERED'", Integer.class, source, day.toString()) > 0;
  }

  public String coveredThrough(String source, YearMonth month) {
    LocalDate expected = month.atDay(1);
    String through = null;
    for (CoverageDay row : coverage(source, month)) {
      if (!row.statDate().equals(expected.toString()) || !row.status().equals("COVERED")) break;
      through = row.statDate();
      expected = expected.plusDays(1);
    }
    return through;
  }

  public List<CostEntry> costs(YearMonth month) {
    return jdbc.query("SELECT service_name, resource_id, stat_date, currency, cost_amount FROM cost_daily WHERE stat_date LIKE ? ORDER BY stat_date DESC, service_name, currency",
      (rs, i) -> new CostEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), month + "%");
  }

  public void publishCosts(LocalDate first, LocalDate end, List<CostEntry> entries) {
    jdbc.update("DELETE FROM cost_daily WHERE stat_date >= ? AND stat_date < ?", first.toString(), end.toString());
    for (CostEntry row : entries) jdbc.update("""
      INSERT INTO cost_daily(service_name,resource_id,stat_date,usage_amount,usage_unit,cost_amount,currency) VALUES (?,?,?,0,'-',?,?)
      """, row.serviceName(), row.resourceId(), row.statDate(), row.costAmount(), row.currency());
    LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(3);
    for (LocalDate date = first; date.isBefore(end); date = date.plusDays(1)) {
      boolean mature = !date.isAfter(cutoff);
      coverage("cost", date, mature ? "COVERED" : "PENDING", mature ? "账单查询已核对" : "账单可能仍有延迟，后续自动复核");
    }
    YearMonth firstMonth = YearMonth.from(first);
    YearMonth lastMonth = YearMonth.from(end.minusDays(1));
    for (YearMonth month = firstMonth; !month.isAfter(lastMonth); month = month.plusMonths(1)) evaluateBudgets(month);
  }

  public void recordInventoryChanges(List<CloudInstance> previous, List<CloudInstance> current) {
    Map<String, CloudInstance> prior = previous.stream().collect(Collectors.toMap(CloudInstance::id, Function.identity()));
    var changes = new ArrayList<Change>();
    for (CloudInstance instance : current) {
      CloudInstance old = prior.remove(instance.id());
      if (old == null) changes.add(new Change("发现新实例", instance.displayName()));
      else if (!instance.lifecycleState().equals(old.lifecycleState())) changes.add(new Change("实例状态变化", instance.displayName() + " · " + instance.lifecycleState()));
    }
    prior.values().forEach(removed -> changes.add(new Change("实例移出采集范围", removed.displayName())));
    jdbc.update("DELETE FROM sync_change");
    changes.forEach(change -> jdbc.update("INSERT INTO sync_change(title, description) VALUES (?, ?)", change.title(), change.description()));
  }

  public List<Change> changes() {
    List<Change> changes = new ArrayList<>(jdbc.query("SELECT title, description FROM sync_change ORDER BY id DESC LIMIT 8", (rs, i) -> new Change(rs.getString(1), rs.getString(2))));
    budgets(YearMonth.now(ZoneOffset.UTC)).stream().filter(budget -> budget.reachedThreshold() > 0).forEach(budget ->
      changes.add(new Change("月度预算提醒", "%s 已完成预算的 %s%% 额度检查".formatted(budget.currency(), budget.reachedThreshold()))));
    return changes;
  }

  public List<Budget> budgets(YearMonth month) {
    String through = coveredThrough("cost", month);
    List<CostEntry> entries = costs(month);
    return jdbc.query("SELECT currency, amount, reached_threshold FROM cost_budget WHERE month = ? ORDER BY currency", (rs, i) -> {
      String currency = rs.getString(1);
      BigDecimal used = entries.stream().filter(row -> row.currency().equals(currency) && through != null && row.statDate().compareTo(through) <= 0)
        .map(row -> new BigDecimal(row.costAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
      return new Budget(month.toString(), currency, rs.getString(2), used.toPlainString(), rs.getInt(3));
    }, month.toString());
  }

  public Budget saveBudget(YearMonth month, String currency, BigDecimal amount) {
    jdbc.update("""
      INSERT INTO cost_budget(month,currency,amount) VALUES(?,?,?)
      ON CONFLICT (month,currency) DO UPDATE SET amount = excluded.amount
      """, month.toString(), currency, amount.toPlainString());
    evaluateBudgets(month);
    return budgets(month).stream().filter(row -> row.currency().equals(currency)).findFirst().orElseThrow();
  }

  private void evaluateBudgets(YearMonth month) {
    String through = coveredThrough("cost", month);
    LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(3);
    LocalDate expected = month.atEndOfMonth().isBefore(cutoff) ? month.atEndOfMonth() : cutoff;
    if (through == null || through.compareTo(expected.toString()) < 0) return;
    for (Budget row : budgets(month)) {
      BigDecimal used = new BigDecimal(row.usedAmount());
      BigDecimal limit = new BigDecimal(row.amount());
      int threshold = used.compareTo(limit) >= 0 ? 100 : used.compareTo(limit.multiply(new BigDecimal("0.8"))) >= 0 ? 80 : 0;
      if (threshold > row.reachedThreshold()) jdbc.update("UPDATE cost_budget SET reached_threshold = MAX(reached_threshold, ?) WHERE month = ? AND currency = ?", threshold, month.toString(), row.currency());
    }
  }
}
