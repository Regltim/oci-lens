package org.ociarmmonitor.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class RequestSecurity {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    // Session authorization is enforced by AuthInterceptor after MVC resolves the request.
    return http.authorizeHttpRequests(registry -> registry.anyRequest().permitAll())
      .logout(AbstractHttpConfigurer::disable)
      .requestCache(AbstractHttpConfigurer::disable)
      .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, failure) -> {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"请求令牌失效，请刷新后重试。\"}");
      }))
      .build();
  }
}
