package org.ociarmmonitor.serverstatus;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class ServerStatusCollectorTest {

  @TempDir
  private Path tempDir;

  @Test
  void collectSystemInfoReadsCpuModelAndRuntimeDetails() throws Exception {
    Files.writeString(tempDir.resolve("cpuinfo"), """
      processor   : 0
      model name  : Ampere Altra Processor
      processor   : 1
      model name  : Ampere Altra Processor
      """);

    ServerStatusCollector collector = new ServerStatusCollector(
      tempDir.toString(),
      tempDir.toString(),
      "jdbc:sqlite:" + tempDir.resolve("monitor.db")
    );

    ServerSystemInfo systemInfo = collector.collectSystemInfo();

    assertThat(systemInfo.cpuModelName()).isEqualTo("Ampere Altra Processor");
    assertThat(systemInfo.availableProcessors()).isGreaterThan(0);
    assertThat(systemInfo.osArch()).isEqualTo(System.getProperty("os.arch", ""));
    assertThat(systemInfo.javaVersion()).isEqualTo(System.getProperty("java.version", ""));
    assertThat(systemInfo.jvmName()).isEqualTo(System.getProperty("java.vm.name", ""));
  }
  @Test
  void separatesCpuWarmupFromMeasuredIdleAndBusySamples(@TempDir Path proc) throws IOException {
    Path stat = proc.resolve("stat");
    Files.writeString(stat, "cpu 1 0 0 99 0 0 0 0 0 0\n");
    var collector = new ServerStatusCollector(proc.toString(), proc.toString(), "jdbc:sqlite:/missing");
    var warmup = collector.sample();
    assertThat(warmup.cpuMeasured()).isFalse();
    Files.writeString(stat, "cpu 1 0 0 199 0 0 0 0 0 0\n");
    var idle = collector.sample();
    assertThat(idle.cpuMeasured()).isTrue();
    assertThat(idle.snapshot().cpuUsagePercent()).isZero();
    Files.writeString(stat, "cpu 11 0 0 289 0 0 0 0 4 0\n");
    var busy = collector.sample();
    assertThat(busy.cpuMeasured()).isTrue();
    assertThat(busy.snapshot().cpuUsagePercent()).isEqualTo(10.0);

    try (var storage = new SingleConnectionDataSource("jdbc:sqlite::memory:", true)) {
      new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(storage);
      var history = new ServerStatusRepository(new JdbcTemplate(storage), 72);
      history.save(warmup.snapshot(), warmup.cpuMeasured(), warmup.networkMeasured());
      history.save(idle.snapshot(), idle.cpuMeasured(), idle.networkMeasured());
      history.save(busy.snapshot(), busy.cpuMeasured(), busy.networkMeasured());
      assertThat(history.listHistory(24, 10)).extracting(ServerMetricPoint::cpuUsagePercent)
        .containsExactly(null, 0.0, 10.0);
    }
  }
}
