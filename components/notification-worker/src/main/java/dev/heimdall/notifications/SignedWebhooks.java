package dev.heimdall.notifications;

import dev.heimdall.providers.Providers.Notifications;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;

public final class SignedWebhooks implements Notifications {
  private final Map<String, String> destinations;
  private final byte[] signingKey;
  private final boolean local;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public SignedWebhooks(Map<String, String> destinations, byte[] signingKey, boolean local) {
    if (signingKey.length < 32)
      throw new IllegalArgumentException("Notification signing key must be at least 32 bytes");
    this.destinations = Map.copyOf(destinations);
    this.signingKey = signingKey.clone();
    this.local = local;
  }

  @Override
  public void deliver(String deliveryId, String destination, byte[] body) {
    try {
      String address = destinations.get(destination);
      if (address == null)
        throw new IllegalArgumentException("Notification destination not registered");
      URI uri = URI.create(address);
      if (!uri.getScheme().equals("https") && !(local && uri.getHost().equals("localhost")))
        throw new IllegalArgumentException("Webhook requires HTTPS");
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
      var response =
          client.send(
              HttpRequest.newBuilder(uri)
                  .timeout(Duration.ofSeconds(5))
                  .header("Content-Type", "application/json")
                  .header("X-Heimdall-Delivery", deliveryId)
                  .header(
                      "X-Heimdall-Signature",
                      "sha256=%s".formatted(HexFormat.of().formatHex(mac.doFinal(body))))
                  .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() < 200 || response.statusCode() >= 300)
        throw new IllegalStateException("Webhook delivery rejected");
    } catch (Exception e) {
      throw new IllegalStateException("Webhook delivery failed");
    }
  }
}
