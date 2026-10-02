package dev.heimdall.providers;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Tag("integration")
class AdaptersIntegrationTest {
  HttpServer server;
  Map<String, byte[]> objects;
  String endpoint;

  @BeforeEach
  void setup() throws Exception {
    objects = new ConcurrentHashMap<>();
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          if (path.startsWith("/v1/secret/")) {
            byte[] body =
                "{\"data\":{\"data\":{\"key\":\"vault-value\"}}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          } else if (exchange.getRequestMethod().equals("PUT")) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            // S3 accepts an additional aws-chunked encoding for checksummed streaming uploads.
            if (exchange.getRequestHeaders().getFirst("x-amz-decoded-content-length") != null) {
              var decoded = new java.io.ByteArrayOutputStream();
              int offset = 0;
              while (offset < body.length) {
                int end = offset;
                while (end + 1 < body.length && !(body[end] == '\r' && body[end + 1] == '\n'))
                  end++;
                int length =
                    Integer.parseInt(
                        new String(body, offset, end - offset, StandardCharsets.US_ASCII)
                            .split(";")[0],
                        16);
                if (length == 0) break;
                offset = end + 2;
                decoded.write(body, offset, length);
                offset += length + 2;
              }
              body = decoded.toByteArray();
            }
            objects.put(path, body);
            exchange.getResponseHeaders().set("ETag", "\"fixture\"");
            exchange.sendResponseHeaders(200, -1);
          } else {
            byte[] body = objects.get(path);
            if (body == null) exchange.sendResponseHeaders(404, -1);
            else {
              exchange.sendResponseHeaders(200, body.length);
              exchange.getResponseBody().write(body);
            }
          }
          exchange.close();
        });
    server.start();
    endpoint = "http://localhost:%d".formatted(server.getAddress().getPort());
    System.setProperty("aws.accessKeyId", "fixture-access");
    System.setProperty("aws.secretAccessKey", "fixture-secret");
  }

  @AfterEach
  void close() {
    server.stop(0);
    System.clearProperty("aws.accessKeyId");
    System.clearProperty("aws.secretAccessKey");
  }

  @Test
  void vaultResolvesKvV2FieldAndRejectsUnsafePaths() {
    var vault = new VaultSecrets(endpoint, "fixture-token");
    assertEquals("vault-value", vault.resolve("secret/data/payments/orders#key"));
    assertThrows(IllegalArgumentException.class, () -> vault.resolve("../metadata#key"));
    assertThrows(
        IllegalStateException.class, () -> vault.resolve("secret/data/payments/orders#missing"));
  }

  @Test
  void s3StoresAuthenticatedCiphertextAndRoundTripsArtifacts() {
    try (var storage =
        new S3Artifacts(
            URI.create(endpoint),
            "us-east-1",
            "heimdall",
            Base64.getEncoder().encodeToString(new byte[32]))) {
      byte[] original = "redacted-failure-diagnostics".getBytes(StandardCharsets.UTF_8);
      storage.put("failure/run-1/id", original);
      assertArrayEquals(original, storage.get("failure/run-1/id"));
      byte[] ciphertext = objects.values().iterator().next();
      assertFalse(new String(ciphertext, StandardCharsets.UTF_8).contains("redacted-failure"));
      ciphertext[ciphertext.length - 1] ^= 1;
      assertThrows(IllegalStateException.class, () -> storage.get("failure/run-1/id"));
      assertThrows(IllegalArgumentException.class, () -> storage.get("../invalid"));
    }
  }
}
