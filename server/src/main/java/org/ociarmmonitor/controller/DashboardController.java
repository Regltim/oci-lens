package org.ociarmmonitor.controller;

import org.ociarmmonitor.common.ApiResponse;
import org.ociarmmonitor.dashboard.DashboardService;
import org.ociarmmonitor.dashboard.DashboardSummary;
import org.ociarmmonitor.oci.OciQuotaService;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dashboard")
public class DashboardController {

  private final DashboardService dashboardService;

  private final OciQuotaService quotas;

  public DashboardController(DashboardService dashboardService, OciQuotaService quotas) {
    this.dashboardService = dashboardService;
    this.quotas = quotas;
  }

  @GetMapping("/quota")
  public ApiResponse<OciQuotaService.Summary> quotas(@RequestParam(required = false) YearMonth month) {
    return ApiResponse.ok(quotas.summary(month == null ? YearMonth.now(ZoneOffset.UTC) : month));
  }

  @GetMapping("/summary")
  public ApiResponse<DashboardSummary> getSummary() {
    return ApiResponse.ok(dashboardService.getSummary());
  }
}
