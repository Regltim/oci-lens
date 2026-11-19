package org.ociarmmonitor.instance;

public record InstanceOverview(
  CloudInstance instance,
  Double cpuUtilization,
  Double memoryUtilization,
  Double egressGbToday,
  double costAmountThisMonth
) {
}
