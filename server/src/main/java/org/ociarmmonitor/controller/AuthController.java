package org.ociarmmonitor.controller;

import org.ociarmmonitor.auth.AuthService;
import org.ociarmmonitor.auth.AuthSession;
import org.ociarmmonitor.auth.ChangeCredentialsRequest;
import org.ociarmmonitor.auth.ChangePasswordRequest;
import org.ociarmmonitor.auth.LoginRequest;
import org.ociarmmonitor.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/auth")
public class AuthController {

  private final AuthService authService;
  private Instant loginWindowEnd = Instant.EPOCH;
  private int failedLogins;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @GetMapping("/csrf")
  public ApiResponse<Map<String, String>> csrf(CsrfToken token) {
    return ApiResponse.ok(Map.of("token", token.getToken()));
  }

  @PostMapping("/login")
  public synchronized ApiResponse<AuthSession> login(@Valid @RequestBody LoginRequest input, HttpServletRequest request) {
    Instant now = Instant.now();
    if (!now.isBefore(loginWindowEnd)) {
      failedLogins = 0;
      loginWindowEnd = now.plusSeconds(60);
    }
    if (failedLogins >= 8) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "登录尝试过多，请一分钟后重试。");
    AuthSession signedIn;
    try {
      signedIn = authService.login(input, request.getSession());
    } catch (IllegalArgumentException failure) {
      failedLogins++;
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误。");
    }
    request.changeSessionId();
    failedLogins = 0;
    return ApiResponse.ok(signedIn, "登录成功");
  }

  @GetMapping("/me")
  public ApiResponse<AuthSession> current(HttpSession session) {
    return ApiResponse.ok(authService.current(session));
  }

  @PostMapping("/password")
  public ApiResponse<AuthSession> changePassword(@Valid @RequestBody ChangePasswordRequest input, HttpServletRequest request) {
    AuthSession result = authService.changePassword(input, request.getSession());
    request.changeSessionId();
    return ApiResponse.ok(result, "密码已更新");
  }

  @PostMapping("/credentials")
  public ApiResponse<AuthSession> changeCredentials(@Valid @RequestBody ChangeCredentialsRequest input, HttpServletRequest request) {
    AuthSession result = authService.changeCredentials(input, request.getSession());
    request.changeSessionId();
    return ApiResponse.ok(result, "登录凭据已更新");
  }

  @PostMapping("/logout")
  public ApiResponse<Void> logout(HttpSession session) {
    authService.logout(session);
    return ApiResponse.ok(null, "已退出登录");
  }
}
