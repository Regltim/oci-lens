package org.ociarmmonitor.controller;

import org.ociarmmonitor.common.ApiResponse;
import org.ociarmmonitor.config.FreeQuota;
import org.ociarmmonitor.config.FreeQuotaRepository;
import org.ociarmmonitor.config.OciDiagnosticsResult;
import org.ociarmmonitor.config.OciDiagnosticsService;
import org.ociarmmonitor.config.OciSettingsProvider;
import org.ociarmmonitor.config.OciSettingsStatus;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.ociarmmonitor.oci.OciTarget;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/settings")
public class SettingsController {

  private final FreeQuotaRepository freeQuotaRepository;
  private final OciDiagnosticsService ociDiagnosticsService;
  private final OciSettingsProvider ociSettingsProvider;
  private final OciTarget target;

  public SettingsController(
    FreeQuotaRepository freeQuotaRepository,
    OciDiagnosticsService ociDiagnosticsService,
    OciSettingsProvider ociSettingsProvider,
    OciTarget target
  ) {
    this.freeQuotaRepository = freeQuotaRepository;
    this.ociDiagnosticsService = ociDiagnosticsService;
    this.ociSettingsProvider = ociSettingsProvider;
    this.target = target;
  }

  @GetMapping("/quota")
  public ApiResponse<FreeQuota> getQuota() {
    return ApiResponse.ok(freeQuotaRepository.getQuota());
  }

  @PutMapping("/quota")
  public ApiResponse<FreeQuota> updateQuota(@RequestBody FreeQuota quota) {
    FreeQuota nextQuota = new FreeQuota(
      quota.ampereOcpuHours(),
      quota.ampereMemoryGbHours(),
      quota.blockVolumeGb(),
      quota.outboundDataTransferGb(),
      quota.monitoringIngestionPoints(),
      quota.monitoringRetrievalPoints(),
      Instant.now().toString()
    );
    freeQuotaRepository.save(nextQuota);
    return ApiResponse.ok(nextQuota, "免费额度配置已保存");
  }

  @GetMapping("/oci")
  public ApiResponse<OciSettingsStatus> getOciSettings() {
    return ApiResponse.ok(ociSettingsProvider.getStatus());
  }

  @PostMapping("/oci/diagnostics")
  public ApiResponse<OciDiagnosticsResult> diagnoseOciSettings() {
    OciDiagnosticsResult result = ociDiagnosticsService.diagnose();
    if (result.configured() && result.steps().stream().noneMatch(step -> step.status().equals("FAILED"))) target.verified();
    return ApiResponse.ok(result);
  }
}
