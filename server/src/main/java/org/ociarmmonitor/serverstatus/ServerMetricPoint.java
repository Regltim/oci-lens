package org.ociarmmonitor.serverstatus;

public record ServerMetricPoint(
  String sampledAt,
  Double cpuUsagePercent,
  Double memoryUsagePercent,
  Double diskUsagePercent,
  Double networkRxBytesPerSecond,
  Double networkTxBytesPerSecond
) {
}
