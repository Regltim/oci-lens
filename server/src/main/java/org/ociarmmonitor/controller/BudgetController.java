package org.ociarmmonitor.controller;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import org.ociarmmonitor.common.ApiResponse;
import org.ociarmmonitor.oci.LensLedger;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/cost-budgets")
class BudgetController {
  private final LensLedger ledger;
  BudgetController(LensLedger ledger) { this.ledger = ledger; }

  @GetMapping
  ApiResponse<List<LensLedger.Budget>> list(@RequestParam(required = false) YearMonth month) {
    return ApiResponse.ok(ledger.budgets(month == null ? YearMonth.now(ZoneOffset.UTC) : month));
  }

  @Transactional
  @PutMapping("/{month}/{currency}")
  ApiResponse<LensLedger.Budget> put(@PathVariable YearMonth month, @PathVariable String currency, @Valid @RequestBody Limit limit) {
    return ApiResponse.ok(ledger.saveBudget(month, Currency.getInstance(currency).getCurrencyCode(), limit.amount()));
  }

  record Limit(@NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount) {}
}
