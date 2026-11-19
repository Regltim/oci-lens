package org.ociarmmonitor.serverstatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MaintenanceWindows {
  private final JdbcTemplate jdbc;
  public MaintenanceWindows(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public record Window(String id, String reason, Instant startsAt, Instant endsAt) {}

  public List<Window> list() {
    return jdbc.query("SELECT id,reason,starts_at,ends_at FROM maintenance_window ORDER BY starts_at DESC LIMIT 100",
      (rs, i) -> new Window(rs.getString(1),rs.getString(2),Instant.parse(rs.getString(3)),Instant.parse(rs.getString(4))));
  }

  @Transactional
  public Window create(String reason, Instant start, Instant stop) {
    if (reason == null || reason.isBlank() || reason.length() > 120 || start == null || stop == null ||
      !stop.isAfter(start) || !stop.isAfter(Instant.now()) || Duration.between(start,stop).toDays() > 30) {
      throw new IllegalArgumentException("请输入维护原因，并选择 30 天以内的有效起止时间。");
    }
    if (jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_window WHERE julianday(starts_at) < julianday(?) AND julianday(ends_at) > julianday(?)",
      Integer.class, stop.toString(), start.toString()) > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "该时间与已有维护窗口重叠。");
    Window window = new Window(UUID.randomUUID().toString(), reason.trim(), start, stop);
    jdbc.update("INSERT INTO maintenance_window(id,reason,starts_at,ends_at) VALUES (?,?,?,?)", window.id(),window.reason(),start.toString(),stop.toString());
    return window;
  }

  public boolean active(Instant now) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_window WHERE julianday(starts_at) <= julianday(?) AND julianday(ends_at) > julianday(?)",
      Integer.class,now.toString(),now.toString()) > 0;
  }

  @Transactional
  public void finish(String id) {
    Instant now = Instant.now();
    var current = jdbc.query("SELECT starts_at, ends_at FROM maintenance_window WHERE id = ?", (rs, i) ->
      new Instant[]{Instant.parse(rs.getString(1)), Instant.parse(rs.getString(2))}, id).stream().findFirst()
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "维护窗口不存在。"));
    if (now.isBefore(current[1])) {
      Instant end = now.isBefore(current[0]) ? current[0] : now;
      jdbc.update("UPDATE maintenance_window SET ends_at = ? WHERE id = ?", end.toString(), id);
    }
  }
}
