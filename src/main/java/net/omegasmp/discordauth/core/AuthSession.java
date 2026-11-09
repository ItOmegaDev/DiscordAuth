package net.omegasmp.discordauth.core;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One pending authorization. A session can require two independent steps:
 * <ul>
 *   <li><b>Discord link</b> — confirmed with {@code /verify <code>} in Discord;</li>
 *   <li><b>Password</b> — {@code /register} (first time) or {@code /login}.</li>
 * </ul>
 * The player is released only when every required step is done.
 *
 * <p>In the Velocity login limbo the player is not yet registered in
 * {@code ProxyServer#getPlayer(UUID)}, so the platform keeps its own player handle in
 * {@link #getHandle()}. On Paper the handle is the Bukkit {@code Player}.</p>
 */
public class AuthSession {

  public enum Step {
    DISCORD,
    PASSWORD_LOGIN,
    PASSWORD_REGISTER,
    DONE
  }

  private final UUID uuid;
  private final String username;
  private final String code;
  private final boolean discordRequired;
  private final boolean passwordRequired;
  private final boolean linkOnly;
  private final boolean bedrock;
  private final String address;
  private final long createdAt;
  private volatile long expiresAt;
  private final AtomicBoolean discordDone;
  private final AtomicBoolean passwordDone;
  private final AtomicBoolean released = new AtomicBoolean(false);
  private volatile Object handle;
  private int failedPasswordAttempts;
  private long passwordBlockedUntil;

  public AuthSession(UUID uuid, String username, String code, long expiresAt,
      boolean needsDiscord, boolean needsPassword, boolean linkOnly, boolean bedrock, String address) {
    this.uuid = uuid;
    this.username = username;
    this.code = code;
    this.expiresAt = expiresAt;
    this.discordRequired = needsDiscord;
    this.passwordRequired = needsPassword;
    this.linkOnly = linkOnly;
    this.bedrock = bedrock;
    this.address = address == null ? "" : address;
    this.createdAt = System.currentTimeMillis();
    this.discordDone = new AtomicBoolean(!needsDiscord);
    this.passwordDone = new AtomicBoolean(!needsPassword || linkOnly);
  }

  public UUID getUuid() {
    return this.uuid;
  }

  public String getUsername() {
    return this.username;
  }

  public String getCode() {
    return this.code;
  }

  public long getCreatedAt() {
    return this.createdAt;
  }

  public long getExpiresAt() {
    return this.expiresAt;
  }

  public boolean isExpired() {
    return System.currentTimeMillis() > this.expiresAt;
  }

  public void extendTo(long newExpiresAt) {
    if (newExpiresAt > this.expiresAt) {
      this.expiresAt = newExpiresAt;
    }
  }

  public boolean needsDiscord() {
    return !this.discordDone.get();
  }

  public boolean needsPassword() {
    return !this.passwordDone.get();
  }

  public boolean isDiscordRequired() {
    return this.discordRequired;
  }

  public boolean isPasswordRequired() {
    return this.passwordRequired;
  }

  public boolean isLinkOnly() {
    return this.linkOnly;
  }

  public boolean isBedrock() {
    return this.bedrock;
  }

  public String getAddress() {
    return this.address;
  }

  public boolean markDiscordDone() {
    return this.discordDone.compareAndSet(false, true);
  }

  public boolean markPasswordDone() {
    return this.passwordDone.compareAndSet(false, true);
  }

  public boolean isComplete() {
    return this.discordDone.get() && this.passwordDone.get();
  }

  public boolean markReleased() {
    return this.released.compareAndSet(false, true);
  }

  public int getFailedPasswordAttempts() {
    return this.failedPasswordAttempts;
  }

  public void registerPasswordFailure(int maxAttempts, long cooldownMillis) {
    this.failedPasswordAttempts++;
    if (this.failedPasswordAttempts >= maxAttempts) {
      this.failedPasswordAttempts = 0;
      this.passwordBlockedUntil = System.currentTimeMillis() + cooldownMillis;
    }
  }

  public long blockedSeconds() {
    long remaining = this.passwordBlockedUntil - System.currentTimeMillis();
    return remaining > 0 ? remaining / 1000L + 1L : 0L;
  }

  public Object getHandle() {
    return this.handle;
  }

  public void setHandle(Object handle) {
    this.handle = handle;
  }

  /** Which dialog/prompt the platform should show right now. */
  public Step step(boolean hasPassword) {
    if (isComplete()) {
      return Step.DONE;
    }
    if (needsDiscord()) {
      return Step.DISCORD;
    }
    if (needsPassword()) {
      return hasPassword ? Step.PASSWORD_LOGIN : Step.PASSWORD_REGISTER;
    }
    return Step.DONE;
  }
}
