package ru.taska.security.tenant;

import io.grpc.ManagedChannel;

/** TAS-317 supplies the mTLS channel material; no plaintext fallback when context is activated. */
public interface TrustedRpcChannels {
  ManagedChannel create(String host, int port, String audience);
}
