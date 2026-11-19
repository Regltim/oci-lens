package org.ociarmmonitor.controller;

import org.ociarmmonitor.common.ApiResponse;
import org.ociarmmonitor.cost.CostService;
import org.ociarmmonitor.cost.ManualCostRepository;
import org.ociarmmonitor.oci.LensLedger;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.ociarmmonitor.cost.ManualCost;
import org.ociarmmonitor.cost.ManualCostCreateRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/costs")
public class CostController {

  private final CostService costService;
  private final LensLedger ledger;
  private final ManualCostRepository manual;

  public CostController(CostService costService, LensLedger ledger, ManualCostRepository manual) {
    this.costService = costService;
    this.ledger = ledger;
    this.manual = manual;
  }

  @GetMapping("/summary")
  public ApiResponse<Summary> getSummary(@RequestParam(required = false) YearMonth month) {
    YearMonth requested = month == null ? YearMonth.now(ZoneOffset.UTC) : month;
    return ApiResponse.ok(new Summary(ledger.costs(requested),manual.listMonth(requested),ledger.coverage("cost",requested),ledger.coveredThrough("cost",requested)));
  }

  @PostMapping("/manual")
  public ApiResponse<ManualCost> createManualCost(@Valid @RequestBody ManualCostCreateRequest request) {
    validate(request);
    return ApiResponse.ok(costService.createManualCost(request), "费用记录已保存");
  }

  @PutMapping("/manual/{id}")
  public ApiResponse<Void> updateManualCost(@PathVariable String id, @Valid @RequestBody ManualCostCreateRequest request) {
    validate(request);
    manual.update(id, request);
    return ApiResponse.ok(null, "费用记录已保存");
  }

  private void validate(ManualCostCreateRequest request) {
    try {
      java.time.LocalDate.parse(request.occurredOn());
      java.util.Currency.getInstance(request.currency());
    } catch (IllegalArgumentException | java.time.DateTimeException failure) {
      throw new IllegalArgumentException("请选择有效币种和日期。");
    }
  }

  @DeleteMapping("/manual/{id}")
  public ApiResponse<Void> deleteManualCost(@PathVariable String id) {
    costService.deleteManualCost(id);
    return ApiResponse.ok(null, "费用记录已删除");
  }

  public record Summary(List<LensLedger.CostEntry> daily, List<ManualCost> manualCosts, List<LensLedger.CoverageDay> coverage, String through) {}
}
