package org.ociarmmonitor.controller;

import org.ociarmmonitor.common.ApiResponse;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class HealthController {
  private final ApplicationAvailability availability;
  private final JdbcTemplate jdbc;

  HealthController(ApplicationAvailability availability, JdbcTemplate jdbc) {
    this.availability = availability;
    this.jdbc = jdbc;
  }

  @GetMapping("/health")
  ApiResponse<String> check() {
    if (availability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "服务尚未就绪。");
    jdbc.queryForObject("SELECT 1", Integer.class);
    return ApiResponse.ok("ready");
  }
}
