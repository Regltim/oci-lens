package org.ociarmmonitor.common;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

public record HistoryWindow(LocalDate from, LocalDate to) {
  public static HistoryWindow parse(String from, String to) {
    try {
      LocalDate start = LocalDate.parse(from);
      LocalDate end = LocalDate.parse(to);
      if (!start.isAfter(end) && !end.isAfter(LocalDate.now(ZoneOffset.UTC)) && ChronoUnit.DAYS.between(start, end) < 366) {
        return new HistoryWindow(start, end);
      }
    } catch (DateTimeParseException ignored) {
      // Use the same validation message for malformed and out-of-range windows.
    }
    throw new IllegalArgumentException("请选择有效的 UTC 日期范围，最多 366 天，结束日期不得晚于今天。");
  }

  public String start() { return from.toString(); }
  public String endExclusive() { return to.plusDays(1).toString(); }
  public boolean useDailyAverages() { return ChronoUnit.DAYS.between(from, to) >= 7; }
}
