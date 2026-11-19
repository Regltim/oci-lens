package org.ociarmmonitor.auth;

import jakarta.servlet.http.HttpSession;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

  public static final String SESSION_USER_KEY = "MONITOR_AUTH_USER";

  private final AdminUserRepository adminUserRepository;
  private final PasswordHasher passwordHasher;

  public AuthService(AdminUserRepository adminUserRepository, PasswordHasher passwordHasher) {
    this.adminUserRepository = adminUserRepository;
    this.passwordHasher = passwordHasher;
  }

  public AuthSession login(LoginRequest request, HttpSession session) {
    AdminUser adminUser = adminUserRepository.findByUsername(request.username())
      .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
    if (!passwordHasher.matches(request.password(), adminUser)) {
      throw new IllegalArgumentException("用户名或密码错误");
    }
    return bindSession(session, adminUser);
  }

  public AuthSession current(HttpSession session) {
    return new AuthSession(activeUser(session).orElseThrow(() -> new IllegalArgumentException("未登录或登录已过期")).username());
  }

  public boolean isSessionCurrent(HttpSession session) {
    return activeUser(session).isPresent();
  }

  private Optional<AdminUser> activeUser(HttpSession session) {
    if (session == null) {
      return Optional.empty();
    }
    Object attribute;
    try {
      attribute = session.getAttribute(SESSION_USER_KEY);
    } catch (IllegalStateException expired) {
      return Optional.empty();
    }
    if (!(attribute instanceof SessionIdentity identity)) {
      return Optional.empty();
    }
    return adminUserRepository.findByUsername(identity.username()).filter(user -> user.updatedAt().equals(identity.updatedAt()));
  }

  private AuthSession bindSession(HttpSession session, AdminUser user) {
    session.setAttribute(SESSION_USER_KEY, new SessionIdentity(user.username(), user.updatedAt()));
    return new AuthSession(user.username());
  }

  private AdminUser verifiedUser(HttpSession session, String currentPassword) {
    AdminUser user = activeUser(session).orElseThrow(() -> new IllegalArgumentException("未登录或登录已过期"));
    if (!passwordHasher.matches(currentPassword, user)) {
      throw new IllegalArgumentException("当前密码不正确");
    }
    return user;
  }

  public AuthSession changePassword(ChangePasswordRequest request, HttpSession session) {
    AdminUser user = verifiedUser(session, request.currentPassword());
    return saveCredentials(session, user, user.username(), request.newPassword(), request.confirmPassword());
  }

  public AuthSession changeCredentials(ChangeCredentialsRequest request, HttpSession session) {
    AdminUser user = verifiedUser(session, request.currentPassword());
    String username = request.username() == null ? user.username() : request.username().strip();
    if (request.username() != null && (username.isBlank() || username.codePoints().anyMatch(Character::isISOControl))) {
      throw new IllegalArgumentException("请输入有效的账号名。");
    }
    String password = request.newPassword();
    if (password != null && (password.isBlank() || password.length() < 12)) {
      throw new IllegalArgumentException("新密码至少 12 位。");
    }
    return saveCredentials(session, user, username, password, request.confirmPassword());
  }

  private AuthSession saveCredentials(HttpSession session, AdminUser user, String username, String password, String confirmation) {
    if (!Objects.equals(password, confirmation)) {
      throw new IllegalArgumentException("两次输入的新密码不一致");
    }
    if (password == null && username.equals(user.username())) {
      return new AuthSession(username);
    }
    if (password != null && passwordHasher.matches(password, user)) {
      throw new IllegalArgumentException("新密码不能与当前密码一致");
    }
    String salt = password != null ? passwordHasher.generateSalt() : user.passwordSalt();
    String hash = password != null ? passwordHasher.hash(password, salt) : user.passwordHash();
    return bindSession(session, adminUserRepository.updateCredentials(user, username, hash, salt));
  }

  public void logout(HttpSession session) {
    session.invalidate();
  }

  private record SessionIdentity(String username, String updatedAt) {}
}
