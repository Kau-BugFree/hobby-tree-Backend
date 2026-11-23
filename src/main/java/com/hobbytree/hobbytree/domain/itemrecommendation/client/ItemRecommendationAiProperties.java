package com.hobbytree.hobbytree.domain.itemrecommendation.client;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("item-recommendation.ai")
public record ItemRecommendationAiProperties(
    @DefaultValue("false") boolean enabled,
    URI baseUrl,
    @DefaultValue("") String serviceToken,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("10s") Duration requestTimeout) {
  /**
   * Fails startup for enabled clients with an unsafe origin, missing token, or unbounded timeout.
   * The catalog lookup has no outbound Adpick call on the request path, so the request timeout is
   * bounded tighter than Coach's generative call.
   */
  public ItemRecommendationAiProperties {
    if (connectTimeout == null
        || connectTimeout.isNegative()
        || connectTimeout.isZero()
        || requestTimeout == null
        || requestTimeout.isNegative()
        || requestTimeout.isZero()
        || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
      throw new IllegalArgumentException(
          "Item recommendation timeouts must be positive; request timeout <= 30s");
    }
    if (enabled
        && (baseUrl == null
            || baseUrl.getHost() == null
            || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
            // Plain HTTP would send X-Service-Token unencrypted; only allow it to a host we can
            // identify as private/internal. Any other host (public IP or an unrecognized
            // hostname) must use HTTPS.
            || ("http".equals(baseUrl.getScheme()) && !isPrivateNetworkHost(baseUrl.getHost()))
            || baseUrl.getUserInfo() != null
            || baseUrl.getQuery() != null
            || baseUrl.getFragment() != null
            || !(baseUrl.getPath().isEmpty() || "/".equals(baseUrl.getPath()))
            || serviceToken == null
            || serviceToken.isBlank()
            || serviceToken.chars().anyMatch(c -> c < 33 || c > 126))) {
      throw new IllegalArgumentException(
          "Enabled item recommendation client requires a private HTTP origin or HTTPS, and a service token");
    }
  }

  private static final Pattern IPV4 =
      Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

  /**
   * Static, DNS-free check: a literal RFC1918/loopback IPv4 address, or a hostname under a reserved
   * private-use suffix (localhost, .internal, .local - RFC 6761/8375 style names used for
   * EC2-internal AI hosts). Never resolves the hostname.
   */
  private static boolean isPrivateNetworkHost(String host) {
    String lower = host.toLowerCase(Locale.ROOT);
    if (lower.equals("localhost") || lower.endsWith(".internal") || lower.endsWith(".local"))
      return true;
    var matcher = IPV4.matcher(host);
    if (!matcher.matches()) return false;
    int[] octet = new int[4];
    for (int i = 0; i < 4; i++) {
      int value = Integer.parseInt(matcher.group(i + 1));
      if (value > 255) return false;
      octet[i] = value;
    }
    return octet[0] == 127
        || octet[0] == 10
        || (octet[0] == 172 && octet[1] >= 16 && octet[1] <= 31)
        || (octet[0] == 192 && octet[1] == 168);
  }

  @Override
  /** Keeps the server credential out of diagnostics that stringify configuration objects. */
  public String toString() {
    return "ItemRecommendationAiProperties[redacted]";
  }
}
