package org.ociarmmonitor.dashboard;

public record QuotaUsage(
  String name,
  Double used,
  double quota,
  String unit,
  Double percent
) {
}
