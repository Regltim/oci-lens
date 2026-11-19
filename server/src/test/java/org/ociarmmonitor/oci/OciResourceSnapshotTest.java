package org.ociarmmonitor.oci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.core.BlockstorageClient;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.model.BootVolume;
import com.oracle.bmc.core.model.BootVolumeAttachment;
import com.oracle.bmc.core.model.Volume;
import com.oracle.bmc.core.responses.ListBootVolumeAttachmentsResponse;
import com.oracle.bmc.core.responses.ListBootVolumesResponse;
import com.oracle.bmc.core.responses.ListVolumeAttachmentsResponse;
import com.oracle.bmc.core.responses.ListVolumesResponse;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.model.AvailabilityDomain;
import com.oracle.bmc.identity.model.RegionSubscription;
import com.oracle.bmc.identity.responses.ListAvailabilityDomainsResponse;
import com.oracle.bmc.identity.responses.ListRegionSubscriptionsResponse;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.usageapi.UsageapiClient;
import com.oracle.bmc.usageapi.model.UsageAggregation;
import com.oracle.bmc.usageapi.model.UsageSummary;
import com.oracle.bmc.usageapi.responses.RequestSummarizedUsagesResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.ociarmmonitor.config.FreeQuotaRepository;
import org.ociarmmonitor.config.OciAuthMode;
import org.ociarmmonitor.config.OciSettings;
import org.ociarmmonitor.config.OciSettingsProvider;
import org.ociarmmonitor.instance.CloudInstanceRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class OciResourceSnapshotTest {
  private static final String REGION = "ap-tokyo-1";
  private static final String TENANCY = "tenant-fixture";

  @Test
  void capturesDetachedCapacityAndRetainsLastSuccessfulVolumeSnapshotOnDenial() {
    try (var db = storage()) {
      var jdbc = new JdbcTemplate(db);
      var transactions = new TransactionTemplate(new DataSourceTransactionManager(db));
      var factory = mock(OciClientFactory.class);
      var provider = mock(BasicAuthenticationDetailsProvider.class);
      var identity = mock(IdentityClient.class);
      var block = mock(BlockstorageClient.class);
      var compute = mock(ComputeClient.class);
      when(factory.identityClient(provider, REGION)).thenReturn(identity);
      when(factory.blockstorageClient(provider, REGION)).thenReturn(block);
      when(factory.computeClient(provider, REGION)).thenReturn(compute);
      when(identity.listAvailabilityDomains(any())).thenReturn(ListAvailabilityDomainsResponse.builder()
        .items(List.of(AvailabilityDomain.builder().name("AD-1").build())).build());
      when(identity.listRegionSubscriptions(any())).thenReturn(ListRegionSubscriptionsResponse.builder()
        .items(List.of(RegionSubscription.builder().regionName(REGION).isHomeRegion(true).build())).build());
      when(block.listBootVolumes(any())).thenReturn(ListBootVolumesResponse.builder()
        .items(List.of(BootVolume.builder().id("boot").sizeInGBs(50L).build())).build());
      when(block.listVolumes(any())).thenReturn(ListVolumesResponse.builder()
        .items(List.of(Volume.builder().id("unattached").sizeInGBs(100L).build())).build());
      when(compute.listBootVolumeAttachments(any())).thenReturn(ListBootVolumeAttachmentsResponse.builder()
        .items(List.of(BootVolumeAttachment.builder().bootVolumeId("boot").instanceId("instance")
          .lifecycleState(BootVolumeAttachment.LifecycleState.Attached).build())).build());
      when(compute.listVolumeAttachments(any())).thenReturn(ListVolumeAttachmentsResponse.builder().items(List.of()).build());
      var service = new OciVolumeService(factory, new OciBudgetGate(jdbc, 80, 1000), jdbc, transactions);
      service.refresh(settings(), provider, TENANCY);
      assertThat(service.totalGb()).isEqualTo(150);
      var first = service.forInstance("instance");
      assertThat(first.snapshot().status()).isEqualTo("AVAILABLE");
      assertThat(first.snapshot().homeRegion()).isEqualTo(REGION);
      assertThat(first.volumes()).singleElement().satisfies(volume -> assertThat(volume.sizeGb()).isEqualTo(50));

      when(block.listVolumes(any())).thenThrow(new BmcException(403, "NotAuthorized", "fixture", "fixture"));
      service.refresh(settings(), provider, TENANCY);
      var failed = service.forInstance("instance");
      assertThat(failed.snapshot().status()).isEqualTo("DENIED");
      assertThat(failed.snapshot().succeededAt()).isEqualTo(first.snapshot().succeededAt());
      assertThat(failed.volumes()).containsExactlyElementsOf(first.volumes());
      assertThat(service.totalGb()).isEqualTo(150);
    }
  }

  @Test
  void countsSpecificSkuAndUnitAndKeepsLastUsageWhenTheSourceIsEmpty() {
    try (var db = storage()) {
      var jdbc = new JdbcTemplate(db);
      var source = mock(OciSettingsProvider.class);
      var volumes = mock(OciVolumeService.class);
      when(source.isConfigured(any())).thenReturn(true);
      when(source.billingEnabled()).thenReturn(true);
      when(volumes.status()).thenReturn(new OciVolumeService.Status("PENDING", null, null, null, null, REGION, false));
      var service = new OciQuotaService(new FreeQuotaRepository(jdbc), new CloudInstanceRepository(jdbc), source, volumes,
        new OciBudgetGate(jdbc, 80, 1000), jdbc);
      var client = mock(UsageapiClient.class);
      when(client.requestSummarizedUsages(any())).thenReturn(usage(List.of(
        row("B93297", "OCPU Per Hour", REGION, "100.5"), row("B93297", "hour", "us-ashburn-1", "45"),
        row("B93298", "GB", REGION, "1000"), row("B93455", "Gigabytes", REGION, "5"),
        row("B88327", "gib", "us-ashburn-1", "10"), row("UNKNOWN", "GB", REGION, "2000")
      )));
      LocalDate day = LocalDate.of(2026, 6, 20);
      YearMonth month = YearMonth.from(day);
      service.refresh(client, TENANCY, day);
      var items = service.summary(month).items();
      assertThat(items.get(0).used()).isEqualTo(100.5);
      assertThat(items.get(0).scope()).contains(REGION);
      assertThat(items.get(1).used()).isNull();
      assertThat(items.get(1).status()).isEqualTo("NO_DATA");
      assertThat(items.get(3).used()).isEqualTo(15.73741824);
      assertThat(items.get(3).requestedThrough()).isEqualTo("2026-06-19");

      when(client.requestSummarizedUsages(any())).thenReturn(usage(List.of()));
      service.refresh(client, TENANCY, day.plusDays(1));
      var stale = service.summary(month).items().get(0);
      assertThat(stale.status()).isEqualTo("NO_DATA");
      assertThat(stale.used()).isEqualTo(100.5);
      assertThat(stale.requestedThrough()).isEqualTo("2026-06-19");
      when(source.billingEnabled()).thenReturn(false);
      assertThat(service.summary(month).items().get(0).status()).isEqualTo("DISABLED");
    }
  }

  private static UsageSummary row(String sku, String unit, String region, String quantity) {
    return UsageSummary.builder().skuPartNumber(sku).unit(unit).region(region)
      .computedQuantity(new BigDecimal(quantity)).build();
  }

  private static RequestSummarizedUsagesResponse usage(List<UsageSummary> rows) {
    return RequestSummarizedUsagesResponse.builder()
      .usageAggregation(UsageAggregation.builder().items(rows).build()).build();
  }

  private static OciSettings settings() {
    return new OciSettings(OciAuthMode.INSTANCE_PRINCIPAL, TENANCY, "", "", REGION, "scope", "", "", "", "");
  }

  private SingleConnectionDataSource storage() {
    var db = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(db);
    return db;
  }
}
