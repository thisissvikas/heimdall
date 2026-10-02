package dev.heimdall.providers;

import dev.heimdall.providers.Providers.Artifacts;
import java.net.URI;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** S3-compatible encrypted object storage. Credentials use the SDK's workload identity chain. */
public final class S3Artifacts implements Artifacts, AutoCloseable {
  private final S3Client client;
  private final String bucket;
  private final SecretKeySpec key;

  public S3Artifacts(URI endpoint, String region, String bucket, String encryptionKey) {
    var builder = S3Client.builder().region(Region.of(region)).forcePathStyle(true);
    if (endpoint != null) builder.endpointOverride(endpoint);
    this.client = builder.build();
    this.bucket = bucket;
    byte[] bytes = Base64.getDecoder().decode(encryptionKey);
    if (bytes.length != 32) throw new IllegalArgumentException("Artifact key must be 256 bits");
    this.key = new SecretKeySpec(bytes, "AES");
  }

  @Override
  public void put(String name, byte[] bytes) {
    validate(name);
    if (bytes.length > 10485760) throw new IllegalArgumentException("Artifact size limit exceeded");
    byte[] nonce = new byte[12];
    new SecureRandom().nextBytes(nonce);
    try {
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
      cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
      byte[] encrypted = cipher.doFinal(bytes);
      byte[] body = ByteBuffer.allocate(12 + encrypted.length).put(nonce).put(encrypted).array();
      client.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(name)
              .contentType("application/octet-stream")
              .build(),
          RequestBody.fromBytes(body));
    } catch (Exception e) {
      throw new IllegalStateException("Artifact storage unavailable", e);
    }
  }

  @Override
  public byte[] get(String name) {
    validate(name);
    try {
      byte[] body =
          client
              .getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(name).build())
              .asByteArray();
      if (body.length > 10485800 || body.length < 28)
        throw new IllegalStateException("Invalid encrypted artifact");
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(body, 12)));
      cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(Arrays.copyOfRange(body, 12, body.length));
    } catch (Exception e) {
      throw new IllegalStateException("Artifact storage unavailable", e);
    }
  }

  private static void validate(String key) {
    if (key == null || !key.matches("[A-Za-z0-9_:/.-]{1,500}") || key.contains(".."))
      throw new IllegalArgumentException("Invalid object key");
  }

  @Override
  public void close() {
    client.close();
  }
}
