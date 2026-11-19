package org.ociarmmonitor.serverstatus;

import org.ociarmmonitor.oci.SyncRunRepository;
import org.ociarmmonitor.common.HistoryWindow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ServerStatusService {

  private final ServerStatusCollector serverStatusCollector;
  private final ServerStatusRepository serverStatusRepository;
  private final ServerAlertService serverAlertService;
  private final SyncRunRepository syncRunRepository;

  public ServerStatusService(
    ServerStatusCollector serverStatusCollector,
    ServerStatusRepository serverStatusRepository,
    ServerAlertService serverAlertService,
    SyncRunRepository syncRunRepository
  ) {
    this.serverStatusCollector = serverStatusCollector;
    this.serverStatusRepository = serverStatusRepository;
    this.serverAlertService = serverAlertService;
    this.syncRunRepository = syncRunRepository;
  }

  public ServerStatusSummary getSummary() {
    ServerStatusSnapshot snapshot = serverStatusRepository.latest()
      .filter(row -> Duration.between(Instant.parse(row.sampledAt()), Instant.now()).compareTo(Duration.ofMinutes(5)) < 0)
      .orElseGet(this::sampleAndStore);
    return new ServerStatusSummary(
      snapshot,
      listHistory(24, 720),
      serverAlertService.evaluate(snapshot, syncRunRepository.latest()),
      serverStatusCollector.collectSystemInfo(),
      serverStatusCollector.collectDisks(),
      serverStatusCollector.networkAvailable()
    );
  }

  public ServerStatusSnapshot sampleAndStore() {
    ServerStatusSnapshot snapshot = serverStatusCollector.sample();
    serverStatusRepository.save(snapshot);
    serverStatusRepository.deleteExpired();
    return snapshot;
  }

  public List<ServerStatusRepository.HostHistorySource> listHistorySources() {
    return serverStatusRepository.listHistorySources();
  }

  public List<ServerMetricPoint> listHistory(String from, String to, String host) {
    return serverStatusRepository.listHistory(HistoryWindow.parse(from, to), host);
  }

  public List<ServerMetricPoint> listHistory(int hours, int limit) {
    return serverStatusRepository.listHistory(hours, limit);
  }
}
