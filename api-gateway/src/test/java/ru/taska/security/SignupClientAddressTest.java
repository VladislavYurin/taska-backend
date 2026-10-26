package ru.taska.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import java.net.InetSocketAddress;
import static org.assertj.core.api.Assertions.*;

class SignupClientAddressTest {
    private static MockServerWebExchange request(String peer,String forwarded) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/signup")
                .remoteAddress(new InetSocketAddress(peer,12345)).header("X-Forwarded-For",forwarded));
    }
    @Test void untrustedPeerCannotChooseRateLimitBucket() {
        assertThat(new SignupClientAddress("").resolve(request("198.51.100.2","203.0.113.1"))).isEqualTo("198.51.100.2");
    }
    @Test void walksTrustedChainFromRightAndStopsAtFirstUntrustedHop() {
        var resolver=new SignupClientAddress("10.0.0.0/8,2001:db8::/32");
        assertThat(resolver.resolve(request("10.0.0.2","192.0.2.99, 203.0.113.7, 10.0.0.3"))).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve(request("10.0.0.2","203.0.113.7"))).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve(request("10.0.0.2","abc"))).isEqualTo("10.0.0.2");
        assertThat(resolver.resolve(request("10.0.0.2","1"))).isEqualTo("10.0.0.2");
    }
    @Test void invalidTrustedNetworksFailConfiguration() {
        for(String value:new String[]{"hostname","10.0.0.0/33","2001:db8::/129","10.0.0.1/8/1"})
            assertThatThrownBy(()->new SignupClientAddress(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
