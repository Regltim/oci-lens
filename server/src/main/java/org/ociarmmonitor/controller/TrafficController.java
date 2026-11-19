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

  @GetMapping("/summary")
  public ApiResponse<Summary> getSummary(@RequestParam(required = false) YearMonth month,
                                         @RequestParam(defaultValue = "") String instanceId) {
    YearMonth requested = month == null ? YearMonth.now(ZoneOffset.UTC) : month;
    List<TrafficDaily> rows = trafficRepository.listMonth(requested).stream()
      .filter(row -> instanceId.isBlank() || row.instanceId().equals(instanceId)).toList();
    return ApiResponse.ok(new Summary(rows, ledger.coverage("traffic", requested)));
  }

  public record Summary(List<TrafficDaily> daily, List<LensLedger.CoverageDay> coverage) {}
}
