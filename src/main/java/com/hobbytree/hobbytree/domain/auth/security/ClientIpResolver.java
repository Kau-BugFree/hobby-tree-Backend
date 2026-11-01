package com.hobbytree.hobbytree.domain.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves one ALB append hop behind an ALB-only security group. CIDRs alone do not identify ALB.
 */
@Component
public final class ClientIpResolver {
  private static final Logger LOG = LoggerFactory.getLogger("hobbytree.auth.proxy");
  private static final int MAX_HEADER_LENGTH = 8192;
  private final List<Network> trusted;

  public ClientIpResolver(@Value("${auth.proxy.trusted-cidrs:}") String configuredCidrs) {
    var networks = new ArrayList<Network>();
    if (!configuredCidrs.isBlank()) {
      String[] entries = configuredCidrs.split(",", -1);
      if (entries.length > 32) throw new IllegalArgumentException("Too many trusted proxy CIDRs");
      for (String entry : entries) networks.add(network(entry.trim()));
    }
    trusted = List.copyOf(networks);
  }

  public String resolve(HttpServletRequest request) {
    InetAddress peer = literal(request.getRemoteAddr());
    boolean trustedPeer = trusted.stream().anyMatch(network -> network.contains(peer.getAddress()));
    // Opt-in deployment check for Docker/NAT source preservation; never log forwarded headers.
    LOG.debug("Login socket peer={}, trustedProxy={}", peer.getHostAddress(), trustedPeer);
    if (!trustedPeer) {
      return peer.getHostAddress();
    }
    var headers = request.getHeaders("X-Forwarded-For");
    if (headers == null || !headers.hasMoreElements()) throw new InvalidForwardedHeader();
    String value = headers.nextElement();
    if (headers.hasMoreElements() || value.length() > MAX_HEADER_LENGTH) {
      throw new InvalidForwardedHeader();
    }
    // ALB append mode writes the actual caller last. Never walk left past that caller,
    // even when its private address happens to belong to a trusted proxy subnet.
    String last = value.substring(value.lastIndexOf(',') + 1).trim();
    return forwardedLiteral(last).getHostAddress();
  }

  private static InetAddress forwardedLiteral(String value) {
    if (value.startsWith("[")) {
      int end = value.indexOf(']');
      if (end < 0 || !value.substring(1, end).contains(":")) throw new InvalidForwardedHeader();
      String tail = value.substring(end + 1);
      if (!tail.isEmpty()) {
        if (!tail.startsWith(":")) throw new InvalidForwardedHeader();
        port(tail.substring(1));
      }
      return literal(value.substring(1, end));
    }
    int colon = value.indexOf(':');
    if (colon >= 0 && colon == value.lastIndexOf(':') && value.contains(".")) {
      port(value.substring(colon + 1));
      return literal(value.substring(0, colon));
    }
    return literal(value);
  }

  private static void port(String value) {
    if (!value.matches("[0-9]{1,5}")) throw new InvalidForwardedHeader();
    int port = Integer.parseInt(value);
    if (port < 1 || port > 65535) throw new InvalidForwardedHeader();
  }

  private static InetAddress literal(String value) {
    if (value == null || value.isEmpty() || value.length() > 45) throw new InvalidForwardedHeader();
    try {
      if (!value.contains(":")) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) throw new InvalidForwardedHeader();
        byte[] bytes = new byte[4];
        for (int i = 0; i < parts.length; i++) {
          if (!parts[i].matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(parts[i]) > 255) {
            throw new InvalidForwardedHeader();
          }
          bytes[i] = (byte) Integer.parseInt(parts[i]);
        }
        return InetAddress.getByAddress(bytes);
      }
      // A colon plus this numeric-only alphabet prevents DNS resolution. No zones,
      // hostnames, brackets, ports or legacy compact IPv4 representations are accepted.
      if (!value.matches("[0-9a-fA-F:.]+")) throw new InvalidForwardedHeader();
      if (value.contains(".")) literal(value.substring(value.lastIndexOf(':') + 1));
      return InetAddress.getByName(value);
    } catch (UnknownHostException e) {
      throw new InvalidForwardedHeader();
    }
  }

  private static Network network(String value) {
    try {
      String[] parts = value.split("/", -1);
      if (parts.length != 2 || !parts[1].matches("[0-9]{1,3}")) throw new InvalidForwardedHeader();
      byte[] address = literal(parts[0]).getAddress();
      if (parts[0].contains(":") && address.length != 16) throw new InvalidForwardedHeader();
      int prefix = Integer.parseInt(parts[1]);
      if (prefix <= 0 || prefix > address.length * 8) throw new InvalidForwardedHeader();
      for (int bit = prefix; bit < address.length * 8; bit++) {
        if ((address[bit / 8] & (1 << (7 - bit % 8))) != 0) throw new InvalidForwardedHeader();
      }
      return new Network(address, prefix);
    } catch (InvalidForwardedHeader e) {
      throw new IllegalArgumentException(
          "Trusted proxies must be explicit numeric network CIDRs", e);
    }
  }

  private record Network(byte[] address, int prefix) {
    boolean contains(byte[] candidate) {
      if (candidate.length != address.length) return false;
      for (int bit = 0; bit < prefix; bit++) {
        int mask = 1 << (7 - bit % 8);
        if ((candidate[bit / 8] & mask) != (address[bit / 8] & mask)) return false;
      }
      return true;
    }
  }

  public static final class InvalidForwardedHeader extends IllegalArgumentException {
    public InvalidForwardedHeader() {
      super("A single valid ALB client address is required");
    }
  }
}
