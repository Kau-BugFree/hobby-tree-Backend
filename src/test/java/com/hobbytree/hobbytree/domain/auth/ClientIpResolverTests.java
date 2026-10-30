package com.hobbytree.hobbytree.domain.auth;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.auth.security.ClientIpResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTests {
  private final ClientIpResolver resolver = new ClientIpResolver("10.0.1.0/24,10.0.2.0/24");

  private MockHttpServletRequest request(String peer, String header) {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr(peer);
    if (header != null) request.addHeader("X-Forwarded-For", header);
    return request;
  }

  @Test
  void noConfiguredProxyOrUntrustedPeerIgnoresAllForwardingHeaders() {
    var direct = request("203.0.113.7", "198.51.100.1, 198.51.100.2");
    direct.addHeader("X-Forwarded-For", "invalid");
    direct.addHeader("Forwarded", "for=198.51.100.3");
    direct.addHeader("X-Real-IP", "198.51.100.4");
    assertThat(resolver.resolve(direct)).isEqualTo("203.0.113.7");
    assertThat(new ClientIpResolver("").resolve(request("10.0.1.5", "198.51.100.9")))
        .isEqualTo("10.0.1.5");
    assertThat(resolver.resolve(request("10.0.3.5", "198.51.100.9"))).isEqualTo("10.0.3.5");
  }

  @Test
  void trustsEitherConfiguredSubnetButOnlyAlbsLastAppendedAddress() {
    for (String peer : new String[] {"10.0.1.5", "10.0.2.254"}) {
      var request = request(peer, "attacker-controlled, 198.51.100.10, 203.0.113.8");
      request.addHeader("Forwarded", "for=203.0.113.99");
      request.addHeader("X-Real-IP", "203.0.113.99");
      assertThat(resolver.resolve(request)).isEqualTo("203.0.113.8");
    }
  }

  @Test
  void privateClientInProxyRangeIsNotSkippedToAnAttackerPrefix() {
    assertThat(resolver.resolve(request("10.0.1.5", "198.51.100.99, 10.0.2.42")))
        .isEqualTo("10.0.2.42");
  }

  @Test
  void portsAndEquivalentIpv6RepresentationsDoNotCreateNewIdentities() {
    assertThat(resolver.resolve(request("10.0.1.5", "203.0.113.8:49152"))).isEqualTo("203.0.113.8");
    String ipv6 = resolver.resolve(request("10.0.1.5", "2001:db8::1"));
    assertThat(resolver.resolve(request("10.0.1.5", "[2001:0DB8:0:0:0:0:0:1]:65535")))
        .isEqualTo(ipv6);
    assertThat(resolver.resolve(request("10.0.1.5", "::ffff:203.0.113.8")))
        .isEqualTo("203.0.113.8");
  }

  @Test
  void supportsConfiguredIpv6ProxyWithoutTrustingOtherIpv6Peers() {
    var ipv6 = new ClientIpResolver("2001:db8:1::/64");
    assertThat(ipv6.resolve(request("2001:db8:1::5", "203.0.113.8"))).isEqualTo("203.0.113.8");
    assertThat(ipv6.resolve(request("2001:db8:2::5", "203.0.113.8")))
        .isEqualTo("2001:db8:2:0:0:0:0:5");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "unknown",
        "localhost",
        "127.1",
        "2130706433",
        "010.0.0.1",
        "256.1.1.1",
        "203.0.113.1,",
        "203.0.113.1:0",
        "203.0.113.1:65536",
        "203.0.113.1:abc",
        "[2001:db8::1]:-1",
        "[2001:db8::1]junk",
        "[localhost]:80",
        "fe80::1%eth0",
        "::ffff:010.0.0.1",
        "for=203.0.113.1"
      })
  void rejectsMalformedTrustedProxyAddressWithoutDnsOrFallback(String value) {
    assertThatThrownBy(() -> resolver.resolve(request("10.0.1.5", value)))
        .isInstanceOf(ClientIpResolver.InvalidForwardedHeader.class);
  }

  @Test
  void rejectsMissingDuplicateAndOversizedHeadersFromTrustedPeer() {
    assertThatThrownBy(() -> resolver.resolve(request("10.0.1.5", null)))
        .isInstanceOf(ClientIpResolver.InvalidForwardedHeader.class);
    var duplicate = request("10.0.1.5", "203.0.113.1");
    duplicate.addHeader("X-Forwarded-For", "203.0.113.2");
    assertThatThrownBy(() -> resolver.resolve(duplicate))
        .isInstanceOf(ClientIpResolver.InvalidForwardedHeader.class);
    assertThatThrownBy(() -> resolver.resolve(request("10.0.1.5", "x".repeat(8193))))
        .isInstanceOf(ClientIpResolver.InvalidForwardedHeader.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "0.0.0.0/0",
        "::/0",
        "localhost/32",
        "10.0.1.1/24",
        "10.0.1.0",
        "10.0.1.0/33",
        "10.0.1.0/24,",
        "fe80::%eth0/64",
        "::ffff:10.0.1.0/120"
      })
  void rejectsAmbiguousOrWildcardProxyConfiguration(String value) {
    assertThatThrownBy(() -> new ClientIpResolver(value))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
