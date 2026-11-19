package org.ociarmmonitor.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminUserRepository {

  private final JdbcTemplate jdbcTemplate;

  public AdminUserRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public long count() {
    Long count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM admin_user", Long.class);
    return count == null ? 0 : count;
  }

  public Optional<AdminUser> findByUsername(String username) {
    List<AdminUser> users = jdbcTemplate.query("""
      SELECT id, username, password_hash, password_salt, created_at, updated_at
      FROM admin_user
      WHERE username = ?
      """, (resultSet, rowNum) -> mapUser(resultSet), username);
    return users.stream().findFirst();
  }

  public AdminUser create(String username, String passwordHash, String passwordSalt) {
    String now = Instant.now().toString();
    AdminUser adminUser = new AdminUser(UUID.randomUUID().toString(), username, passwordHash, passwordSalt, now, now);
    jdbcTemplate.update("""
      INSERT INTO admin_user(id, username, password_hash, password_salt, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?)
      """,
      adminUser.id(),
      adminUser.username(),
      adminUser.passwordHash(),
      adminUser.passwordSalt(),
      adminUser.createdAt(),
      adminUser.updatedAt()
    );
    return adminUser;
  }

  public AdminUser updateCredentials(AdminUser original, String username, String passwordHash, String passwordSalt) {
    String updatedAt = Instant.now().toString();
    int updatedRows = jdbcTemplate.update("""
      UPDATE admin_user
      SET username = ?, password_hash = ?, password_salt = ?, updated_at = ?
      WHERE id = ? AND updated_at = ?
        AND NOT EXISTS (SELECT 1 FROM admin_user WHERE username = ? AND id <> ?)
      """, username, passwordHash, passwordSalt, updatedAt, original.id(), original.updatedAt(), username, original.id());
    if (updatedRows != 1) {
      throw new IllegalArgumentException("账号名已占用或登录凭据已更新，请核对账号后重试。");
    }
    return new AdminUser(original.id(), username, passwordHash, passwordSalt, original.createdAt(), updatedAt);
  }

  private AdminUser mapUser(ResultSet resultSet) throws SQLException {
    return new AdminUser(
      resultSet.getString("id"),
      resultSet.getString("username"),
      resultSet.getString("password_hash"),
      resultSet.getString("password_salt"),
      resultSet.getString("created_at"),
      resultSet.getString("updated_at")
    );
  }
}
