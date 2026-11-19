package org.ociarmmonitor.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

public final class AdminPasswordResetCommand {
  public static final String NAME = "reset-admin-password";

  private AdminPasswordResetCommand() {
  }

  public static int run(String[] args, String databasePath, InputStream input) {
    try {
      String username = parseUsername(args);
      if (databasePath == null || databasePath.isBlank()) {
        throw new IllegalArgumentException("未指定已部署的 SQLite 数据库。");
      }
      String password = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)).readLine();
      reset(Path.of(databasePath), username, password);
      System.out.println("管理员密码已重置。");
      return 0;
    } catch (IllegalArgumentException failure) {
      System.err.println(failure.getMessage());
      return 1;
    } catch (IOException | SQLException | DataAccessException failure) {
      System.err.println("无法更新管理员密码，请检查数据库权限和备份。");
      return 1;
    }
  }

  private static String parseUsername(String[] args) {
    if (args.length == 1) {
      return null;
    }
    if (args.length == 3 && "--username".equals(args[1]) && !args[2].isBlank()) {
      return args[2];
    }
    throw new IllegalArgumentException("用法：reset-admin-password [--username 当前账号名]，密码从标准输入读取。");
  }

  static void reset(Path database, String username, String password) throws SQLException {
    if (password == null || password.isBlank() || password.length() < 12 || password.length() > 128) {
      throw new IllegalArgumentException("新密码需在 12 到 128 位之间。");
    }
    if (!Files.isRegularFile(database) || !Files.isReadable(database)) {
      throw new IllegalArgumentException("找不到可读取的现有管理员数据库。");
    }

    String url = "jdbc:sqlite:" + database.toAbsolutePath().toUri().toASCIIString() + "?mode=rw";
    try (Connection connection = DriverManager.getConnection(url)) {
      JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      AdminUserRepository repository = new AdminUserRepository(jdbc);
      AdminUser user = (username == null ? repository.findSingleAdmin() : repository.findByUsername(username))
        .orElseThrow(() -> new IllegalArgumentException("找不到要重置的管理员，请核对当前账号名。"));
      PasswordHasher hasher = new PasswordHasher();
      if (hasher.matches(password, user)) {
        throw new IllegalArgumentException("新密码不能与当前密码一致。");
      }
      String salt = hasher.generateSalt();
      repository.updateCredentials(user, user.username(), hasher.hash(password, salt), salt);
    }
  }
}
