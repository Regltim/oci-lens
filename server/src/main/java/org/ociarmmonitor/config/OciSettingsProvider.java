package org.ociarmmonitor.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OciSettingsProvider {

  private final String configFilePath;
  private final String profile;
  private final String region;
  private final String compartmentOcid;
  private final String tenancyOcid;
  private final OciAuthMode authMode;
  private final String instanceId;
  @Value("${monitor.oci.billing-enabled:false}")
  private boolean billingEnabled;
  @Value("${monitor.oci.network-compartment-ocid:}")
  private String networkCompartment = "";

  public OciSettingsProvider(
    @Value("${monitor.oci.auth-mode:config_file}") String authMode,
    @Value("${monitor.oci.config-file-path:}") String configFilePath,
    @Value("${monitor.oci.profile:DEFAULT}") String profile,
    @Value("${monitor.oci.region:}") String region,
    @Value("${monitor.oci.compartment-ocid:}") String compartmentOcid,
    @Value("${monitor.oci.tenancy-ocid:}") String tenancyOcid
  ) {
    boolean auto = "auto".equalsIgnoreCase(authMode);
    JsonNode metadata = auto || "instance_principal".equalsIgnoreCase(authMode) ? readMetadata() : null;
    this.instanceId = metadata(metadata, "id", "instance");
    this.authMode = auto ? (instanceId.isBlank() ? OciAuthMode.CONFIG_FILE : OciAuthMode.INSTANCE_PRINCIPAL) : OciAuthMode.from(authMode);
    this.configFilePath = normalize(configFilePath);
    this.profile = blankToDefault(profile, "DEFAULT");
    String discoveredRegion = metadata == null ? "" : metadata.path("canonicalRegionName").asText("");
    this.region = blankToDefault(region, discoveredRegion);
    this.compartmentOcid = blankToDefault(compartmentOcid, metadata(metadata, "compartmentId", "compartment"));
    this.tenancyOcid = blankToDefault(tenancyOcid, metadata(metadata, "tenantId", "tenancy"));
  }

  private static JsonNode readMetadata() {
    if (!System.getProperty("os.name", "").startsWith("Linux")) return null;
    try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
      HttpRequest request = HttpRequest.newBuilder(URI.create("http://169.254.169.254/opc/v2/instance/"))
        .timeout(Duration.ofSeconds(2)).header("Authorization", "Bearer Oracle").GET().build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      return response.statusCode() == 200 ? new ObjectMapper().readTree(response.body()) : null;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return null;
    } catch (java.io.IOException failure) {
      return null;
    }
  }

  private static String metadata(JsonNode node, String key, String type) {
    String value = node == null ? "" : node.path(key).asText("");
    return value.matches("ocid1\\." + type + "\\.[A-Za-z0-9._-]+") ? value : "";
  }

  public boolean billingEnabled() { return billingEnabled; }

  public String networkCompartment() { return blankToDefault(networkCompartment, compartmentOcid); }

  public OciSettings getSettings() {
    return new OciSettings(
      authMode,
      tenancyOcid,
      "",
      "",
      region,
      compartmentOcid,
      "",
      configFilePath,
      profile,
      Instant.now().toString()
    );
  }

  public OciSettingsStatus getStatus() {
    OciSettings settings = getSettings();
    return new OciSettingsStatus(
      isConfigured(settings),
      settings.authMode().value(),
      settings.authMode().label(),
      settings.authMode() == OciAuthMode.CONFIG_FILE,
      !settings.configFilePath().isBlank(),
      !settings.profile().isBlank(),
      !settings.region().isBlank(),
      !settings.compartmentOcid().isBlank(),
      !settings.tenancyOcid().isBlank(),
      settings.authMode() == OciAuthMode.INSTANCE_PRINCIPAL
        ? "SERVER_ENV_AND_INSTANCE_PRINCIPAL"
        : "SERVER_ENV_AND_OCI_CONFIG",
      settings.updatedAt(), instanceId, settings.tenancyOcid(), settings.compartmentOcid(), settings.region(), billingEnabled, networkCompartment()
    );
  }

  public boolean isConfigured(OciSettings settings) {
    return settings.region() != null && !settings.region().isBlank()
      && settings.compartmentOcid() != null && !settings.compartmentOcid().isBlank()
      && isAuthConfigured(settings);
  }

  private boolean isAuthConfigured(OciSettings settings) {
    if (settings.authMode() == OciAuthMode.INSTANCE_PRINCIPAL) {
      return settings.tenancyOcid() != null && !settings.tenancyOcid().isBlank();
    }
    return settings.configFilePath() != null && !settings.configFilePath().isBlank()
      && settings.profile() != null && !settings.profile().isBlank();
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  private String blankToDefault(String value, String defaultValue) {
    String normalizedValue = normalize(value);
    return normalizedValue.isBlank() ? defaultValue : normalizedValue;
  }
}
