package dev.heimdall.providers;

import dev.heimdall.contracts.Json;
import dev.heimdall.providers.Providers.Secrets;
import java.net.*;
import java.net.http.*;
import java.time.Duration;

public final class VaultSecrets implements Secrets {
  private final String address, token;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public VaultSecrets() {
    this(System.getenv("VAULT_ADDRESS"), System.getenv("VAULT_TOKEN"));
  }

  public VaultSecrets(String address, String token) {
    if (address == null || token == null)
      throw new IllegalArgumentException("VAULT_ADDRESS and VAULT_TOKEN required");
    this.address = address.replaceAll("/$", "");
    this.token = token;
  }

  @Override
  public String resolve(String reference) {
    if (!reference.matches("[a-zA-Z0-9_/-]+#[a-zA-Z0-9_-]+") || reference.contains(".."))
      throw new IllegalArgumentException("Secret reference must be mount/path#field");
    var p = reference.split("#", 2);
    try {
      var request =
          HttpRequest.newBuilder(URI.create("%s/v1/%s".formatted(address, p[0])))
              .timeout(Duration.ofSeconds(5))
              .header("X-Vault-Token", token)
              .GET()
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200 || response.body().length() > 65536)
        throw new IllegalStateException("Secret provider unavailable");
      var data = Json.MAPPER.readTree(response.body()).path("data");
      if (data.has("data")) data = data.path("data");
      var value = data.get(p[1]);
      if (value == null || !value.isTextual())
        throw new IllegalStateException("Secret reference unavailable");
      return value.asText();
    } catch (Exception e) {
      throw new IllegalStateException("Secret provider unavailable");
    }
  }
}
