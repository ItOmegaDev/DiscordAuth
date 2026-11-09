package net.omegasmp.discordauth.paper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.omegasmp.discordauth.core.Config;
import net.omegasmp.discordauth.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.slf4j.Logger;

/**
 * {@code role=bridge}: the backend half of the Velocity auth plugin.
 *
 * <p>It only asks the proxy for the linked Discord identity of every player and caches it for
 * PlaceholderAPI. It never holds or authenticates anyone — the proxy already did that.</p>
 *
 * <p>The wire format is the same as the 1.x bridge, so an existing deployment keeps working:
 * {@code ID\0uuid\0linked\0nick\0username\0global\0discordId}.</p>
 */
public final class PaperBridge implements Listener, PluginMessageListener {

  private static final String REQUEST = "REQ";
  private static final String IDENTITY = "ID";

  private final DiscordAuthPaper plugin;
  private final Config config;
  private final Logger logger;
  private final Map<UUID, PaperIdentity> identities = new ConcurrentHashMap<>();

  public PaperBridge(DiscordAuthPaper plugin, Config config, Logger logger) {
    this.plugin = plugin;
    this.config = config;
    this.logger = logger;
  }

  public void register() {
    Bukkit.getMessenger().registerOutgoingPluginChannel(this.plugin, this.config.bridge.channel);
    Bukkit.getMessenger().registerIncomingPluginChannel(this.plugin, this.config.bridge.channel, this);
    Bukkit.getPluginManager().registerEvents(this, this.plugin);
    this.logger.info("Bridge mode: listening on '{}' for Discord identities from the proxy.",
        this.config.bridge.channel);
  }

  public void unregister() {
    Bukkit.getMessenger().unregisterIncomingPluginChannel(this.plugin, this.config.bridge.channel, this);
    Bukkit.getMessenger().unregisterOutgoingPluginChannel(this.plugin, this.config.bridge.channel);
    this.identities.clear();
  }

  public PaperIdentity identity(UUID uuid) {
    return uuid == null ? null : this.identities.get(uuid);
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    this.request(player);
    for (long delay : new long[] {20L, 60L, 140L, 300L}) {
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.request(player), delay);
    }
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    this.identities.remove(event.getPlayer().getUniqueId());
  }

  private void request(Player player) {
    if (!this.plugin.isEnabled() || !player.isOnline()) {
      return;
    }
    try {
      String payload = REQUEST + '\u0000' + player.getUniqueId();
      player.sendPluginMessage(this.plugin, this.config.bridge.channel, payload.getBytes(StandardCharsets.UTF_8));
    } catch (Throwable throwable) {
      this.logger.debug("Unable to ask the proxy for the identity of {}", player.getName(), throwable);
    }
  }

  @Override
  public void onPluginMessageReceived(String channel, Player player, byte[] message) {
    if (!this.config.bridge.channel.equals(channel) || message == null) {
      return;
    }
    String[] parts = new String(message, StandardCharsets.UTF_8).split("\u0000", -1);
    if (parts.length < 7 || !IDENTITY.equals(parts[0])) {
      return;
    }
    UUID uuid;
    try {
      uuid = UUID.fromString(parts[1]);
    } catch (IllegalArgumentException exception) {
      return;
    }
    PaperIdentity identity = new PaperIdentity(
        "1".equals(parts[2]),
        Text.sanitizeUser(parts[3]),
        Text.sanitizeUser(parts[4]),
        Text.sanitizeUser(parts[5]),
        Text.sanitizeUser(parts[6]));
    this.identities.put(uuid, identity);
    if (this.config.bridge.debug) {
      this.logger.info("Identity of {}: linked={} nick={} user={} global={}",
          uuid, identity.linked(), identity.nick(), identity.username(), identity.global());
    }
  }
}
