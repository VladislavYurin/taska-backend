package ru.taska.mcp;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Atomic admission across both identities; active or partially refilled buckets are never evicted.
 */
@Component
public class McpQuotaLimiter {
  private static final double MINUTE = 60_000_000_000d;
  private final McpQuotaProperties limits;
  private final LongSupplier clock;
  private final Map<String, State> tokens = new HashMap<>();
  private final Map<String, State> users = new HashMap<>();

  @Autowired
  public McpQuotaLimiter(McpQuotaProperties limits) {
    this(limits, System::nanoTime);
  }

  McpQuotaLimiter(McpQuotaProperties limits, LongSupplier clock) {
    this.limits = limits;
    this.clock = clock;
  }

  public synchronized Lease acquire(String userId, String tokenId, boolean write) {
    long now = clock.getAsLong();
    if (!tokens.containsKey(tokenId)) evict(tokens, now);
    if (!users.containsKey(userId)) evict(users, now);
    // Check both capacities before inserting either identity: a rejected caller cannot fill the
    // other map.
    if ((!tokens.containsKey(tokenId) && tokens.size() >= limits.maxTokens())
        || (!users.containsKey(userId) && users.size() >= limits.maxUsers())) {
      throw rejected("TRACKED_IDENTITY_LIMIT", 1);
    }
    State token = tokens.get(tokenId);
    if (token == null) token = new State(limits.tokenRead(), limits.tokenWrite(), now);
    State user = users.get(userId);
    if (user == null) user = new State(limits.userRead(), limits.userWrite(), now);
    token.refill(now, limits.tokenRead(), limits.tokenWrite());
    user.refill(now, limits.userRead(), limits.userWrite());
    if (token.active >= limits.tokenConcurrent()) throw rejected("TOKEN_CONCURRENCY_LIMIT", 1);
    if (user.active >= limits.userConcurrent()) throw rejected("USER_CONCURRENCY_LIMIT", 1);
    double tokenAvailable = write ? token.write : token.read;
    double userAvailable = write ? user.write : user.read;
    if (tokenAvailable < 1 || userAvailable < 1) {
      double wait =
          Math.max(
              (1 - tokenAvailable) * 60 / (write ? limits.tokenWrite() : limits.tokenRead()),
              (1 - userAvailable) * 60 / (write ? limits.userWrite() : limits.userRead()));
      throw rejected(
          tokenAvailable < 1 ? "TOKEN_RATE_LIMIT" : "USER_RATE_LIMIT",
          Math.max(1, (long) Math.ceil(wait)));
    }
    if (write) {
      token.write--;
      user.write--;
    } else {
      token.read--;
      user.read--;
    }
    tokens.putIfAbsent(tokenId, token);
    users.putIfAbsent(userId, user);
    token.active++;
    user.active++;
    return new Lease(token, user);
  }

  private void evict(Map<String, State> states, long now) {
    states
        .values()
        .removeIf(state -> state.active == 0 && now - state.touched >= limits.idleTtl().toNanos());
  }

  private static GatewayToolException rejected(String reason, long seconds) {
    return new GatewayToolException(
        "rate_limited",
        "Превышен лимит MCP. Повторите вызов позже.",
        Map.of("reason", reason, "retry_after_seconds", seconds));
  }

  synchronized int tokenCount() {
    return tokens.size();
  }

  synchronized int userCount() {
    return users.size();
  }

  public final class Lease implements AutoCloseable {
    private final State token;
    private final State user;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Lease(State token, State user) {
      this.token = token;
      this.user = user;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true))
        synchronized (McpQuotaLimiter.this) {
          token.active--;
          user.active--;
        }
    }
  }

  private static final class State {
    private double read, write;
    private long touched;
    private int active;

    private State(int read, int write, long now) {
      this.read = read;
      this.write = write;
      touched = now;
    }

    private void refill(long now, int readLimit, int writeLimit) {
      double elapsed = Math.max(0, now - touched) / MINUTE;
      read = Math.min(readLimit, read + elapsed * readLimit);
      write = Math.min(writeLimit, write + elapsed * writeLimit);
      touched = now;
    }
  }
}
