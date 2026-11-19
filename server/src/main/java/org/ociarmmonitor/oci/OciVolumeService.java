package org.ociarmmonitor.oci;

import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.core.requests.ListBootVolumeAttachmentsRequest;
import com.oracle.bmc.core.requests.ListBootVolumesRequest;
import com.oracle.bmc.core.requests.ListVolumeAttachmentsRequest;
import com.oracle.bmc.core.requests.ListVolumesRequest;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.requests.ListAvailabilityDomainsRequest;
import com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.http.internal.BmcEnum;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.ociarmmonitor.config.OciSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OciVolumeService {
  private static final Logger LOGGER = LoggerFactory.getLogger(OciVolumeService.class);
  private final OciClientFactory clients;
  private final OciBudgetGate budget;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public OciVolumeService(OciClientFactory clients, OciBudgetGate budget, JdbcTemplate jdbc, TransactionTemplate transactions) {
    this.clients = clients;
    this.budget = budget;
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  public record Failure(String operation, Integer httpStatus) {}
  public record Status(String status, String attemptedAt, String succeededAt, String region, String compartmentId,
                       String homeRegion, boolean incomplete, Failure failure) {}
  public record Volume(String id, String name, String kind, Long sizeGb, String state, String attachmentState) {}
  public record Details(Status snapshot, List<Volume> volumes) {}
  private record Attachment(String volumeId, String instanceId, String state) {}
  private record Page<T>(List<T> items, String next) {}

  public Status status() {
    return jdbc.query("""
      SELECT s.status, s.attempted_at, s.succeeded_at, s.region, s.compartment_id, s.home_region, s.incomplete,
        f.operation, f.http_status
      FROM oci_volume_snapshot s LEFT JOIN oci_volume_read_failure f ON f.id = s.id WHERE s.id = 1
      """, (row, index) -> new Status(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
      row.getString(5), row.getString(6), row.getBoolean(7),
      row.getString(8) == null ? null : new Failure(row.getString(8), (Integer) org.springframework.jdbc.support.JdbcUtils.getResultSetValue(row, 9, Integer.class))))
      .stream().findFirst().orElse(new Status("PENDING", null, null, null, null, null, false, null));
  }

  @Transactional(readOnly = true)
  public Details forInstance(String instanceId) {
    Status snapshot = status();
    var volumes = jdbc.query("""
      SELECT v.id, v.name, v.kind, v.size_gb, v.state, a.state
      FROM oci_volume_attachment a JOIN oci_volume v ON v.id = a.volume_id
      WHERE a.instance_id = ? ORDER BY v.kind DESC, v.name
      """, (row, index) -> new Volume(row.getString(1), row.getString(2), row.getString(3),
      row.getObject(4) instanceof Number amount ? amount.longValue() : null, row.getString(5), row.getString(6)), instanceId);
    return new Details(snapshot, volumes);
  }

  public Long totalGb() {
    return jdbc.queryForObject("""
      SELECT CASE WHEN COUNT(*) = COUNT(size_gb) THEN COALESCE(SUM(size_gb), 0) END FROM oci_volume
      """, Long.class);
  }

  public void refresh(OciSettings settings, BasicAuthenticationDetailsProvider provider, String tenancyId) {
    String region = settings.region();
    String compartment = settings.compartmentOcid();
    String attempted = Instant.now().toString();
    String operation = "初始化 OCI 云盘客户端";
    try (var identity = clients.identityClient(provider, region);
         var storage = clients.blockstorageClient(provider, region);
         var compute = clients.computeClient(provider, region)) {
      operation = "ListAvailabilityDomains";
      budget.reserve(0);
      var domains = identity.listAvailabilityDomains(ListAvailabilityDomainsRequest.builder()
        .compartmentId(compartment).build()).getItems();
      if (domains == null || domains.isEmpty()) throw new IllegalStateException("No availability domain in OCI response");

      Map<String, Volume> volumes = new LinkedHashMap<>();
      List<Attachment> attachments = new ArrayList<>();
      for (var domain : domains) {
        String ad = domain.getName();
        operation = "ListBootVolumes";
        pages(page -> {
          var response = storage.listBootVolumes(ListBootVolumesRequest.builder()
            .availabilityDomain(ad).compartmentId(compartment).page(page).build());
          return new Page<>(response.getItems(), response.getOpcNextPage());
        }).forEach(volume -> addVolume(volumes, volume.getId(), volume.getDisplayName(), "BOOT",
          volume.getSizeInGBs(), state(volume.getLifecycleState())));
        operation = "ListBootVolumeAttachments";
        pages(page -> {
          var response = compute.listBootVolumeAttachments(ListBootVolumeAttachmentsRequest.builder()
            .availabilityDomain(ad).compartmentId(compartment).page(page).build());
          return new Page<>(response.getItems(), response.getOpcNextPage());
        }).forEach(attachment -> attachments.add(new Attachment(attachment.getBootVolumeId(),
          attachment.getInstanceId(), state(attachment.getLifecycleState()))));
      }
      operation = "ListVolumes";
      pages(page -> {
        var response = storage.listVolumes(ListVolumesRequest.builder().compartmentId(compartment).page(page).build());
        return new Page<>(response.getItems(), response.getOpcNextPage());
      }).forEach(volume -> addVolume(volumes, volume.getId(), volume.getDisplayName(), "BLOCK",
        volume.getSizeInGBs(), state(volume.getLifecycleState())));
      operation = "ListVolumeAttachments";
      pages(page -> {
        var response = compute.listVolumeAttachments(ListVolumeAttachmentsRequest.builder()
          .compartmentId(compartment).page(page).build());
        return new Page<>(response.getItems(), response.getOpcNextPage());
      }).forEach(attachment -> attachments.add(new Attachment(attachment.getVolumeId(),
        attachment.getInstanceId(), state(attachment.getLifecycleState()))));

      operation = "ListRegionSubscriptions";
      String homeRegion = resolveHomeRegion(identity, tenancyId);
      var active = attachments.stream().filter(attachment -> attachment.volumeId() != null
        && attachment.instanceId() != null && List.of("ATTACHED", "ATTACHING", "DETACHING").contains(attachment.state())).toList();
      boolean incomplete = active.stream().anyMatch(attachment -> !volumes.containsKey(attachment.volumeId()));
      operation = "保存云盘快照";
      transactions.executeWithoutResult(transaction -> publish(region, compartment, attempted, homeRegion, volumes, active, incomplete));
    } catch (RuntimeException failure) {
      String state = sourceFailure(failure);
      recordFailure(state, attempted, operation, failure instanceof BmcException bmc ? bmc.getStatusCode() : null);
      if (failure instanceof OciBudgetGate.BudgetExceeded) throw failure;
      LOGGER.warn("OCI volume snapshot refresh failed ({})", state);
    }
  }

  private String resolveHomeRegion(IdentityClient identity, String tenancyId) {
    String previous = status().homeRegion();
    if (tenancyId.isBlank() || previous != null) return previous;
    try {
      budget.reserve(0);
      var regions = identity.listRegionSubscriptions(ListRegionSubscriptionsRequest.builder().tenancyId(tenancyId).build()).getItems();
      return regions == null ? null : regions.stream().filter(row -> Boolean.TRUE.equals(row.getIsHomeRegion()))
        .map(row -> row.getRegionName()).findFirst().orElse(null);
    } catch (BmcException failure) {
      return null;
    }
  }

  private void publish(String region, String compartment, String attempted, String homeRegion, Map<String, Volume> volumes,
                       List<Attachment> attachments, boolean incomplete) {
    jdbc.update("DELETE FROM oci_volume_read_failure WHERE id = 1");
    jdbc.update("DELETE FROM oci_volume_attachment");
    jdbc.update("DELETE FROM oci_volume");
    volumes.values().forEach(volume -> jdbc.update("INSERT INTO oci_volume VALUES (?, ?, ?, ?, ?)",
      volume.id(), volume.name(), volume.kind(), volume.sizeGb(), volume.state()));
    attachments.stream().filter(attachment -> volumes.containsKey(attachment.volumeId())).forEach(attachment -> jdbc.update("""
      INSERT INTO oci_volume_attachment VALUES (?, ?, ?)
      ON CONFLICT(volume_id, instance_id) DO UPDATE SET state = excluded.state
      WHERE oci_volume_attachment.state != 'ATTACHED'
      """, attachment.volumeId(), attachment.instanceId(), attachment.state()));
    jdbc.update("""
      UPDATE cloud_instance SET boot_volume_gb = COALESCE((
        SELECT SUM(v.size_gb) FROM oci_volume v JOIN oci_volume_attachment a ON a.volume_id = v.id
        WHERE a.instance_id = cloud_instance.id AND a.state = 'ATTACHED' AND v.kind = 'BOOT'
      ), 0)
      """);
    jdbc.update("""
      INSERT INTO oci_volume_snapshot VALUES (1, 'AVAILABLE', ?, ?, ?, ?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET status = excluded.status, attempted_at = excluded.attempted_at,
        succeeded_at = excluded.succeeded_at, region = excluded.region, compartment_id = excluded.compartment_id,
        home_region = excluded.home_region, incomplete = excluded.incomplete
      """, attempted, Instant.now().toString(), region, compartment, homeRegion, incomplete);
  }

  private void recordFailure(String status, String attempted, String operation, Integer httpStatus) {
    transactions.executeWithoutResult(transaction -> {
      jdbc.update("""
        INSERT INTO oci_volume_snapshot(id, status, attempted_at) VALUES (1, ?, ?)
        ON CONFLICT(id) DO UPDATE SET status = excluded.status, attempted_at = excluded.attempted_at
        """, status, attempted);
      jdbc.update("""
        INSERT INTO oci_volume_read_failure(id, operation, http_status) VALUES (1, ?, ?)
        ON CONFLICT(id) DO UPDATE SET operation = excluded.operation, http_status = excluded.http_status
        """, operation, httpStatus);
    });
  }

  private <T> List<T> pages(Function<String, Page<T>> request) {
    List<T> items = new ArrayList<>();
    String next = null;
    do {
      budget.reserve(0);
      Page<T> page = request.apply(next);
      if (page.items() != null) items.addAll(page.items());
      next = page.next();
    } while (next != null && !next.isBlank());
    return items;
  }

  private void addVolume(Map<String, Volume> volumes, String id, String name, String kind, Long size, String state) {
    if (id != null && !List.of("TERMINATING", "TERMINATED").contains(state)) {
      volumes.put(id, new Volume(id, name == null || name.isBlank() ? id : name, kind,
        size != null && size > 0 ? size : null, state, null));
    }
  }

  private String state(BmcEnum state) {
    return state == null ? "UNKNOWN" : state.getValue().toUpperCase(java.util.Locale.ROOT);
  }

  static String sourceFailure(RuntimeException failure) {
    if (failure instanceof OciBudgetGate.BudgetExceeded) return "PAUSED";
    if (failure instanceof BmcException bmc && List.of(401, 403, 404).contains(bmc.getStatusCode())) return "DENIED";
    return "ERROR";
  }
}
