package net.omegasmp.discordauth.core;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;

/**
 * Persistent password storage backed by accounts.json. Passwords are stored as BCrypt hashes,
 * never in plain text. All writes are atomic.
 */
public class AccountStore {

  /** BCrypt cost: strong enough and still fast enough to run off the main thread. */
  private static final int BCRYPT_COST = 12;

  public static class Account {

    public String name;
    public String hash;
    public long registeredAt;
    public long lastLogin;
  }

  public static class Data {

    public int version = 1;
    public Map<String, Account> accounts = new LinkedHashMap<>();
  }

  private static final Type DATA_TYPE = new TypeToken<Data>() {
  }.getType();

  private final Path file;
  private final Logger logger;
  private final Data data;
  private final Object lock = new Object();

  public AccountStore(Path file, Logger logger) {
    this.file = file;
    this.logger = logger;
    Data loaded = JsonFiles.read(file, DATA_TYPE, new Data(), "accounts.json");
    if (loaded.accounts == null) {
      loaded.accounts = new LinkedHashMap<>();
    }
    this.data = loaded;
  }

  private void save() {
    JsonFiles.write(this.file, this.data, this.logger, "accounts.json");
  }

  public boolean isRegistered(UUID uuid) {
    synchronized (this.lock) {
      Account account = this.data.accounts.get(uuid.toString());
      return account != null && account.hash != null && !account.hash.isBlank();
    }
  }

  public long lastLogin(UUID uuid) {
    synchronized (this.lock) {
      Account account = this.data.accounts.get(uuid.toString());
      return account == null ? 0L : account.lastLogin;
    }
  }

  public long registeredAt(UUID uuid) {
    synchronized (this.lock) {
      Account account = this.data.accounts.get(uuid.toString());
      return account == null ? 0L : account.registeredAt;
    }
  }

  public String getName(UUID uuid) {
    synchronized (this.lock) {
      Account account = this.data.accounts.get(uuid.toString());
      return account == null ? null : account.name;
    }
  }

  public boolean register(UUID uuid, String name, String password) {
    String hash = BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray());
    synchronized (this.lock) {
      Account account = this.data.accounts.get(uuid.toString());
      if (account != null && account.hash != null && !account.hash.isBlank()) {
        return false;
      }
      if (account == null) {
        account = new Account();
        account.registeredAt = System.currentTimeMillis();
        this.data.accounts.put(uuid.toString(), account);
      }
      account.name = name;
      account.hash = hash;
      account.lastLogin = System.currentTimeMillis();
      this.save();
    }
    this.logger.info("Registered a password for Minecraft {} ({})", name, uuid);
    return true;
  }

  public boolean verify(UUID uuid, String password) {
    String hash;
    synchronized (this.lock) {
      Account account = this.data.accounts.get(uuid.toString());
      if (account == null || account.hash == null || account.hash.isBlank()) {
        return false;
      }
      hash = account.hash;
    }
    boolean ok;
    try {
      ok = BCrypt.verifyer().verify(password.toCharArray(), hash).verified;
    } catch (RuntimeException exception) {
      this.logger.error("Unable to check the stored password hash for {}", uuid, exception);
      return false;
    }
    if (!ok) {
      return false;
    }
    synchronized (this.lock) {
      Account account = this.data.accounts.get(uuid.toString());
      if (account != null) {
        account.lastLogin = System.currentTimeMillis();
        this.save();
      }
    }
    return true;
  }

  public boolean changePassword(UUID uuid, String newPassword) {
    String hash = BCrypt.withDefaults().hashToString(BCRYPT_COST, newPassword.toCharArray());
    synchronized (this.lock) {
      Account account = this.data.accounts.get(uuid.toString());
      if (account == null || account.hash == null || account.hash.isBlank()) {
        return false;
      }
      account.hash = hash;
      this.save();
    }
    return true;
  }

  public boolean unregister(UUID uuid) {
    boolean removed;
    synchronized (this.lock) {
      removed = this.data.accounts.remove(uuid.toString()) != null;
      if (removed) {
        this.save();
      }
    }
    if (removed) {
      this.logger.info("Removed the password of Minecraft account {}", uuid);
    }
    return removed;
  }

  public int size() {
    synchronized (this.lock) {
      return this.data.accounts.size();
    }
  }
}
