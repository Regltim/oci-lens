package org.ociarmmonitor.cost;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ManualCostCreateRequest(
  @NotBlank(message = "费用名称不能为空") @Size(max = 100) String costName,
  @NotBlank(message = "费用分类不能为空") @Size(max = 60) String category,
  @DecimalMin(value = "0.01", message = "费用金额必须大于 0") @Digits(integer = 12, fraction = 2) double amount,
  @NotBlank(message = "币种不能为空")
  @Pattern(regexp = "[A-Z]{3}", message = "请填写 ISO 三位币种代码") String currency,
  @NotBlank(message = "发生日期不能为空") String occurredOn,
  @Size(max = 500) String note
) {
}
