package net.omegasmp.discordauth.core;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;

/** Persistent bans enforced before the auth UI, backed by bans.json. */
public class BanStore {

  public static class Ban {

    public String name;
    public String uuid;
    public String reason;
    public String bannedBy;
    public long createdAt;
    /** Epoch millis when the ban expires; {@code 0} means permanent. */
    public long expiresAt;

    public boolean permanent() {
      return this.expiresAt <= 0;
    }

    public boolean expired() {
      return this.expiresAt > 0 && System.currentTimeMillis() >= this.expiresAt;
    }
  }

  public static class Data {

    public int version = 1;
    /** Keyed by the lower-cased player name. */
    public Map<String, Ban> bans = new LinkedHashMap<>();
  }

  private static final Type DATA_TYPE = new TypeToken<Data>() {
  }.getType();

  private final Path file;
  private final Logger logger;
  private final Data data;
  private final Object lock = new Object();

  public BanStore(Path file, Logger logger) {
    this.file = file;
    this.logger = logger;
    Data loaded = JsonFiles.read(file, DATA_TYPE, new Data(), "bans.json");
    if (loaded.bans == null) {
      loaded.bans = new LinkedHashMap<>();
    }
    this.data = loaded;
  }

  private void save() {
    JsonFiles.write(this.file, this.data, this.logger, "bans.json");
  }

  public Ban find(UUID uuid, String name) {
    synchronized (this.lock) {
      this.pruneExpired();
      String id = uuid == null ? null : uuid.toString();
      for (Ban ban : this.data.bans.values()) {
        if (id != null && ban.uuid != null && ban.uuid.equalsIgnoreCase(id)) {
          return ban;
        }
        if (name != null && name.equalsIgnoreCase(ban.name)) {
          return ban;
        }
      }
      return null;
    }
  }

  public Ban add(String name, UUID uuid, String reason, String bannedBy, long durationMillis) {
    synchronized (this.lock) {
      this.pruneExpired();
      Ban ban = new Ban();
      ban.name = name;
      ban.uuid = uuid == null ? null : uuid.toString();
      ban.reason = reason;
      ban.bannedBy = bannedBy;
      ban.createdAt = System.currentTimeMillis();
      ban.expiresAt = durationMillis <= 0 ? 0L : safeAdd(ban.createdAt, durationMillis);
      this.data.bans.put(name.toLowerCase(Locale.ROOT), ban);
      this.save();
      return ban;
    }
  }

  public boolean remove(String name) {
    if (name == null || name.isBlank()) {
      return false;
    }
    synchronized (this.lock) {
      boolean removed = this.data.bans.remove(name.toLowerCase(Locale.ROOT)) != null;
      if (!removed) {
        removed = this.data.bans.values().removeIf(ban -> name.equalsIgnoreCase(ban.name));
      }
      if (removed) {
        this.save();
      }
      return removed;
    }
  }

  public List<Ban> active() {
    synchronized (this.lock) {
      this.pruneExpired();
      return new ArrayList<>(this.data.bans.values());
    }
  }

  public int size() {
    synchronized (this.lock) {
      return this.data.bans.size();
    }
  }

  private void pruneExpired() {
    boolean changed = this.data.bans.values().removeIf(Ban::expired);
    if (changed) {
      this.save();
    }
  }

  private static long safeAdd(long base, long duration) {
    try {
      return Math.addExact(base, duration);
    } catch (ArithmeticException exception) {
      return Long.MAX_VALUE;
    }
  }
}
