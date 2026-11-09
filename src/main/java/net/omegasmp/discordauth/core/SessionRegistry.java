package net.omegasmp.discordauth.core;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** All pending auth sessions, indexed by player and by Discord code. */
public class SessionRegistry {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final Map<UUID, AuthSession> byPlayer = new ConcurrentHashMap<>();
  private final Map<String, AuthSession> byCode = new ConcurrentHashMap<>();
  private final Map<String, AuthSession> linkByCode = new ConcurrentHashMap<>();

  public void add(AuthSession session) {
    AuthSession previous = this.byPlayer.put(session.getUuid(), session);
    if (previous != null) {
      this.byCode.remove(previous.getCode(), previous);
    }
    if (session.needsDiscord() || session.isLinkOnly()) {
      (session.isLinkOnly() ? this.linkByCode : this.byCode).put(session.getCode(), session);
    }
  }

  /** A code that only links an already playing account ({@code /discordlink}). */
  public void addLink(AuthSession session) {
    this.linkByCode.put(session.getCode(), session);
  }

  public AuthSession byPlayer(UUID uuid) {
    return uuid == null ? null : this.byPlayer.get(uuid);
  }

  public AuthSession byCode(String code, boolean linkOnly) {
    if (code == null) {
      return null;
    }
    return linkOnly ? this.linkByCode.get(code) : this.byCode.get(code);
  }

  public void remove(AuthSession session) {
    if (session == null) {
      return;
    }
    this.byPlayer.remove(session.getUuid(), session);
    this.byCode.remove(session.getCode(), session);
    this.linkByCode.remove(session.getCode(), session);
  }

  public void removeCode(AuthSession session) {
    if (session != null) {
      this.byCode.remove(session.getCode(), session);
    }
  }

  public AuthSession removeLink(String code) {
    return code == null ? null : this.linkByCode.remove(code);
  }

  /** Drops expired {@code /discordlink} codes. */
  public void removeExpiredLinks() {
    this.linkByCode.entrySet().removeIf(entry -> entry.getValue().isExpired());
  }

  public List<AuthSession> all() {
    return new ArrayList<>(this.byPlayer.values());
  }

  public List<AuthSession> allLinks() {
    return new ArrayList<>(this.linkByCode.values());
  }

  /** Creates a fresh numeric code that is not used by any pending session. */
  public String generateCode(int digits) {
    int length = Math.max(4, Math.min(10, digits));
    String code;
    do {
      StringBuilder builder = new StringBuilder(length);
      for (int i = 0; i < length; i++) {
        builder.append(RANDOM.nextInt(10));
      }
      code = builder.toString();
    } while (this.byCode.containsKey(code) || this.linkByCode.containsKey(code));
    return code;
  }
}
