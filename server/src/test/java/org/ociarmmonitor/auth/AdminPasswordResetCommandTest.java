package org.ociarmmonitor.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;

class AdminPasswordResetCommandTest {
  @TempDir
  Path directory;

  private final PasswordHasher hasher = new PasswordHasher();

  @Test
  void existingAdminSurvivesStartupAndCanBeResetWithoutRenaming() throws Exception {
    Path database = directory.resolve("oci-lens.db");
    try (var storage = createDatabase(database)) {
      var repository = new AdminUserRepository(new JdbcTemplate(storage));
      var service = new AuthService(repository, hasher);
      createAdmin(repository, "admin", "fixture-initial-password");
      var session = new MockHttpSession();
      service.login(new LoginRequest("admin", "fixture-initial-password"), session);
      service.changeCredentials(new ChangeCredentialsRequest(
        "fixture-initial-password", "changed-admin", "fixture-updated-password", "fixture-updated-password"), session);
      AdminUser beforeReset = repository.findByUsername("changed-admin").orElseThrow();

      var initializer = new AdminUserInitializer("admin", "",
        directory.resolve("missing-bootstrap").toString(), repository, hasher);
      initializer.run();
      assertThat(repository.findByUsername("changed-admin")).contains(beforeReset);
      assertThat(service.login(new LoginRequest("changed-admin", "fixture-updated-password"), new MockHttpSession())
        .username()).isEqualTo("changed-admin");

      int status = AdminPasswordResetCommand.run(new String[]{AdminPasswordResetCommand.NAME}, database.toString(),
        new ByteArrayInputStream("fixture-reset-password\n".getBytes(StandardCharsets.UTF_8)));
      assertThat(status).isZero();
      AdminUser afterReset = repository.findByUsername("changed-admin").orElseThrow();
      assertThat(afterReset.id()).isEqualTo(beforeReset.id());
      assertThat(afterReset.passwordSalt()).isNotEqualTo(beforeReset.passwordSalt());
      assertThat(repository.count()).isEqualTo(1);
      assertThat(service.isSessionCurrent(session)).isFalse();
      assertThatThrownBy(() -> service.login(new LoginRequest("changed-admin", "fixture-updated-password"), new MockHttpSession()))
        .isInstanceOf(IllegalArgumentException.class);
      assertThat(service.login(new LoginRequest("changed-admin", "fixture-reset-password"), new MockHttpSession())
        .username()).isEqualTo("changed-admin");
    }
  }

  @Test
  void resetRejectsInvalidTargetsAndChangesOnlyTheNamedAdmin() throws Exception {
    Path database = directory.resolve("existing.db");
    try (var storage = createDatabase(database)) {
      var repository = new AdminUserRepository(new JdbcTemplate(storage));
      AdminUser first = createAdmin(repository, "first", "fixture-first-password");
      AdminUser second = createAdmin(repository, "second", "fixture-second-password");

      assertThatThrownBy(() -> AdminPasswordResetCommand.reset(database, null, "fixture-valid-replacement"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--username");
      assertThatThrownBy(() -> AdminPasswordResetCommand.reset(database, "second", "short"))
        .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> AdminPasswordResetCommand.reset(database, "unknown", "fixture-valid-replacement"))
        .isInstanceOf(IllegalArgumentException.class);
      assertThat(repository.findByUsername("second")).contains(second);

      AdminPasswordResetCommand.reset(database, "second", "fixture-valid-replacement");
      assertThat(repository.findByUsername("first")).contains(first);
      assertThat(hasher.matches("fixture-valid-replacement", repository.findByUsername("second").orElseThrow())).isTrue();
    }

    Path missing = directory.resolve("missing.db");
    assertThatThrownBy(() -> AdminPasswordResetCommand.reset(missing, null, "fixture-valid-replacement"))
      .isInstanceOf(IllegalArgumentException.class);
    assertThat(missing).doesNotExist();
  }

  private SingleConnectionDataSource createDatabase(Path database) {
    var storage = new SingleConnectionDataSource("jdbc:sqlite:" + database, true);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(storage);
    return storage;
  }

  private AdminUser createAdmin(AdminUserRepository repository, String username, String password) {
    String salt = hasher.generateSalt();
    return repository.create(username, hasher.hash(password, salt), salt);
  }
}
