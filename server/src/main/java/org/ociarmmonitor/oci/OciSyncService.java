package org.ociarmmonitor.oci;

import com.oracle.bmc.auth.AuthenticationDetailsProvider;
import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.model.InstanceShapeConfig;
import com.oracle.bmc.core.model.Vnic;
import com.oracle.bmc.core.model.VnicAttachment;
import com.oracle.bmc.core.requests.GetVnicRequest;
import com.oracle.bmc.core.requests.ListInstancesRequest;
import com.oracle.bmc.core.requests.ListVnicAttachmentsRequest;
import com.oracle.bmc.core.responses.ListInstancesResponse;
import com.oracle.bmc.core.responses.ListVnicAttachmentsResponse;
import com.oracle.bmc.monitoring.MonitoringClient;
import com.oracle.bmc.monitoring.model.AggregatedDatapoint;
import com.oracle.bmc.monitoring.model.MetricData;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.monitoring.model.SummarizeMetricsDataDetails;
import com.oracle.bmc.monitoring.requests.SummarizeMetricsDataRequest;
import com.oracle.bmc.usageapi.UsageapiClient;
import com.oracle.bmc.usageapi.model.RequestSummarizedUsagesDetails;
import com.oracle.bmc.usageapi.model.UsageAggregation;
import com.oracle.bmc.usageapi.model.UsageSummary;
import com.oracle.bmc.usageapi.requests.RequestSummarizedUsagesRequest;
import com.oracle.bmc.usageapi.responses.RequestSummarizedUsagesResponse;
import org.ociarmmonitor.config.OciSettings;
import org.ociarmmonitor.config.OciSettingsProvider;
import org.ociarmmonitor.instance.CloudInstance;
import org.ociarmmonitor.instance.CloudInstanceRepository;
import org.ociarmmonitor.instance.MetricPoint;
import org.ociarmmonitor.instance.MetricRepository;
import org.ociarmmonitor.traffic.TrafficDaily;
import org.ociarmmonitor.traffic.TrafficRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OciSyncService {

  private static final Logger LOGGER = LoggerFactory.getLogger(OciSyncService.class);

  private final OciSettingsProvider ociSettingsProvider;
  private final OciClientFactory ociClientFactory;
  private final CloudInstanceRepository cloudInstanceRepository;
  private final MetricRepository metricRepository;
  private final TrafficRepository trafficRepository;
  private final LensLedger ledger;
  private final OciBudgetGate budget;
  private final TransactionTemplate transactions;
  private final OciTarget target;
  private final SyncRunRepository syncRunRepository;
  private final TaskExecutor syncTaskExecutor;
  private final OciVolumeService volumes;
  private final OciQuotaService quotas;
  private final AtomicBoolean syncRunning = new AtomicBoolean(false);

  public OciSyncService(
    OciSettingsProvider ociSettingsProvider,
    OciClientFactory ociClientFactory,
    CloudInstanceRepository cloudInstanceRepository,
    MetricRepository metricRepository,
    TrafficRepository trafficRepository,
    LensLedger ledger,
    OciBudgetGate budget,
    TransactionTemplate transactions,
    OciTarget target,
    SyncRunRepository syncRunRepository,
    @Qualifier("syncTaskExecutor") TaskExecutor syncTaskExecutor,
    OciVolumeService volumes,
    OciQuotaService quotas
  ) {
    this.ociSettingsProvider = ociSettingsProvider;
    this.ociClientFactory = ociClientFactory;
    this.cloudInstanceRepository = cloudInstanceRepository;
    this.metricRepository = metricRepository;
    this.trafficRepository = trafficRepository;
    this.ledger = ledger;
    this.budget = budget;
    this.transactions = transactions;
    this.target = target;
    this.syncRunRepository = syncRunRepository;
    this.syncTaskExecutor = syncTaskExecutor;
    this.volumes = volumes;
    this.quotas = quotas;
  }

  public SyncResult syncResources() {
    return syncResources("FULL");
  }

  public SyncResult syncScheduledResources() {
    if (!ociSettingsProvider.isConfigured(ociSettingsProvider.getSettings()) || !target.isVerified()) {
      return new SyncResult("NOT_CONFIGURED", "等待 OCI 连接验证。", "", "", 0, 0, 0, 0);
    }
    return syncResources("SCHEDULED");
  }

  public SyncResult backfill(String source, LocalDate date) {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    if (date == null || !date.isBefore(today) || date.isBefore(today.minusDays(89))
      || (!"traffic".equals(source) && !"cost".equals(source))) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "请选择近 89 天内的完整 UTC 日期及有效来源。");
    }
    if ("cost".equals(source) && !ociSettingsProvider.billingEnabled()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "账单读取尚未开启。");
    }
    return syncResources("BACKFILL", source, date);
  }

  public SyncResult syncResources(String syncType) {
    return syncResources(syncType, null, null);
  }

  private SyncResult syncResources(String syncType, String source, LocalDate date) {
    if (!syncRunning.compareAndSet(false, true)) {
      if (date != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "已有同步任务，请在同步记录查看。");
      return latestRunningResult();
    }

    String syncRunId = "";
    try {
      syncRunId = syncRunRepository.start(syncType);
      SyncResult startedResult = syncRunRepository.findById(syncRunId)
        .orElseGet(() -> runningResult("同步任务已开始，正在后台拉取 OCI 数据。"));
      String createdSyncRunId = syncRunId;
      syncTaskExecutor.execute(() -> runSync(createdSyncRunId, source, date));
      return startedResult;
    } catch (RuntimeException exception) {
      syncRunning.set(false);
      if (syncRunId.isBlank()) {
        String now = Instant.now().toString();
        return new SyncResult("FAILED", "后台同步任务启动失败：" + cleanErrorMessage(exception), now, now, 0, 0, 0, 0);
      }
      return syncRunRepository.finish(syncRunId, "FAILED", "后台同步任务启动失败：" + cleanErrorMessage(exception), 0, 0, 0, 0);
    }
  }

  public SyncStatus getStatus() {
    OciSettings settings = ociSettingsProvider.getSettings();
    SyncResult latest = syncRunRepository.latest().orElse(null);
    String lastSuccessful = syncRunRepository.latestSuccessful().map(SyncResult::finishedAt).orElse("");
    boolean hasData = !lastSuccessful.isBlank() || !cloudInstanceRepository.findAll().isEmpty();
    if (latest == null) {
      return new SyncStatus(ociSettingsProvider.isConfigured(settings), hasData, "NEVER_SYNCED", "尚未执行同步。", "", "", 0, 0, 0, 0);
    }
    return new SyncStatus(
      ociSettingsProvider.isConfigured(settings),
      hasData,
      latest.status(),
      latest.message(),
      latest.startedAt(),
      latest.finishedAt(),
      latest.instanceCount(),
      latest.metricCount(),
      latest.trafficCount(),
      latest.costCount(),
      lastSuccessful
    );
  }

  private void runSync(String syncRunId, String source, LocalDate date) {
    try {
      updateProgress(syncRunId, "正在检查后端 OCI 配置。", 0, 0, 0, 0);
      OciSettings settings = ociSettingsProvider.getSettings();
      if (!ociSettingsProvider.isConfigured(settings)) {
        syncRunRepository.finish(syncRunId, "NOT_CONFIGURED", "后端 OCI 环境变量未配置完整，未同步任何业务数据。", 0, 0, 0, 0);
        return;
      }

      updateProgress(syncRunId, "正在创建 OCI SDK 认证 Provider。", 0, 0, 0, 0);
      BasicAuthenticationDetailsProvider provider = ociClientFactory.createProvider(settings);
      SyncCounts counts = date == null ? syncAll(syncRunId, settings, provider) : repair(settings, provider, source, date);
      target.verified();
      syncRunRepository.finish(
        syncRunId,
        counts.partial() ? "PARTIAL" : "SUCCESS",
        counts.partial() ? "部分来源已更新，缺口及旧快照仍可查看。" : "OCI 数据同步完成。",
        counts.instanceCount(),
        counts.metricCount(),
        counts.trafficCount(),
        counts.costCount()
      );
      LOGGER.info("OCI sync run {} finished successfully", syncRunId);
    } catch (Throwable exception) {
      LOGGER.warn("OCI sync run {} stopped: {}", syncRunId, exception.getClass().getSimpleName());
      String status = exception instanceof OciBudgetGate.BudgetExceeded ? "PAUSED" : "FAILED";
      syncRunRepository.finish(syncRunId, status, cleanErrorMessage(exception), 0, 0, 0, 0);
      if (isFatalJvmError(exception)) {
        throw exception;
      }
    } finally {
      syncRunning.set(false);
    }
  }

  private SyncCounts syncAll(String runId, OciSettings settings, BasicAuthenticationDetailsProvider provider) {
    String compartment = settings.compartmentOcid();
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    List<Instance> instances;
    Map<String, OciVnicInfo> vnics = Map.of();
    boolean partial = false;
    int metrics = 0;
    int traffic = 0;
    int costs = 0;
    try (ComputeClient compute = ociClientFactory.computeClient(provider, settings.region());
         VirtualNetworkClient network = ociClientFactory.virtualNetworkClient(provider, settings.region());
         MonitoringClient monitoring = ociClientFactory.monitoringClient(provider, settings.region())) {
      instances = listInstances(compute, compartment).stream()
        .filter(row -> row.getLifecycleState() != Instance.LifecycleState.Terminated).toList();
      try {
        vnics = listPrimaryVnics(compute, network, compartment, instances);
      } catch (OciBudgetGate.BudgetExceeded exhausted) {
        throw exhausted;
      } catch (RuntimeException failure) {
        partial = true;
      }
      if (!partial) {
        Map<String, OciVnicInfo> inventoryVnics = vnics;
        List<CloudInstance> snapshots = instances.stream().map(instance -> toCloudInstance(instance, settings.region(), inventoryVnics.get(instance.getId()))).toList();
        transactions.executeWithoutResult(status -> {
          ledger.recordInventoryChanges(cloudInstanceRepository.findAll(), snapshots);
          snapshots.forEach(cloudInstanceRepository::save);
          cloudInstanceRepository.deleteByIds(snapshots.stream().map(CloudInstance::id).toList());
        });
      }
      updateProgress(runId, "OCI 实例清单已检查，正在采集指标与流量。", instances.size(), 0, 0, 0);
      try {
        List<MetricPoint> points = collectMetrics(monitoring, compartment, instances);
        transactions.executeWithoutResult(status -> points.forEach(metricRepository::insert));
        metrics = points.size();
      } catch (OciBudgetGate.BudgetExceeded exhausted) {
        throw exhausted;
      } catch (RuntimeException failure) {
        partial = true;
      }
      LocalDate first = trafficStart(today, ledger.coverage("traffic", YearMonth.from(today)));
      try {
        traffic = publishTraffic(monitoring, vnics, instances.size(), first, today);
        partial |= !instances.isEmpty() && !ledger.covered("traffic", today.minusDays(1));
      } catch (OciBudgetGate.BudgetExceeded exhausted) {
        throw exhausted;
      } catch (RuntimeException failure) {
        partial = true;
        markGap("traffic", first, today, "读取流量失败，保留上一次可用快照");
      }
    }

    if (ociSettingsProvider.billingEnabled()) {
      LocalDate first = ledger.coverage("cost", YearMonth.from(today)).isEmpty()
        ? YearMonth.from(today).atDay(1)
        : today.minusDays(7);
      try (UsageapiClient usage = ociClientFactory.usageapiClient(provider, settings.region())) {
        costs = syncCostUsage(usage, settings, resolveTenantId(settings, provider), first, today);
      } catch (OciBudgetGate.BudgetExceeded exhausted) {
        throw exhausted;
      } catch (RuntimeException failure) {
        partial = true;
        markGap("cost", first, today, "费用读取失败，可在连接诊断查看权限");
      }
    }
    String tenancyId = resolveTenantId(settings, provider);
    volumes.refresh(settings, provider, tenancyId);
    if (ociSettingsProvider.billingEnabled()) {
      try (UsageapiClient usage = ociClientFactory.usageapiClient(provider, settings.region())) {
        quotas.refresh(usage, tenancyId, today);
      } catch (OciBudgetGate.BudgetExceeded exhausted) {
        throw exhausted;
      } catch (RuntimeException failure) {
        quotas.recordFailure(YearMonth.from(today), OciVolumeService.sourceFailure(failure), Instant.now().toString());
      }
    }
    return new SyncCounts(instances.size(), metrics, traffic, costs, partial);
  }

  static LocalDate trafficStart(LocalDate today, List<LensLedger.CoverageDay> coverage) {
    var attempted = coverage.stream().map(LensLedger.CoverageDay::statDate).collect(java.util.stream.Collectors.toSet());
    LocalDate recent = today.minusDays(2);
    return YearMonth.from(today).atDay(1).datesUntil(today)
      .filter(day -> day.isBefore(recent) && !attempted.contains(day.toString()))
      .findFirst().orElse(recent);
  }

  private SyncCounts repair(OciSettings settings, BasicAuthenticationDetailsProvider provider, String source, LocalDate date) {
    LocalDate next = date.plusDays(1);
    if ("cost".equals(source)) {
      try (UsageapiClient usage = ociClientFactory.usageapiClient(provider, settings.region())) {
        return new SyncCounts(0, 0, 0, syncCostUsage(usage, settings, resolveTenantId(settings, provider), date, next), false);
      }
    }
    try (ComputeClient compute = ociClientFactory.computeClient(provider, settings.region());
         VirtualNetworkClient network = ociClientFactory.virtualNetworkClient(provider, settings.region());
         MonitoringClient monitoring = ociClientFactory.monitoringClient(provider, settings.region())) {
      var instances = listInstances(compute, settings.compartmentOcid()).stream()
        .filter(instance -> instance.getLifecycleState() != Instance.LifecycleState.Terminated)
        .filter(instance -> instance.getTimeCreated() == null || instance.getTimeCreated().before(toUtcStartOfDay(next))).toList();
      var vnics = listPrimaryVnics(compute, network, settings.compartmentOcid(), instances);
      int count = publishTraffic(monitoring, vnics, instances.size(), date, next);
      return new SyncCounts(0, 0, count, 0, !ledger.covered("traffic", date));
    }
  }

  private void markGap(String source, LocalDate start, LocalDate end, String reason) {
    transactions.executeWithoutResult(status -> {
      for (LocalDate day = start; day.isBefore(end); day = day.plusDays(1)) ledger.coverage(source, day, "GAP", reason);
    });
  }

  private SyncResult latestRunningResult() {
    Optional<SyncResult> latest = syncRunRepository.latest();
    return latest
      .filter(result -> "RUNNING".equals(result.status()))
      .orElseGet(() -> runningResult("已有同步任务正在后台执行，请稍后查看同步状态。"));
  }

  private SyncResult runningResult(String message) {
    return new SyncResult("RUNNING", message, Instant.now().toString(), null, 0, 0, 0, 0);
  }

  private void updateProgress(String syncRunId, String message, int instanceCount, int metricCount, int trafficCount, int costCount) {
    LOGGER.info("OCI sync run {} progress: {}", syncRunId, message);
    syncRunRepository.updateProgress(syncRunId, message, instanceCount, metricCount, trafficCount, costCount);
  }

  private List<Instance> listInstances(ComputeClient computeClient, String compartmentOcid) {
    List<Instance> instances = new ArrayList<>();
    String nextPage = null;
    do {
      budget.reserve(0);
      ListInstancesResponse response = computeClient.listInstances(ListInstancesRequest.builder()
        .compartmentId(compartmentOcid)
        .limit(100)
        .page(nextPage)
        .build());
      instances.addAll(response.getItems());
      nextPage = response.getOpcNextPage();
    } while (nextPage != null && !nextPage.isBlank());
    return instances;
  }

  private Map<String, OciVnicInfo> listPrimaryVnics(
    ComputeClient computeClient,
    VirtualNetworkClient virtualNetworkClient,
    String compartmentOcid,
    List<Instance> instances
  ) {
    Map<String, OciVnicInfo> result = new HashMap<>();
    for (Instance instance : instances) {
      String nextPage = null;
      do {
        budget.reserve(0);
        ListVnicAttachmentsResponse response = computeClient.listVnicAttachments(ListVnicAttachmentsRequest.builder()
          .compartmentId(compartmentOcid)
          .instanceId(instance.getId())
          .limit(50)
          .page(nextPage)
          .build());
        for (VnicAttachment attachment : response.getItems()) {
          if (attachment.getVnicId() == null || attachment.getVnicId().isBlank()) {
            continue;
          }
          budget.reserve(0);
          Vnic vnic = virtualNetworkClient.getVnic(GetVnicRequest.builder().vnicId(attachment.getVnicId()).build()).getVnic();
          if (Boolean.TRUE.equals(vnic.getIsPrimary())) {
            result.put(instance.getId(), new OciVnicInfo(instance.getId(), vnic.getId(), vnic.getPublicIp(), vnic.getPrivateIp(), blankToDefault(vnic.getCompartmentId(), ociSettingsProvider.networkCompartment())));
            break;
          }
        }
        nextPage = response.getOpcNextPage();
      } while (!result.containsKey(instance.getId()) && nextPage != null && !nextPage.isBlank());
    }
    return result;
  }

  private CloudInstance toCloudInstance(Instance instance, String region, OciVnicInfo vnicInfo) {
    InstanceShapeConfig shapeConfig = instance.getShapeConfig();
    double ocpus = shapeConfig == null || shapeConfig.getOcpus() == null ? 0 : shapeConfig.getOcpus();
    double memoryGb = shapeConfig == null || shapeConfig.getMemoryInGBs() == null ? 0 : shapeConfig.getMemoryInGBs();
    return new CloudInstance(
      instance.getId(),
      blankToDefault(instance.getDisplayName(), instance.getId()),
      blankToDefault(instance.getRegion(), region),
      instance.getCompartmentId(),
      blankToDefault(instance.getShape(), "-"),
      instance.getLifecycleState() == null ? "UNKNOWN" : instance.getLifecycleState().getValue(),
      ocpus,
      memoryGb,
      0,
      vnicInfo == null ? "" : blankToDefault(vnicInfo.publicIp(), ""),
      vnicInfo == null ? "" : blankToDefault(vnicInfo.privateIp(), ""),
      toInstantText(instance.getTimeCreated()),
      Instant.now().toString()
    );
  }

  private List<MetricPoint> collectMetrics(MonitoringClient client, String compartment, List<Instance> instances) {
    Instant end = Instant.now().truncatedTo(ChronoUnit.HOURS);
    Instant start = end.minus(48, ChronoUnit.HOURS);
    List<MetricPoint> points = new ArrayList<>();
    for (Instance instance : instances) {
      String id = instance.getId();
      addMetrics(points, client, compartment, "CpuUtilization", "cpu_utilization", id, start, end);
      addMetrics(points, client, compartment, "MemoryUtilization", "memory_utilization", id, start, end);
    }
    return points;
  }

  private void addMetrics(List<MetricPoint> points, MonitoringClient client, String compartment, String metric, String name,
                          String id, Instant start, Instant end) {
    String query = "%s[1h]{resourceId = \"%s\"}.mean()".formatted(metric, id);
    for (MetricData data : summarizeMetricData(client, compartment, "oci_computeagent", query, start, end)) {
      if (data.getAggregatedDatapoints() == null) continue;
      for (AggregatedDatapoint point : data.getAggregatedDatapoints()) {
        if (point.getValue() != null && point.getTimestamp() != null) {
          points.add(new MetricPoint(id, name, point.getValue(), "%", toInstantText(point.getTimestamp())));
        }
      }
    }
  }

  private int publishTraffic(MonitoringClient client, Map<String, OciVnicInfo> vnics, int instanceCount,
                             LocalDate first, LocalDate end) {
    Map<LocalDate, List<TrafficDaily>> days = new HashMap<>();
    for (OciVnicInfo vnic : vnics.values()) {
      var inbound = hourlyTraffic(client, vnic, "VnicFromNetworkBytes", first, end);
      var outbound = hourlyTraffic(client, vnic, "VnicToNetworkBytes", first, end);
      for (LocalDate day = first; day.isBefore(end); day = day.plusDays(1)) {
        Instant start = day.atStartOfDay().toInstant(ZoneOffset.UTC);
        List<Double> ingress = new ArrayList<>(24);
        List<Double> egress = new ArrayList<>(24);
        for (int hour = 1; hour <= 24; hour++) {
          Instant bucketEnd = start.plus(hour, ChronoUnit.HOURS);
          if (inbound.containsKey(bucketEnd)) ingress.add(inbound.get(bucketEnd));
          if (outbound.containsKey(bucketEnd)) egress.add(outbound.get(bucketEnd));
        }
        if (ingress.size() == 24 && egress.size() == 24) {
          double in = ingress.stream().mapToDouble(Double::doubleValue).sum() / 1073741824d;
          double out = egress.stream().mapToDouble(Double::doubleValue).sum() / 1073741824d;
          days.computeIfAbsent(day, ignored -> new ArrayList<>()).add(new TrafficDaily(vnic.instanceId(), day.toString(), in, out));
        }
      }
    }
    transactions.executeWithoutResult(status -> {
      for (LocalDate day = first; day.isBefore(end); day = day.plusDays(1)) {
        var rows = days.getOrDefault(day, List.of());
        rows.forEach(trafficRepository::save);
        boolean complete = instanceCount > 0 && rows.size() == instanceCount;
        ledger.coverage("traffic", day, complete ? "COVERED" : "GAP", complete ? "小时采样齐全" : "部分主网卡或小时采样未返回");
      }
    });
    return days.values().stream().mapToInt(List::size).sum();
  }

  private Map<Instant, Double> hourlyTraffic(MonitoringClient client, OciVnicInfo vnic, String metric, LocalDate first, LocalDate end) {
    Map<Instant, Double> volumes = trafficBuckets(client, vnic, metric, "sum", first, end);
    Map<Instant, Double> counts = trafficBuckets(client, vnic, metric, "count", first, end);
    volumes.keySet().removeIf(hour -> counts.getOrDefault(hour, 0d) < 60);
    return volumes;
  }

  private Map<Instant, Double> trafficBuckets(MonitoringClient client, OciVnicInfo vnic, String metric, String statistic, LocalDate first, LocalDate end) {
    String query = "%s[1h]{resourceId = \"%s\"}.%s()".formatted(metric, vnic.vnicId(), statistic);
    Map<Instant, Double> points = new HashMap<>();
    for (MetricData data : summarizeMetricData(client, vnic.compartmentId(), "oci_vcn", query, toUtcStartOfDay(first).toInstant(), toUtcStartOfDay(end).toInstant())) {
      if (data.getAggregatedDatapoints() == null) continue;
      for (AggregatedDatapoint point : data.getAggregatedDatapoints()) {
        if (point.getTimestamp() != null && point.getValue() != null) points.put(point.getTimestamp().toInstant(), point.getValue());
      }
    }
    return points;
  }

  private int syncCostUsage(
    UsageapiClient usageapiClient,
    OciSettings settings,
    String tenantId,
    LocalDate monthStart,
    LocalDate usageEndExclusive
  ) {
    if (!usageEndExclusive.isAfter(monthStart)) {
      LOGGER.info("Skip Usage API cost sync because no completed UTC day is available in current month.");
      return 0;
    }
    String resolvedTenantId = blankToDefault(settings.tenancyOcid(), tenantId);
    if (resolvedTenantId.isBlank()) {
      LOGGER.info("Skip Usage API cost sync because tenant OCID is not configured.");
      return 0;
    }
    RequestSummarizedUsagesDetails details = RequestSummarizedUsagesDetails.builder()
      .tenantId(resolvedTenantId)
      .timeUsageStarted(toUtcStartOfDay(monthStart))
      .timeUsageEnded(toUtcStartOfDay(usageEndExclusive))
      .granularity(RequestSummarizedUsagesDetails.Granularity.Daily)
      .queryType(RequestSummarizedUsagesDetails.QueryType.Cost)
      .isAggregateByTime(false)
      .groupBy(List.of("service", "resourceId", "currency"))
      .compartmentDepth(BigDecimal.valueOf(6))
      .build();

    Map<CostKey, BigDecimal> sums = new HashMap<>();
    String nextPage = null;
    do {
      budget.reserve(0);
      RequestSummarizedUsagesResponse response = usageapiClient.requestSummarizedUsages(RequestSummarizedUsagesRequest.builder()
        .requestSummarizedUsagesDetails(details).limit(1000).page(nextPage).build());
      UsageAggregation aggregation = response.getUsageAggregation();
      if (aggregation != null && aggregation.getItems() != null) {
        for (UsageSummary row : aggregation.getItems()) {
          if (row.getComputedAmount() == null || row.getTimeUsageStarted() == null || row.getCurrency() == null || row.getCurrency().isBlank()) continue;
          var key = new CostKey(blankToDefault(row.getService(), "未归属"), blankToDefault(row.getResourceId(), "-"),
            toDateText(row.getTimeUsageStarted()), row.getCurrency());
          sums.merge(key, row.getComputedAmount(), BigDecimal::add);
        }
      }
      nextPage = response.getOpcNextPage();
    } while (nextPage != null && !nextPage.isBlank());
    List<LensLedger.CostEntry> rows = sums.entrySet().stream().map(entry -> {
      CostKey key = entry.getKey();
      return new LensLedger.CostEntry(key.service(), key.resource(), key.date(), key.currency(), entry.getValue().toPlainString());
    }).toList();
    transactions.executeWithoutResult(status -> ledger.publishCosts(monthStart, usageEndExclusive, rows));
    return rows.size();
  }

  private record CostKey(String service, String resource, String date, String currency) {}

  private String resolveTenantId(OciSettings settings, BasicAuthenticationDetailsProvider provider) {
    if (!settings.tenancyOcid().isBlank()) {
      return settings.tenancyOcid();
    }
    if (provider instanceof AuthenticationDetailsProvider authenticationDetailsProvider) {
      return blankToDefault(authenticationDetailsProvider.getTenantId(), "");
    }
    return "";
  }

  private Date toUtcStartOfDay(LocalDate date) {
    return Date.from(date.atStartOfDay().toInstant(ZoneOffset.UTC));
  }

  private List<MetricData> summarizeMetricData(
    MonitoringClient monitoringClient,
    String compartmentOcid,
    String namespace,
    String query,
    Instant startTime,
    Instant endTime
  ) {
    budget.reserve(OciBudgetGate.estimatePoints(startTime, endTime));
    return monitoringClient.summarizeMetricsData(SummarizeMetricsDataRequest.builder()
      .compartmentId(compartmentOcid)
      .summarizeMetricsDataDetails(SummarizeMetricsDataDetails.builder()
        .namespace(namespace)
        .query(query)
        .startTime(Date.from(startTime))
        .endTime(Date.from(endTime))
        .resolution("1h")
        .build())
      .build())
      .getItems();
  }

  private String cleanErrorMessage(Throwable exception) {
    if (exception instanceof OciBudgetGate.BudgetExceeded) return exception.getMessage();
    if (exception instanceof BmcException failure) return "OCI HTTP " + failure.getStatusCode() + "，请在设置中检查连接与授权。";
    if (exception instanceof java.util.ServiceConfigurationError) return "OCI SDK 连接组件初始化失败。";
    return "OCI 同步失败，请检查连接与 IAM 权限。";
  }

  private boolean isFatalJvmError(Throwable exception) {
    return exception instanceof VirtualMachineError || exception instanceof ThreadDeath;
  }

  private String toInstantText(Date date) {
    return date == null ? "" : date.toInstant().toString();
  }

  private String toDateText(Date date) {
    return date == null ? LocalDate.now().toString() : date.toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString();
  }



  private String blankToDefault(String value, String defaultValue) {
    return value == null || value.isBlank() ? defaultValue : value;
  }

  private record SyncCounts(int instanceCount, int metricCount, int trafficCount, int costCount, boolean partial) {}
}
