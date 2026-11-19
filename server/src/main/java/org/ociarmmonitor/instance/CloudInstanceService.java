package org.ociarmmonitor.instance;

import org.ociarmmonitor.cost.CostRepository;
import org.ociarmmonitor.traffic.TrafficRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalDouble;
import org.springframework.stereotype.Service;

@Service
public class CloudInstanceService {

  private final CloudInstanceRepository cloudInstanceRepository;
  private final MetricRepository metricRepository;
  private final TrafficRepository trafficRepository;
  private final CostRepository costRepository;

  public CloudInstanceService(
    CloudInstanceRepository cloudInstanceRepository,
    MetricRepository metricRepository,
    TrafficRepository trafficRepository,
    CostRepository costRepository
  ) {
    this.cloudInstanceRepository = cloudInstanceRepository;
    this.metricRepository = metricRepository;
    this.trafficRepository = trafficRepository;
    this.costRepository = costRepository;
  }

  public List<InstanceOverview> listInstances() {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    return cloudInstanceRepository.findAll().stream()
      .map(instance -> new InstanceOverview(
        instance,
        value(metricRepository.latestOptional(instance.id(), "cpu_utilization")),
        value(metricRepository.latestOptional(instance.id(), "memory_utilization")),
        trafficRepository.egressSnapshot(instance.id(), today),
        costRepository.costByResourceForCurrentMonth(instance.id())
      ))
      .toList();
  }

  private Double value(OptionalDouble result) { return result.isPresent() ? result.getAsDouble() : null; }

  public List<MetricPoint> listMetricSeries(String instanceId, String metricName) {
    return metricRepository.findSeries(instanceId, metricName, 48);
  }
}
