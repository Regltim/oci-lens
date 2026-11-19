package org.ociarmmonitor.oci;

public record SyncStatus(
  boolean configured,
  boolean hasData,
  String lastStatus,
  String lastMessage,
  String lastStartedAt,
  String lastFinishedAt,
  int instanceCount,
  int metricCount,
  int trafficCount,
  int costCount,
  String lastSuccessfulAt
) {
  public SyncStatus(boolean configured, boolean hasData, String lastStatus, String lastMessage, String lastStartedAt,
                    String lastFinishedAt, int instanceCount, int metricCount, int trafficCount, int costCount) {
    this(configured, hasData, lastStatus, lastMessage, lastStartedAt, lastFinishedAt, instanceCount, metricCount, trafficCount, costCount, "");
  }
}
