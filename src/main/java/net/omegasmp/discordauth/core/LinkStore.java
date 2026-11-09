package net.omegasmp.discordauth.core;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;

/** Persistent Minecraft &lt;-&gt; Discord link storage backed by links.json. */
public class LinkStore {

  public static class Link {

    public String name;
    public String discordId;
    public String discordName;
    public String discordNick;
    public String discordGlobal;
    public long linkedAt;
    /** Epoch millis of the last successful Discord nickname refresh (0 = never). */
    public long lastIdentityRefresh;

    public Link() {
    }

    public Link(String name, String discordId, String discordName) {
      this.name = name;
      this.discordId = discordId;
      this.discordName = discordName;
      this.linkedAt = System.currentTimeMillis();
    }

    public String displayName(String source) {
      for (String candidate : displayNameCandidates(source)) {
        if (candidate != null && !candidate.isBlank()) {
          return candidate;
        }
      }
      return null;
    }

    public List<String> displayNameCandidates(String source) {
      if ("username".equalsIgnoreCase(source)) {
        return Arrays.asList(this.discordName, this.discordGlobal, this.discordNick);
      }
      if ("global".equalsIgnoreCase(source)) {
        return Arrays.asList(this.discordGlobal, this.discordNick, this.discordName);
      }
      return Arrays.asList(this.discordNick, this.discordGlobal, this.discordName);
    }
  }

  public static class Data {

    public int version = 1;
    public Map<String, Link> links = new LinkedHashMap<>();
  }

  private static final Type DATA_TYPE = new TypeToken<Data>() {
  }.getType();

  private final Path file;
  private final Logger logger;
  private final Data data;
  private final Object lock = new Object();

  public LinkStore(Path file, Logger logger) {
    this.file = file;
    this.logger = logger;
    Data loaded = JsonFiles.read(file, DATA_TYPE, new Data(), "links.json");
    if (loaded.links == null) {
      loaded.links = new LinkedHashMap<>();
    }
    this.data = loaded;
  }

  private void save() {
    JsonFiles.write(this.file, this.data, this.logger, "links.json");
  }

  public boolean isLinked(UUID uuid) {
    synchronized (this.lock) {
      Link link = this.data.links.get(uuid.toString());
      return link != null && link.discordId != null && !link.discordId.isBlank();
    }
  }

  public Link getLink(UUID uuid) {
    synchronized (this.lock) {
      return this.data.links.get(uuid.toString());
    }
  }

  public Link getLinkByName(String name) {
    if (name == null) {
      return null;
    }
    synchronized (this.lock) {
      for (Link link : this.data.links.values()) {
        if (link.name != null && link.name.equalsIgnoreCase(name)) {
          return link;
        }
      }
      return null;
    }
  }

  public String getUuidByName(String name) {
    if (name == null) {
      return null;
    }
    synchronized (this.lock) {
      for (Map.Entry<String, Link> entry : this.data.links.entrySet()) {
        if (entry.getValue().name != null && entry.getValue().name.equalsIgnoreCase(name)) {
          return entry.getKey();
        }
      }
      return null;
    }
  }

  public String getUuidByDiscordId(String discordId) {
    if (discordId == null) {
      return null;
    }
    synchronized (this.lock) {
      for (Map.Entry<String, Link> entry : this.data.links.entrySet()) {
        if (entry.getValue().discordId != null && entry.getValue().discordId.equals(discordId)) {
          return entry.getKey();
        }
      }
      return null;
    }
  }

  public List<Link> getLinksByDiscordId(String discordId) {
    List<Link> result = new ArrayList<>();
    if (discordId == null) {
      return result;
    }
    synchronized (this.lock) {
      for (Link link : this.data.links.values()) {
        if (link.discordId != null && link.discordId.equals(discordId)) {
          result.add(link);
        }
      }
    }
    result.sort(Comparator.comparingLong(link -> link.linkedAt));
    return result;
  }

  public int countByDiscordId(String discordId) {
    if (discordId == null) {
      return 0;
    }
    synchronized (this.lock) {
      int result = 0;
      for (Link link : this.data.links.values()) {
        if (link.discordId != null && link.discordId.equals(discordId)) {
          result++;
        }
      }
      return result;
    }
  }

  public String getNameByDiscordId(String discordId) {
    if (discordId == null) {
      return null;
    }
    synchronized (this.lock) {
      for (Link link : this.data.links.values()) {
        if (link.discordId != null && link.discordId.equals(discordId)) {
          return link.name;
        }
      }
      return null;
    }
  }

  public void link(UUID uuid, String name, String discordId, String discordName) {
    synchronized (this.lock) {
      Link link = new Link(name, discordId, discordName);
      link.lastIdentityRefresh = System.currentTimeMillis();
      this.data.links.put(uuid.toString(), link);
      this.save();
    }
    this.logger.info("Linked Minecraft {} ({}) to Discord {}{}",
        name, uuid, discordId,
        discordName == null || discordName.isBlank() ? "" : " (@" + discordName + ")");
  }

  public void setDiscordName(UUID uuid, String discordName) {
    synchronized (this.lock) {
      Link link = this.data.links.get(uuid.toString());
      if (link == null || discordName == null || discordName.isBlank()
          || discordName.equals(link.discordName)) {
        return;
      }
      link.discordName = discordName;
      this.save();
    }
  }

  public boolean setDiscordIdentity(UUID uuid, String discordName, String discordNick, String discordGlobal) {
    synchronized (this.lock) {
      Link link = this.data.links.get(uuid.toString());
      if (link == null) {
        return false;
      }
      boolean changed = false;
      if (discordName != null && !discordName.isBlank() && !discordName.equals(link.discordName)) {
        link.discordName = discordName;
        changed = true;
      }
      if (discordNick != null && !discordNick.isBlank() && !discordNick.equals(link.discordNick)) {
        link.discordNick = discordNick;
        changed = true;
      }
      if (discordGlobal != null && !discordGlobal.isBlank() && !discordGlobal.equals(link.discordGlobal)) {
        link.discordGlobal = discordGlobal;
        changed = true;
      }
      link.lastIdentityRefresh = System.currentTimeMillis();
      if (changed) {
        this.save();
      }
      return changed;
    }
  }

  /** Marks a refresh attempt so a failing Discord lookup is not retried on every join. */
  public void touchIdentityRefresh(UUID uuid) {
    synchronized (this.lock) {
      Link link = this.data.links.get(uuid.toString());
      if (link != null) {
        link.lastIdentityRefresh = System.currentTimeMillis();
      }
    }
  }

  public boolean needsIdentityRefresh(UUID uuid, int minutes) {
    if (minutes <= 0) {
      return false;
    }
    synchronized (this.lock) {
      Link link = this.data.links.get(uuid.toString());
      if (link == null || link.lastIdentityRefresh <= 0) {
        return true;
      }
      return System.currentTimeMillis() - link.lastIdentityRefresh > minutes * 60_000L;
    }
  }

  public boolean unlink(UUID uuid) {
    boolean removed;
    synchronized (this.lock) {
      removed = this.data.links.remove(uuid.toString()) != null;
      if (removed) {
        this.save();
      }
    }
    if (removed) {
      this.logger.info("Unlinked Minecraft account {}", uuid);
    }
    return removed;
  }

  public int size() {
    synchronized (this.lock) {
      return this.data.links.size();
    }
  }
}
