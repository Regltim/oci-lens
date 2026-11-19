package org.ociarmmonitor.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class AdminUserInitializer implements CommandLineRunner {

  private final String adminUsername;
  private final String adminPassword;
  private final String passwordFile;
  private final AdminUserRepository adminUserRepository;
  private final PasswordHasher passwordHasher;

  public AdminUserInitializer(
    @Value("${monitor.admin.username:}") String adminUsername,
    @Value("${monitor.admin.password:}") String adminPassword,
    @Value("${monitor.admin.password-file:}") String passwordFile,
    AdminUserRepository adminUserRepository,
    PasswordHasher passwordHasher
  ) {
    this.adminUsername = adminUsername;
    this.adminPassword = adminPassword;
    this.passwordFile = passwordFile;
    this.adminUserRepository = adminUserRepository;
    this.passwordHasher = passwordHasher;
  }

  @Override
  public void run(String... args) throws IOException {
    if (adminUserRepository.count() > 0) return;
    String password = passwordFile.isBlank() ? adminPassword : Files.readString(Path.of(passwordFile)).replaceFirst("\\R$", "");
    if (adminUsername.isBlank() || password.length() < 12) throw new IllegalStateException("首次部署需要至少 12 位的管理员密码文件。");
    String salt = passwordHasher.generateSalt();
    adminUserRepository.create(adminUsername, passwordHasher.hash(password, salt), salt);
  }
}
