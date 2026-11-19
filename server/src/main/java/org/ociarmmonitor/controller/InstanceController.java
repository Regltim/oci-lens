package org.ociarmmonitor.controller;

import org.ociarmmonitor.common.ApiResponse;
import org.ociarmmonitor.instance.CloudInstanceService;
import org.ociarmmonitor.instance.CloudInstanceRepository.HistoricalInstance;
import org.ociarmmonitor.instance.InstanceOverview;
import org.ociarmmonitor.instance.MetricPoint;
import org.ociarmmonitor.instance.MetricRepository.MetricDates;
import org.ociarmmonitor.oci.OciVolumeService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/instances")
public class InstanceController {

  private final CloudInstanceService cloudInstanceService;

  private final OciVolumeService volumes;

  public InstanceController(CloudInstanceService cloudInstanceService, OciVolumeService volumes) {
    this.cloudInstanceService = cloudInstanceService;
    this.volumes = volumes;
  }

  @GetMapping
  public ApiResponse<List<InstanceOverview>> listInstances() {
    return ApiResponse.ok(cloudInstanceService.listInstances());
  }

  @GetMapping("/history")
  public ApiResponse<List<HistoricalInstance>> listHistory() {
    return ApiResponse.ok(cloudInstanceService.listHistory());
  }

  @GetMapping("/{instanceId}/volumes")
  public ApiResponse<OciVolumeService.Details> listVolumes(@PathVariable String instanceId) {
    return ApiResponse.ok(volumes.forInstance(instanceId));
  }

  @GetMapping("/{instanceId}/metrics/dates")
  public ApiResponse<MetricDates> metricDates(@PathVariable String instanceId) {
    return ApiResponse.ok(cloudInstanceService.metricDates(instanceId));
  }

  @GetMapping("/{instanceId}/metrics")
  public ApiResponse<List<MetricPoint>> listMetrics(
    @PathVariable String instanceId,
    @RequestParam String metricName,
    @RequestParam(defaultValue = "") String from,
    @RequestParam(defaultValue = "") String to
  ) {
    return ApiResponse.ok(cloudInstanceService.listMetricSeries(instanceId, metricName, from, to));
  }
}
