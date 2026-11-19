package org.ociarmmonitor.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangeCredentialsRequest(
  @NotBlank(message = "当前密码不能为空") String currentPassword,
  @Size(max = 64, message = "账号名最多 64 个字符") String username,
  @Size(max = 128, message = "新密码最多 128 个字符") String newPassword,
  @Size(max = 128, message = "确认密码最多 128 个字符") String confirmPassword
) {
}
