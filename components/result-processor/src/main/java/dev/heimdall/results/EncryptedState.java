package dev.heimdall.results;

import dev.heimdall.contracts.Json;
import dev.heimdall.providers.Providers.State;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** AES-GCM with a unique nonce and key-bound associated data. No plaintext reaches SQL. */
public final class EncryptedState implements State {
  private final JdbcTemplate jdbc;
  private final SecretKeySpec key;

  public EncryptedState(DataSource ds, String base64Key) {
    jdbc = new JdbcTemplate(ds);
    byte[] bytes = Base64.getDecoder().decode(base64Key);
    if (bytes.length != 32)
      throw new IllegalArgumentException("STATE_KEY must be a base64 256-bit key");
    key = new SecretKeySpec(bytes, "AES");
  }

  @Override
  public <T> T load(String name, Class<T> type) {
    var rows =
        jdbc.query("SELECT cipher FROM control.state WHERE key=?", (rs, n) -> rs.getBytes(1), name);
    if (rows.isEmpty()) return null;
    return Json.read(new String(decrypt(name, rows.getFirst()), StandardCharsets.UTF_8), type);
  }

  @Override
  public void save(String name, Object value) {
    jdbc.update(
        "INSERT INTO control.state(key,cipher) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET cipher=excluded.cipher,updated_at=now()",
        name,
        encrypt(name, Json.write(value).getBytes(StandardCharsets.UTF_8)));
  }

  @Override
  public boolean claim(String name) {
    return jdbc.update("INSERT INTO control.claims(key) VALUES (?) ON CONFLICT DO NOTHING", name)
        == 1;
  }

  private byte[] encrypt(String name, byte[] value) {
    byte[] nonce = new byte[12];
    new SecureRandom().nextBytes(nonce);
    try {
      var c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
      c.updateAAD(name.getBytes(StandardCharsets.UTF_8));
      byte[] encrypted = c.doFinal(value);
      return ByteBuffer.allocate(12 + encrypted.length).put(nonce).put(encrypted).array();
    } catch (Exception e) {
      throw new IllegalStateException("State encryption failed", e);
    }
  }

  private byte[] decrypt(String name, byte[] value) {
    try {
      var c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(value, 12)));
      c.updateAAD(name.getBytes(StandardCharsets.UTF_8));
      return c.doFinal(Arrays.copyOfRange(value, 12, value.length));
    } catch (Exception e) {
      throw new IllegalStateException("State authentication failed", e);
    }
  }
}
