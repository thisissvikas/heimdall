package dev.heimdall.providers;

import dev.heimdall.contracts.*;
import dev.heimdall.contracts.Definitions.*;
import dev.heimdall.providers.Providers.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

public final class OpaAuthorization implements Authorization {
  private final URI endpoint;
  private final Authorization baseline = new Rbac();
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public OpaAuthorization(URI endpoint) {
    this.endpoint = endpoint;
  }

  @Override
  public void require(
      Identity id,
      String action,
      String scope,
      String environment,
      String location,
      Snapshot snapshot) {
    baseline.require(id, action, scope, environment, location, snapshot);
    try {
      var input = new LinkedHashMap<String, Object>();
      input.put("subject", id.subject());
      input.put("action", action);
      input.put("scope", scope);
      input.put("environment", environment);
      input.put("location", location);
      input.put("configCommit", snapshot.commit());
      var response =
          client.send(
              HttpRequest.newBuilder(endpoint)
                  .timeout(Duration.ofSeconds(2))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("input", input))))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200
          || !Json.MAPPER.readTree(response.body()).path("result").asBoolean(false))
        throw new SecurityException("External authorization denied");
    } catch (Exception e) {
      throw new SecurityException("External authorization denied");
    }
  }
}
