package ru.taska.oauth;

import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import java.net.InetSocketAddress;
import java.util.List;

/** Validates the actual socket resolution, avoiding a separate DNS precheck/connect race. */
final class PublicAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {
  private final AddressResolverGroup<InetSocketAddress> delegateGroup;

  PublicAddressResolverGroup() {
    this(DefaultAddressResolverGroup.INSTANCE);
  }

  PublicAddressResolverGroup(AddressResolverGroup<InetSocketAddress> delegateGroup) {
    this.delegateGroup = delegateGroup;
  }

  @Override
  public void close() {
    super.close();
    if (delegateGroup != DefaultAddressResolverGroup.INSTANCE) delegateGroup.close();
  }

  @Override
  protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
    var delegate = delegateGroup.getResolver(executor);
    return new AbstractAddressResolver<InetSocketAddress>(executor, InetSocketAddress.class) {
      @Override
      protected boolean doIsResolved(InetSocketAddress address) {
        if (address.isUnresolved()) return false;
        requirePublic(address);
        return true;
      }

      @Override
      protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> promise) {
        delegate
            .resolveAll(address)
            .addListener(
                future -> {
                  if (!future.isSuccess()) {
                    promise.tryFailure(future.cause());
                    return;
                  }
                  try {
                    // Use the already completed result: never perform a second DNS resolution.
                    @SuppressWarnings("unchecked")
                    var resolved = (List<InetSocketAddress>) future.getNow();
                    resolved.forEach(PublicAddressResolverGroup::requirePublic);
                    if (resolved.isEmpty())
                      throw new IllegalArgumentException("No public CIMD address");
                    promise.trySuccess(resolved.getFirst());
                  } catch (RuntimeException error) {
                    promise.tryFailure(error);
                  }
                });
      }

      @Override
      protected void doResolveAll(
          InetSocketAddress address, Promise<List<InetSocketAddress>> promise) {
        delegate
            .resolveAll(address)
            .addListener(
                future -> {
                  if (!future.isSuccess()) {
                    promise.tryFailure(future.cause());
                    return;
                  }
                  try {
                    @SuppressWarnings("unchecked")
                    var resolved = (List<InetSocketAddress>) future.getNow();
                    resolved.forEach(PublicAddressResolverGroup::requirePublic);
                    if (resolved.isEmpty())
                      throw new IllegalArgumentException("No public CIMD address");
                    promise.trySuccess(resolved);
                  } catch (RuntimeException error) {
                    promise.tryFailure(error);
                  }
                });
      }

      @Override
      public void close() {
        /* shared delegate belongs to Netty */
      }
    };
  }

  private static void requirePublic(InetSocketAddress address) {
    if (address.isUnresolved() || !PublicAddressPolicy.allowed(address.getAddress()))
      throw new IllegalArgumentException("CIMD requires a public unicast address");
  }
}
