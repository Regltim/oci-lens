package org.ociarmmonitor.oci;

import com.oracle.bmc.usageapi.UsageapiClient;
import com.oracle.bmc.usageapi.model.RequestSummarizedUsagesDetails;
import com.oracle.bmc.usageapi.model.UsageSummary;
import com.oracle.bmc.usageapi.requests.RequestSummarizedUsagesRequest;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.ociarmmonitor.config.FreeQuotaRepository;
import org.ociarmmonitor.config.OciSettingsProvider;
import org.ociarmmonitor.instance.CloudInstance;
import org.ociarmmonitor.instance.CloudInstanceRepository;
import org.ociarmmonitor.traffic.TrafficRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OciQuotaService {
  private static final Logger LOGGER = LoggerFactory.getLogger(OciQuotaService.class);
  private static final String A1_SHAPE = "VM.Standard.A1.Flex";
  private static final BigDecimal GIB_TO_GB = new BigDecimal("1.073741824");
  private final FreeQuotaRepository limits;
  private final CloudInstanceRepository instances;
  private final OciSettingsProvider settings;
  private final OciVolumeService volumes;
  private final OciBudgetGate budget;
  private final TrafficRepository traffic;
  private final JdbcTemplate jdbc;

  public OciQuotaService(FreeQuotaRepository limits, CloudInstanceRepository instances, OciSettingsProvider settings,
                         OciVolumeService volumes, OciBudgetGate budget, JdbcTemplate jdbc, TrafficRepository traffic) {
    this.limits = limits;
    this.instances = instances;
    this.settings = settings;
    this.volumes = volumes;
    this.budget = budget;
    this.traffic = traffic;
    this.jdbc = jdbc;
  }

  public record Item(String key, String name, String unit, double limit, Double used, String status,
                     String scope, String updatedAt, String requestedThrough) {}
  public record Summary(String month, Double runningA1Ocpus, Double runningA1MemoryGb, List<Item> items,
                        TrafficRepository.InstanceMonth instanceTraffic, OciVolumeService.Failure volumeFailure) {}
  private record Snapshot(String status, String updatedAt, String requestedThrough, String computeRegion, Double cpu, Double memory, Double outbound) {}
  private enum Meter { A1_CPU, A1_MEMORY, EGRESS }

  @Transactional(readOnly = true)
  public Summary summary(YearMonth month) {
    var quota = limits.getQuota();
    var disk = volumes.status();
    var usage = snapshot(month);
    boolean connected = settings.isConfigured(settings.getSettings());
    String billingStatus = settings.billingEnabled() ? usage.status() : "DISABLED";
    if (!connected && "PENDING".equals(billingStatus)) billingStatus = "NOT_CONFIGURED";
    boolean homeRegionKnown = disk.homeRegion() != null;
    boolean computeScoped = usage.computeRegion() != null;
    String computeScope = computeScoped ? "租户主区域 " + usage.computeRegion() : "租户全部区域 · 主区域待确认";
    String volumeStatus = !connected && "PENDING".equals(disk.status()) ? "NOT_CONFIGURED" : disk.status();
    String volumeScope = disk.region() == null ? "OCI 主区域的启动卷与块卷"
      : disk.region() + " · 当前 Compartment" + (homeRegionKnown ? "" : " · 主区域待确认");
    boolean currentMonth = month.equals(YearMonth.now(ZoneOffset.UTC));
    boolean outsideHome = homeRegionKnown && !disk.homeRegion().equals(disk.region());
    Long total = disk.succeededAt() == null || !currentMonth ? null : volumes.totalGb();
    String diskState = !currentMonth ? "NOT_APPLICABLE" : outsideHome && "AVAILABLE".equals(volumeStatus) ? "OUTSIDE_HOME"
      : "AVAILABLE".equals(volumeStatus) && total == null ? "NO_DATA"
      : "AVAILABLE".equals(volumeStatus) ? "SCOPED" : volumeStatus;
    List<CloudInstance> inventory = instances.findAll();
    String instanceId = settings.localInstanceId();
    if ((instanceId == null || instanceId.isBlank()) && inventory.size() == 1) instanceId = inventory.getFirst().id();
    var instanceTraffic = instanceId == null || instanceId.isBlank() ? null : traffic.instanceMonth(month, instanceId);
    List<CloudInstance> a1 = inventory.stream()
      .filter(instance -> A1_SHAPE.equals(instance.shape()) && "RUNNING".equals(instance.lifecycleState())).toList();
    return new Summary(month.toString(),
      a1.isEmpty() || a1.stream().anyMatch(item -> item.ocpus() <= 0) ? null : a1.stream().mapToDouble(CloudInstance::ocpus).sum(),
      a1.isEmpty() || a1.stream().anyMatch(item -> item.memoryGb() <= 0) ? null : a1.stream().mapToDouble(CloudInstance::memoryGb).sum(),
      List.of(
        usageItem("a1-cpu", "A1 OCPU", "OCPU 小时", quota.ampereOcpuHours(), usage.cpu(), billingStatus, computeScope, usage, computeScoped),
        usageItem("a1-memory", "A1 内存", "GB 小时", quota.ampereMemoryGbHours(), usage.memory(), billingStatus, computeScope, usage, computeScoped),
        new Item("volume", "启动卷与块卷", "GB", quota.blockVolumeGb(), total == null ? null : total.doubleValue(), diskState,
          volumeScope, currentMonth ? disk.succeededAt() : null, null),
        usageItem("egress", "OCI 出站流量", "GB", quota.outboundDataTransferGb(), usage.outbound(), billingStatus, "租户计量 · 全部区域", usage, true)
      ), instanceTraffic, disk.failure());
  }

  private Item usageItem(String key, String name, String unit, double limit, Double used, String status,
                         String scope, Snapshot usage, boolean canCompare) {
    String itemStatus = "AVAILABLE".equals(status) ? used == null ? "NO_DATA" : canCompare ? "AVAILABLE" : "SCOPED" : status;
    return new Item(key, name, unit, limit, used, itemStatus, scope, usage.updatedAt(), usage.requestedThrough());
  }

  private Snapshot snapshot(YearMonth month) {
    return jdbc.query("""
      SELECT status, succeeded_at, requested_through, compute_region, ocpu_hours, memory_gb_hours, outbound_gb
      FROM oci_usage_snapshot WHERE month = ?
      """, (row, index) -> new Snapshot(row.getString(1), row.getString(2), row.getString(3),
      row.getString(4), quantity(row, 5), quantity(row, 6), quantity(row, 7)), month.toString())
      .stream().findFirst().orElse(new Snapshot("PENDING", null, null, null, null, null, null));
  }

  private Double quantity(ResultSet row, int column) throws SQLException {
    double value = row.getDouble(column);
    return row.wasNull() ? null : value;
  }

  public void refresh(UsageapiClient client, String tenancyId, LocalDate today) {
    YearMonth month = YearMonth.from(today);
    LocalDate first = month.atDay(1);
    if (!first.isBefore(today)) return;
    String attempt = Instant.now().toString();
    String homeRegion = volumes.status().homeRegion();
    try {
      if (tenancyId == null || tenancyId.isBlank()) throw new IllegalStateException("OCI tenancy unavailable");
      var details = RequestSummarizedUsagesDetails.builder()
        .tenantId(tenancyId)
        .timeUsageStarted(Date.from(first.atStartOfDay().toInstant(ZoneOffset.UTC)))
        .timeUsageEnded(Date.from(today.atStartOfDay().toInstant(ZoneOffset.UTC)))
        .granularity(RequestSummarizedUsagesDetails.Granularity.Daily)
        .queryType(RequestSummarizedUsagesDetails.QueryType.Usage)
        .isAggregateByTime(false)
        .groupBy(List.of("skuPartNumber", "unit", "region"))
        .build();
      var totals = new EnumMap<Meter, BigDecimal>(Meter.class);
      var mismatched = EnumSet.noneOf(Meter.class);
      String next = null;
      do {
        budget.reserve(0);
        var response = client.requestSummarizedUsages(RequestSummarizedUsagesRequest.builder()
          .requestSummarizedUsagesDetails(details).limit(1000).page(next).build());
        var usage = response.getUsageAggregation();
        if (usage != null && usage.getItems() != null) {
          for (UsageSummary row : usage.getItems()) add(row, homeRegion, totals, mismatched);
        }
        next = response.getOpcNextPage();
      } while (next != null && !next.isBlank());
      mismatched.forEach(totals::remove);
      if (totals.isEmpty()) {
        recordFailure(month, "NO_DATA", attempt);
        return;
      }
      jdbc.update("""
        INSERT INTO oci_usage_snapshot VALUES (?, 'AVAILABLE', ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(month) DO UPDATE SET status = excluded.status, attempted_at = excluded.attempted_at,
          succeeded_at = excluded.succeeded_at, requested_through = excluded.requested_through, compute_region = excluded.compute_region,
          ocpu_hours = excluded.ocpu_hours, memory_gb_hours = excluded.memory_gb_hours, outbound_gb = excluded.outbound_gb
        """, month.toString(), attempt, Instant.now().toString(), today.minusDays(1).toString(), homeRegion,
        totals.get(Meter.A1_CPU), totals.get(Meter.A1_MEMORY), totals.get(Meter.EGRESS));
    } catch (RuntimeException failure) {
      String state = OciVolumeService.sourceFailure(failure);
      recordFailure(month, state, attempt);
      if (failure instanceof OciBudgetGate.BudgetExceeded) throw failure;
      LOGGER.warn("OCI usage quantity refresh failed ({})", state);
    }
  }

  void recordFailure(YearMonth month, String status, String attempt) {
    jdbc.update("""
      INSERT INTO oci_usage_snapshot(month, status, attempted_at) VALUES (?, ?, ?)
      ON CONFLICT(month) DO UPDATE SET status = excluded.status, attempted_at = excluded.attempted_at
      """, month.toString(), status, attempt);
  }

  private void add(UsageSummary row, String homeRegion, Map<Meter, BigDecimal> totals, Set<Meter> mismatched) {
    String sku = row.getSkuPartNumber();
    if (sku == null) return;
    Meter meter = switch (sku) {
      case "B93297" -> Meter.A1_CPU;
      case "B93298" -> Meter.A1_MEMORY;
      case "B88327", "B93455", "B93456" -> Meter.EGRESS;
      default -> null;
    };
    if (meter == null) return;
    if (meter != Meter.EGRESS && homeRegion != null) {
      if (row.getRegion() == null || row.getRegion().isBlank()) {
        mismatched.add(meter);
        return;
      }
      if (!homeRegion.equalsIgnoreCase(row.getRegion())) return;
    }
    String unit = row.getUnit() == null ? "" : row.getUnit().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    BigDecimal amount = row.getComputedQuantity();
    if (amount == null || !validUnit(meter, unit)) {
      mismatched.add(meter);
      return;
    }
    totals.merge(meter, meter == Meter.EGRESS && unit.equals("gib") ? amount.multiply(GIB_TO_GB) : amount, BigDecimal::add);
  }

  private boolean validUnit(Meter meter, String unit) {
    return switch (meter) {
      case A1_CPU -> Set.of("hour", "hours", "ocpuhour", "ocpuhours", "ocpuperhour").contains(unit);
      case A1_MEMORY -> Set.of("gbhour", "gbhours", "gbhr", "gbhrs", "gigabytehour", "gigabytehours",
        "gigabyteperhour", "gbperhour").contains(unit);
      case EGRESS -> Set.of("gb", "gib", "gigabyte", "gigabytes", "gigabyteoutbounddatatransferpermonth").contains(unit);
    };
  }
}
