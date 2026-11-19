package org.ociarmmonitor;

import org.ociarmmonitor.auth.AdminPasswordResetCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OciArmCostMonitorApplication {

  public static void main(String[] args) {
    if (args.length > 0 && AdminPasswordResetCommand.NAME.equals(args[0])) {
      System.exit(AdminPasswordResetCommand.run(args, System.getenv("OCI_MONITOR_DB"), System.in));
      return;
    }
    SpringApplication.run(OciArmCostMonitorApplication.class, args);
  }
}
