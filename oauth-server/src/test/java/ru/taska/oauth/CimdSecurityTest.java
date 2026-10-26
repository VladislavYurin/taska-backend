package ru.taska.oauth;

import static org.assertj.core.api.Assertions.*;

import io.netty.resolver.AddressResolver;
import io.netty.util.concurrent.DefaultEventExecutor;
import java.net.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class CimdSecurityTest {
  @Test
  void rejectsPrivateLinkLocalMulticastMappedAndDocumentationAddresses() throws Exception {
    for (String ip :
        List.of(
            "0.0.0.0",
            "10.1.2.3",
            "127.0.0.1",
            "169.254.169.254",
            "172.16.4.3",
            "192.168.1.1",
            "100.64.1.1",
            "192.0.2.1",
            "198.18.1.1",
            "203.0.113.1",
            "224.0.0.1",
            "255.255.255.255",
            "::1",
            "fe80::1",
            "fc00::1",
            "ff02::1",
            "::ffff:127.0.0.1",
            "2001:db8::1",
            "2002:a00:1::1",
            "3fff::1"))
      assertThat(PublicAddressPolicy.allowed(InetAddress.getByName(ip))).as(ip).isFalse();
    assertThat(PublicAddressPolicy.allowed(InetAddress.getByName("8.8.8.8"))).isTrue();
    assertThat(PublicAddressPolicy.allowed(InetAddress.getByName("2606:4700:4700::1111"))).isTrue();
  }

  @Test
  void mixedAnswersAndRebindingAreRejectedAtEachActualResolution() throws Exception {
    var publicIp = new InetSocketAddress(InetAddress.getByName("8.8.8.8"), 443);
    var privateIp = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 443);
    var answers = new java.util.concurrent.atomic.AtomicReference<>(List.of(publicIp));
    var lookups = new java.util.concurrent.atomic.AtomicInteger();
    var dns =
        new io.netty.resolver.AddressResolverGroup<InetSocketAddress>() {
          @Override
          protected AddressResolver<InetSocketAddress> newResolver(
              io.netty.util.concurrent.EventExecutor executor) {
            return new io.netty.resolver.AbstractAddressResolver<InetSocketAddress>(
                executor, InetSocketAddress.class) {
              @Override
              public void close() {}

              @Override
              protected boolean doIsResolved(InetSocketAddress value) {
                return !value.isUnresolved();
              }

              @Override
              protected void doResolve(
                  InetSocketAddress value,
                  io.netty.util.concurrent.Promise<InetSocketAddress> promise) {
                lookups.incrementAndGet();
                promise.setSuccess(answers.get().getFirst());
              }

              @Override
              protected void doResolveAll(
                  InetSocketAddress value,
                  io.netty.util.concurrent.Promise<List<InetSocketAddress>> promise) {
                lookups.incrementAndGet();
                promise.setSuccess(answers.get());
              }
            };
          }
        };
    var executor = new DefaultEventExecutor();
    var group = new PublicAddressResolverGroup(dns);
    try {
      var resolver = group.getResolver(executor);
      var host = InetSocketAddress.createUnresolved("approved.example.test", 443);
      assertThat(resolver.resolve(host).awaitUninterruptibly().isSuccess()).isTrue();
      answers.set(List.of(publicIp, privateIp));
      assertThat(resolver.resolve(host).awaitUninterruptibly().isSuccess()).isFalse();
      answers.set(List.of(privateIp));
      assertThat(resolver.resolve(host).awaitUninterruptibly().isSuccess()).isFalse();
      assertThat(lookups).hasValue(3);
    } finally {
      group.close();
      executor.shutdownGracefully().syncUninterruptibly();
    }
  }

  @Test
  void actualSocketResolverRejectsNumericAndDnsLoopbackBeforeConnection() {
    var executor = new DefaultEventExecutor();
    var group = new PublicAddressResolverGroup();
    try {
      var resolver = group.getResolver(executor);
      var dns =
          resolver
              .resolve(InetSocketAddress.createUnresolved("localhost", 443))
              .awaitUninterruptibly();
      assertThat(dns.isSuccess()).isFalse();
      assertThatThrownBy(() -> resolver.isResolved(new InetSocketAddress("127.0.0.1", 443)))
          .isInstanceOf(IllegalArgumentException.class);
    } finally {
      group.close();
      executor.shutdownGracefully().syncUninterruptibly();
    }
  }
}
