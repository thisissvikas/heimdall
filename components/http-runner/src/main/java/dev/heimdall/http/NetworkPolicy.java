package dev.heimdall.http;

import dev.heimdall.contracts.Definitions.Location;
import java.net.*;
import java.util.*;
import okhttp3.Dns;

public final class NetworkPolicy implements Dns {
  private final Location location;

  public NetworkPolicy(Location location) {
    this.location = location;
  }

  @Override
  public List<InetAddress> lookup(String hostname) throws UnknownHostException {
    if (location.network().equals("private") && !location.allowedHosts().contains(hostname))
      throw new UnknownHostException("Host not approved for private location");
    List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
    for (var ip : addresses) validate(ip, location.network().equals("private"));
    return addresses; // OkHttp connects to these exact validated addresses, avoiding a second
    // lookup.
  }

  public static void validate(InetAddress ip, boolean privateLocation) throws UnknownHostException {
    byte[] b = ip.getAddress();
    boolean metadata =
        ip.isLinkLocalAddress()
            || (b.length == 4
                && (b[0] & 255) == 100
                && (b[1] & 255) == 100
                && (b[2] & 255) == 100
                && (b[3] & 255) == 200);
    boolean reserved = ip.isAnyLocalAddress() || ip.isMulticastAddress() || metadata;
    boolean internal =
        ip.isLoopbackAddress()
            || ip.isSiteLocalAddress()
            || b.length == 16 && ((b[0] & 0xfe) == 0xfc)
            || b.length == 4
                && ((b[0] & 255) == 0
                    || (b[0] & 255) == 100 && ((b[1] & 255) >= 64 && (b[1] & 255) <= 127)
                    || (b[0] & 255) >= 224);
    if (reserved || !privateLocation && internal)
      throw new UnknownHostException("Destination prohibited by location policy");
  }
}
