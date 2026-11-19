package org.ociarmmonitor.controller;

import java.time.Instant;
import java.util.List;
import org.ociarmmonitor.common.ApiResponse;
import org.ociarmmonitor.serverstatus.MaintenanceWindows;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/maintenance-windows")
class MaintenanceController {
  private final MaintenanceWindows service;
  MaintenanceController(MaintenanceWindows service) { this.service = service; }
  @GetMapping ApiResponse<List<MaintenanceWindows.Window>> list() { return ApiResponse.ok(service.list()); }
  @PostMapping ApiResponse<MaintenanceWindows.Window> create(@RequestBody Request request) {
    return ApiResponse.ok(service.create(request.reason(), request.startsAt(), request.endsAt()));
  }
  @PostMapping("/{id}/end") ApiResponse<Void> end(@PathVariable String id) {
    service.finish(id);
    return ApiResponse.ok(null);
  }
  record Request(String reason, Instant startsAt, Instant endsAt) {}
}
