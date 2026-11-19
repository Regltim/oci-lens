package org.ociarmmonitor.controller;

import org.ociarmmonitor.common.ApiResponse;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.ociarmmonitor.oci.LensLedger;
import org.ociarmmonitor.traffic.TrafficRepository;
import org.ociarmmonitor.traffic.TrafficDaily;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/traffic")
public class TrafficController {

  private final TrafficRepository trafficRepository;
  private final LensLedger ledger;

  public TrafficController(TrafficRepository trafficRepository, LensLedger ledger) {
    this.trafficRepository = trafficRepository;
    this.ledger = ledger;
  }

  @GetMapping("/months")
  public ApiResponse<List<String>> listMonths(@RequestParam(defaultValue = "current") String source) {
    return ApiResponse.ok(trafficRepository.listMonths(legacy(source)));
  }

  private boolean legacy(String source) {
    if ("legacy".equals(source)) return true;
    if ("current".equals(source)) return false;
    throw new IllegalArgumentException("流量数据来源无效。");
  }

  @GetMapping("/summary")
  public ApiResponse<Summary> getSummary(@RequestParam(required = false) YearMonth month,
                                         @RequestParam(defaultValue = "") String instanceId,
                                         @RequestParam(defaultValue = "current") String source) {
    YearMonth requested = month == null ? YearMonth.now(ZoneOffset.UTC) : month;
    boolean archived = legacy(source);
    List<TrafficDaily> rows = (archived ? trafficRepository.listLegacyMonth(requested) : trafficRepository.listMonth(requested)).stream()
      .filter(row -> instanceId.isBlank() || row.instanceId().equals(instanceId)).toList();
    return ApiResponse.ok(new Summary(rows, archived ? List.of() : ledger.coverage("traffic", requested)));
  }

  public record Summary(List<TrafficDaily> daily, List<LensLedger.CoverageDay> coverage) {}
}
