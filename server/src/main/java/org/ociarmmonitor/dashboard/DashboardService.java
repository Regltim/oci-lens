package org.ociarmmonitor.dashboard;

import org.ociarmmonitor.oci.OciQuotaService;
import org.ociarmmonitor.cost.CostRepository;
import org.ociarmmonitor.cost.CostService;
import org.ociarmmonitor.cost.CostSummary;
import org.ociarmmonitor.cost.ManualCostRepository;
import org.ociarmmonitor.instance.CloudInstanceRepository;
import org.ociarmmonitor.instance.MetricRepository;
import org.ociarmmonitor.traffic.TrafficRepository;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {

  private final CloudInstanceRepository cloudInstanceRepository;
  private final MetricRepository metricRepository;
  private final TrafficRepository trafficRepository;
  private final OciQuotaService quotas;
  private final CostRepository costRepository;
  private final ManualCostRepository manualCostRepository;
  private final CostService costService;

  public DashboardService(
    CloudInstanceRepository cloudInstanceRepository,
    MetricRepository metricRepository,
    TrafficRepository trafficRepository,
    OciQuotaService quotas,
    CostRepository costRepository,
    ManualCostRepository manualCostRepository,
    CostService costService
  ) {
    this.cloudInstanceRepository = cloudInstanceRepository;
    this.metricRepository = metricRepository;
    this.trafficRepository = trafficRepository;
    this.quotas = quotas;
    this.costRepository = costRepository;
    this.manualCostRepository = manualCostRepository;
    this.costService = costService;
  }

  public DashboardSummary getSummary() {
    CostSummary costSummary = costService.getSummary();
    double egressGb = trafficRepository.sumEgressForCurrentMonth();
    List<QuotaUsage> quotaUsages = quotas.summary(YearMonth.now(ZoneOffset.UTC)).items().stream()
      .map(item -> new QuotaUsage(item.name(), item.used(), item.limit(), item.unit(),
        item.used() != null && "AVAILABLE".equals(item.status()) && item.limit() > 0 ? item.used() / item.limit() * 100 : null))
      .toList();

    return new DashboardSummary(
      costRepository.costForCurrentMonth(),
      manualCostRepository.costForCurrentMonth(),
      costSummary.estimatedMonthEndCost(),
      metricRepository.average("cpu_utilization"),
      metricRepository.average("memory_utilization"),
      egressGb,
      cloudInstanceRepository.findAll().size(),
      quotaUsages,
      buildRiskAlerts(quotaUsages)
    );
  }

  private List<RiskAlert> buildRiskAlerts(List<QuotaUsage> quotaUsages) {
    List<RiskAlert> alerts = new ArrayList<>();
    for (QuotaUsage quotaUsage : quotaUsages) {
      if (quotaUsage.percent() == null) continue;
      if (quotaUsage.percent() >= 95) {
        alerts.add(new RiskAlert("danger", quotaUsage.name() + "接近上限", "当前已使用 %.1f%%，建议立即检查资源配置。".formatted(quotaUsage.percent())));
      } else if (quotaUsage.percent() >= 80) {
        alerts.add(new RiskAlert("warning", quotaUsage.name() + "使用偏高", "当前已使用 %.1f%%，建议关注后续增长趋势。".formatted(quotaUsage.percent())));
      }
    }
    if (alerts.isEmpty() && quotaUsages.stream().allMatch(quota -> quota.percent() != null)) {
      alerts.add(new RiskAlert("success", "暂无免费额度风险", "当前已同步数据未触发超额预警，费用以 OCI Usage API 返回为准。"));
    }
    return alerts;
  }
}
