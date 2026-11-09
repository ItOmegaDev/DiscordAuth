package net.omegasmp.discordauth.paper;

import java.util.Locale;
import net.omegasmp.discordauth.core.AuthSession;
import net.omegasmp.discordauth.core.BanStore;
import net.omegasmp.discordauth.core.Config;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

/**
 * Enforces authentication on Paper: the ban check happens before the world is entered, the player
 * is frozen on join and every interaction that a logged-out player must not perform is cancelled.
 */
public final class PaperAuthListener implements Listener {

  private final DiscordAuthPaper plugin;
  private final PaperHost host;

  public PaperAuthListener(DiscordAuthPaper plugin, PaperHost host) {
    this.plugin = plugin;
    this.host = host;
  }

  // ------------------------------------------------------------- before world join

  @EventHandler(priority = EventPriority.LOWEST)
  public void onPreLogin(AsyncPlayerPreLoginEvent event) {
    String ip = event.getAddress().getHostAddress();
    // IP rate limiting
    if (this.host.isIpBlocked(ip)) {
      event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
          net.kyori.adventure.text.Component.text("§cЗаблоковано. Ваш IP тимчасово заблокований через підозрілу активність."));
      this.host.logger().warn("{} ({}) blocked — IP rate limit exceeded.", event.getName(), ip);
      return;
    }
    if (this.host.recordConnection(ip)) {
      event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
          net.kyori.adventure.text.Component.text("§cЗаблоковано. Ваш IP тимчасово заблокований через підозрілу активність."));
      this.host.logger().warn("{} ({}) blocked — too many connections from this IP.", event.getName(), ip);
      return;
    }
    BanStore.Ban ban = this.host.bans().find(event.getUniqueId(), event.getName());
    if (ban == null) {
      return;
    }
    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
        this.host.service().banComponent(ban));
    this.host.logger().info("{} tried to join but is banned ({}).", event.getName(), ban.reason);
  }

  // ------------------------------------------------------------------ after join

  @EventHandler(priority = EventPriority.MONITOR)
  public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    boolean bedrock = this.host.isBedrockName(player.getName());
    boolean needsDiscord = !this.plugin.config().auth.testMode
        && !this.host.links().isLinked(player.getUniqueId())
        && !this.host.bypassesDiscord(player);
    boolean needsPassword = this.host.needsPassword(player, bedrock);
    if (!needsDiscord && !needsPassword) {
      this.host.scheduleTabUpdate(player);
      return;
    }

    AuthSession previous = this.host.sessions().byPlayer(player.getUniqueId());
    if (previous != null) {
      this.host.service().dropSession(previous);
    }
    String address = player.getAddress() == null || player.getAddress().getAddress() == null
        ? "" : player.getAddress().getAddress().getHostAddress();
    AuthSession session = this.host.service().createSession(
        player.getUniqueId(), player.getName(), needsDiscord, needsPassword, address);
    session.setHandle(player);
    this.host.hold(player, session);
    this.host.logger().info("{} is not fully authorized — holding in place (discord={}, password={}{}).",
        player.getName(), needsDiscord, needsPassword,
        needsDiscord ? ", code " + session.getCode() : "");
  }

  // ------------------------------------------------------------------- blocking

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onMove(PlayerMoveEvent event) {
    AuthSession session = this.host.sessions().byPlayer(event.getPlayer().getUniqueId());
    if (session == null || event.getTo() == null) {
      return;
    }
    if (event.getFrom().distanceSquared(event.getTo()) > 0.0D) {
      event.setTo(event.getFrom().setDirection(event.getTo().getDirection()));
    }
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onCommand(PlayerCommandPreprocessEvent event) {
    AuthSession session = this.host.sessions().byPlayer(event.getPlayer().getUniqueId());
    if (session == null || session.isComplete()) {
      return;
    }
    String line = event.getMessage().toLowerCase(Locale.ROOT);
    if (!line.startsWith("/")) {
      return;
    }
    String[] parts = line.substring(1).trim().split("\\s+");
    if (parts.length == 0) {
      return;
    }
    Config config = this.plugin.config();
    if (config.commands.login.contains(parts[0]) || config.commands.register.contains(parts[0])) {
      return;
    }
    event.setCancelled(true);
    this.host.send(session, this.host.service().hint(session));
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onBreak(BlockBreakEvent event) {
    this.cancelIfHeld(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onPlace(BlockPlaceEvent event) {
    this.cancelIfHeld(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onInteract(PlayerInteractEvent event) {
    this.cancelIfHeld(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onDrop(PlayerDropItemEvent event) {
    this.cancelIfHeld(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onPickup(EntityPickupItemEvent event) {
    if (event.getEntity() instanceof Player player) {
      this.cancelIfHeld(player, event);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onSwap(PlayerSwapHandItemsEvent event) {
    this.cancelIfHeld(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onSneak(PlayerToggleSneakEvent event) {
    this.cancelIfHeld(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onInventory(InventoryOpenEvent event) {
    if (event.getPlayer() instanceof Player player) {
      this.cancelIfHeld(player, event);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onInventoryClick(InventoryClickEvent event) {
    if (event.getWhoClicked() instanceof Player player) {
      this.cancelIfHeld(player, event);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onHunger(FoodLevelChangeEvent event) {
    if (event.getEntity() instanceof Player player) {
      this.cancelIfHeld(player, event);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onDamage(EntityDamageEvent event) {
    if (event.getEntity() instanceof Player player) {
      this.cancelIfHeld(player, event);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onDamageBy(EntityDamageByEntityEvent event) {
    if (event.getDamager() instanceof Player player) {
      this.cancelIfHeld(player, event);
    }
  }

  private void cancelIfHeld(Player player, org.bukkit.event.Cancellable event) {
    if (player == null) {
      return;
    }
    if (this.host.sessions().byPlayer(player.getUniqueId()) != null) {
      event.setCancelled(true);
    }
  }

  /**
   * Chat during authentication: only {@code /login} and {@code /register} reach the plugin;
   * everything else is replaced by the hint.
   */
  @EventHandler(priority = EventPriority.LOWEST)
  public void onChat(io.papermc.paper.event.player.AsyncChatEvent event) {
    AuthSession session = this.host.sessions().byPlayer(event.getPlayer().getUniqueId());
    if (session == null || session.isComplete()) {
      return;
    }
    event.setCancelled(true);
    String raw = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
        .plainText().serialize(event.message()).trim();
    Config config = this.plugin.config();
    String[] args = raw.split("\\s+");
    String name = args[0].toLowerCase(Locale.ROOT);

    if (config.commands.login.contains(name)) {
      this.host.send(session, this.host.service().login(session, args));
      return;
    }
    if (config.commands.register.contains(name)) {
      this.host.send(session, this.host.service().register(session, args));
      return;
    }
    this.host.send(session, this.host.service().hint(session));
  }
}
