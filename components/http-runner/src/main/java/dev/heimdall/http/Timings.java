package dev.heimdall.http;

import java.net.*;
import java.time.*;
import java.util.*;
import okhttp3.*;

public final class Timings extends okhttp3.EventListener {
  private final Map<String, Long> starts = new HashMap<>(), elapsed = new LinkedHashMap<>();
  private final long start = System.nanoTime();

  private void begin(String phase) {
    starts.put(phase, System.nanoTime());
  }

  private void end(String phase) {
    var s = starts.remove(phase);
    if (s != null) elapsed.merge(phase, (System.nanoTime() - s) / 1_000_000, Long::sum);
  }

  @Override
  public void dnsStart(Call c, String d) {
    begin("dns");
  }

  @Override
  public void dnsEnd(Call c, String d, List<InetAddress> a) {
    end("dns");
  }

  @Override
  public void connectStart(Call c, InetSocketAddress a, Proxy p) {
    begin("connect");
  }

  @Override
  public void connectEnd(Call c, InetSocketAddress a, Proxy p, Protocol protocol) {
    end("connect");
  }

  @Override
  public void secureConnectStart(Call c) {
    begin("tls");
  }

  @Override
  public void secureConnectEnd(Call c, Handshake handshake) {
    end("tls");
    if (handshake != null
        && !handshake.peerCertificates().isEmpty()
        && handshake.peerCertificates().getFirst()
            instanceof java.security.cert.X509Certificate cert)
      elapsed.put(
          "certificateExpiryDays",
          Duration.between(Instant.now(), cert.getNotAfter().toInstant()).toDays());
  }

  @Override
  public void responseHeadersStart(Call c) {
    elapsed.put("firstByte", (System.nanoTime() - start) / 1_000_000);
    begin("transfer");
  }

  @Override
  public void responseBodyEnd(Call c, long bytes) {
    end("transfer");
  }

  public Map<String, Long> result() {
    var out = new LinkedHashMap<>(elapsed);
    out.put("total", (System.nanoTime() - start) / 1_000_000);
    return out;
  }
}
