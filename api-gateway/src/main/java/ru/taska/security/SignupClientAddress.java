package ru.taska.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

/** Forwarded headers are accepted only through explicitly trusted proxy networks. */
@Component
public class SignupClientAddress {
    private final List<Network> trusted;
    private record Network(byte[] address, int bits) {
        boolean contains(byte[] other) {
            if (other.length != address.length) return false;
            for (int bit=0; bit<bits; bit++) {
                int mask=1<<(7-bit%8);
                if ((address[bit/8]&mask)!=(other[bit/8]&mask)) return false;
            }
            return true;
        }
    }
    public SignupClientAddress(@Value("${signup.trusted-proxy-cidrs:}") String networks) {
        trusted = Arrays.stream(networks.split(",")).map(String::strip).filter(s -> !s.isEmpty()).map(value -> {
            String[] parts=value.split("/", -1);
            byte[] bytes=literal(parts[0]).getAddress();
            int bits=parts.length==1 ? bytes.length*8 : Integer.parseInt(parts[1]);
            if (parts.length>2 || bits<0 || bits>bytes.length*8) throw new IllegalArgumentException("Invalid trusted proxy CIDR");
            return new Network(bytes, bits);
        }).toList();
    }
    public String resolve(ServerWebExchange exchange) {
        var remote=exchange.getRequest().getRemoteAddress();
        if (remote==null || remote.getAddress()==null) return "unknown";
        InetAddress current=remote.getAddress();
        String forwarded=exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded!=null && forwarded.length()<=2048) {
            String[] hops=forwarded.split(",");
            if (hops.length<=20) {
                for (int i=hops.length-1; i>=0 && contains(current); i--) {
                    try { current=literal(hops[i].strip()); }
                    catch (IllegalArgumentException malformed) { return remote.getAddress().getHostAddress(); }
                }
            }
        }
        return current.getHostAddress();
    }
    private boolean contains(InetAddress address) { return trusted.stream().anyMatch(n -> n.contains(address.getAddress())); }
    private static InetAddress literal(String value) {
        if (value.isEmpty() || !value.matches("[0-9a-fA-F:.]+") || (!value.contains(":") && !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) throw new IllegalArgumentException("IP literal required");
        try { return InetAddress.getByName(value); }
        catch (java.net.UnknownHostException invalid) { throw new IllegalArgumentException("IP literal required"); }
    }
}
