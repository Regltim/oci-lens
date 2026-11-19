package org.ociarmmonitor.config;

import org.ociarmmonitor.auth.AuthInterceptor;
import java.util.Arrays;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final AuthInterceptor authInterceptor;
  private final String[] allowedOrigins;

  public WebConfig(
    AuthInterceptor authInterceptor,
    @Value("${monitor.cors.allowed-origins:}") String allowedOrigins
  ) {
    this.authInterceptor = authInterceptor;
    this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
      .map(String::trim)
      .filter(origin -> !origin.isBlank())
      .toArray(String[]::new);
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    if (allowedOrigins.length == 0) return;
    registry.addMapping("/**")
      .allowedOrigins(allowedOrigins)
      .allowCredentials(true)
      .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
      .allowedHeaders("*");
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(authInterceptor)
      .addPathPatterns("/**")
      .excludePathPatterns("/auth/login", "/auth/csrf", "/health", "/public/reports/**");
  }
}
