package net.omegasmp.discordauth.paper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.Title.Times;
import net.omegasmp.discordauth.core.AccountStore;
import net.omegasmp.discordauth.core.AuthHost;
import net.omegasmp.discordauth.core.AuthService;
import net.omegasmp.discordauth.core.AuthSession;
import net.omegasmp.discordauth.core.BanStore;
import net.omegasmp.discordauth.core.Config;
import net.omegasmp.discordauth.core.LinkStore;
import net.omegasmp.discordauth.core.Scheduler;
import net.omegasmp.discordauth.core.SessionRegistry;
import net.omegasmp.discordauth.core.Text;
import net.omegasmp.discordauth.discord.DiscordBot;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.slf4j.Logger;

/**
 * Paper side of the plugin ({@code role=auth}): holds unauthenticated players in a frozen lobby,
 * shows the dialog UI (or the chat UI for old clients) and releases them in place.
 *
 * <p>Unlike Velocity there is no second connection: the player is already inside the server, so
 * "release" means restoring the original position, gamemode, flight and visibility.</p>
 */
public final class PaperHost implements AuthHost {

  /** Per-player state captured on join and restored on release. */
  private static final class Held {

    private final Location location;
    private final GameMode gameMode;
    private final boolean allowFlight;
    private final boolean flying;
    private final boolean invulnerable;
    private final boolean collidable;
    private final float walkSpeed;
    private final int fireTicks;

    private Held(Player player) {
      this.location = player.getLocation().clone();
      this.gameMode = player.getGameMode();
      this.allowFlight = player.getAllowFlight();
      this.flying = player.isFlying();
      this.invulnerable = player.isMarkedInvulnerable();
      this.collidable = player.isCollidable();
      this.walkSpeed = player.getWalkSpeed();
      this.fireTicks = player.getFireTicks();
    }
  }

  private final DiscordAuthPaper plugin;
  private final Logger logger;
  private final Config config;
  private final AccountStore accounts;
  private final LinkStore links;
  private final BanStore bans;
  private final SessionRegistry sessions;
  private final Scheduler scheduler;

  private AuthService service;
  private DiscordBot bot;
  private PaperDialogs dialogs;
  private PaperBridge bridge;

  private final Map<UUID, Held> held = new ConcurrentHashMap<>();
  private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
  private final Set<UUID> hiddenFromOthers = ConcurrentHashMap.newKeySet();
  /** IP connection tracking: ip -> list of connection timestamps. */
  private final Map<String, List<Long>> ipConnections = new ConcurrentHashMap<>();
  /** Blocked IPs: ip -> unblock timestamp. */
  private final Map<String, Long> blockedIps = new ConcurrentHashMap<>();

  public PaperHost(DiscordAuthPaper plugin, Logger logger, Config config, AccountStore accounts,
      LinkStore links, BanStore bans, SessionRegistry sessions, Scheduler scheduler) {
    this.plugin = plugin;
    this.logger = logger;
    this.config = config;
    this.accounts = accounts;
    this.links = links;
    this.bans = bans;
    this.sessions = sessions;
    this.scheduler = scheduler;
  }

  public void setService(AuthService service) {
    this.service = service;
    this.dialogs = new PaperDialogs(this);
  }

  public AuthService service() {
    return this.service;
  }

  public void setBot(DiscordBot bot) {
    this.bot = bot;
  }

  public DiscordBot bot() {
    return this.bot;
  }

  public void setBridge(PaperBridge bridge) {
    this.bridge = bridge;
  }

  public PaperBridge bridge() {
    return this.bridge;
  }

  public PaperDialogs dialogs() {
    return this.dialogs;
  }

  // ------------------------------------------------------------------ AuthHost

  @Override
  public Config config() {
    return this.config;
  }

  @Override
  public Scheduler scheduler() {
    return this.scheduler;
  }

  @Override
  public Logger logger() {
    return this.logger;
  }

  @Override
  public AccountStore accounts() {
    return this.accounts;
  }

  @Override
  public LinkStore links() {
    return this.links;
  }

  @Override
  public BanStore bans() {
    return this.bans;
  }

  @Override
  public SessionRegistry sessions() {
    return this.sessions;
  }

  @Override
  public boolean onlineMode() {
    return Bukkit.getOnlineMode();
  }

  @Override
  public void blockIpAndKick(String ip, java.util.UUID uuid) {
    if (ip != null && !ip.isBlank()) {
      this.blockedIps.put(ip, System.currentTimeMillis() + 3_600_000L);
      this.logger.warn("IP {} blocked for 1 hour via Discord button.", ip);
    }
    if (uuid != null) {
      Player player = org.bukkit.Bukkit.getPlayer(uuid);
      if (player != null && player.isOnline()) {
        player.kick(net.kyori.adventure.text.Component.text("&c&lЗаблоковано\n&7Ваш акаунт було заблоковано через підозрілу активність."));
        this.logger.info("Kicked {} ({}) via Discord button.", player.getName(), uuid);
      }
      AuthSession session = this.sessions.byPlayer(uuid);
      if (session != null) {
        this.service.dropSession(session);
      }
      this.forget(uuid);
    }
  }

  @Override
  public boolean isBedrockName(String name) {
    if (name == null || name.isEmpty()) {
      return false;
    }
    String lower = name.toLowerCase(Locale.ROOT);
    for (String prefix : this.config.auth.bedrockUsernamePrefixes) {
      if (prefix != null && !prefix.isEmpty() && lower.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public String addressOf(AuthSession session) {
    Player player = player(session);
    if (player == null || player.getAddress() == null
        || player.getAddress().getAddress() == null) {
      return "";
    }
    return player.getAddress().getAddress().getHostAddress();
  }

  @Override
  public void send(AuthSession session, Component message) {
    if (message == null) {
      return;
    }
    Player player = player(session);
    if (player == null) {
      return;
    }
    try {
      player.sendMessage(message);
    } catch (Throwable throwable) {
      this.logger.debug("Unable to send a message to {}", session.getUsername(), throwable);
    }
  }

  @Override
  public void disconnect(AuthSession session, Component reason) {
    if (session != null) {
      this.sessions.remove(session);
    }
    Player player = player(session);
    if (player == null) {
      return;
    }
    try {
      player.kick(reason == null ? Component.empty() : reason);
    } catch (Throwable throwable) {
      this.logger.debug("Unable to kick {}", player.getName(), throwable);
    }
  }

  @Override
  public void showUi(AuthSession session) {
    if (this.dialogs != null) {
      this.dialogs.show(session);
      return;
    }
    if (session.needsPassword()) {
      this.send(session, this.service.passwordPrompt(session.getUuid()));
    }
  }

  @Override
  public void passwordFailed(AuthSession session) {
    if (this.dialogs != null) {
      this.scheduler.runLater(() -> this.dialogs.show(session), 250L);
    }
  }

  /** Restores the player and lets them play. */
  @Override
  public void release(AuthSession session) {
    Player player = player(session);
    if (player == null) {
      this.logger.warn("{} is authorized, but is no longer online.", session.getUsername());
      return;
    }
    Held state = this.held.remove(session.getUuid());
    this.hideBossBar(session.getUuid());
    this.unhide(player);
    try {
      player.closeDialog();
    } catch (Throwable throwable) {
      this.logger.debug("Unable to close the dialog of {}", player.getName(), throwable);
    }
    // The entity scheduler runs on the thread that owns this player (Folia-safe).
    player.getScheduler().run(this.plugin, task -> {
      if (!player.isOnline()) {
        return;
      }
      try {
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        if (state != null) {
          player.setWalkSpeed(state.walkSpeed);
          player.setInvulnerable(state.invulnerable);
          player.setCollidable(state.collidable);
          player.setFireTicks(state.fireTicks);
          if (this.config.paper.adventure) {
            player.setGameMode(state.gameMode);
            player.setAllowFlight(state.allowFlight);
            player.setFlying(state.flying);
          }
          if (state.location.getWorld() != null
              && !state.location.getWorld().equals(player.getWorld())) {
            player.teleport(state.location);
          }
        }
      } catch (Throwable throwable) {
        this.logger.warn("Unable to restore the state of {}", player.getName(), throwable);
      }
    }, null);
  }

  @Override
  public void passwordChanged(UUID uuid, String name) {
    Player player = Bukkit.getPlayer(uuid);
    if (player != null && player.isOnline()) {
      player.sendMessage(Text.component(Text.render(
          this.config.messages.passwordChangedDiscord, this.config, name, null, 0)));
    }
    this.identityChanged(uuid);
  }

  @Override
  public void identityChanged(UUID uuid) {
    Player player = Bukkit.getPlayer(uuid);
    if (player == null || !player.isOnline()) {
      return;
    }
    this.applyTabName(player);
    if (this.bridge != null) {
      // In bridge mode the proxy owns the identity; locally-stored links are ignored.
      return;
    }
  }

  @Override
  public UUID onlineUuid(String name) {
    Player player = Bukkit.getPlayerExact(name == null ? "" : name.trim());
    return player == null ? null : player.getUniqueId();
  }

  @Override
  public boolean kickOnline(String name, Component reason) {
    Player player = Bukkit.getPlayerExact(name == null ? "" : name.trim());
    if (player == null) {
      return false;
    }
    player.kick(reason == null ? Component.empty() : reason);
    return true;
  }

  // ------------------------------------------------------------ hold / release

  public Player player(AuthSession session) {
    if (session == null) {
      return null;
    }
    return Bukkit.getPlayer(session.getUuid());
  }

  /** Freezes the player where they joined (spawn/anchor) while they authenticate. */
  public void hold(Player player, AuthSession session) {
    this.held.putIfAbsent(player.getUniqueId(), new Held(player));
    try {
      if (this.config.paper.freeze) {
        player.setWalkSpeed(0F);
        player.setInvulnerable(true);
        player.setFireTicks(0);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
            PotionEffect.INFINITE_DURATION, 255, false, false, false));
      }
      if (this.config.paper.blindness) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
            PotionEffect.INFINITE_DURATION, 0, false, false, false));
      }
      if (this.config.paper.hidePlayers) {
        this.hide(player);
      }
    } catch (Throwable throwable) {
      this.logger.warn("Unable to freeze {} in the lobby", player.getName(), throwable);
    }
    if (this.dialogs != null) {
      this.dialogs.show(session);
    }
  }

  private void hide(Player player) {
    this.hiddenFromOthers.add(player.getUniqueId());
    for (Player other : Bukkit.getOnlinePlayers()) {
      if (other.equals(player)) {
        continue;
      }
      try {
        other.hidePlayer(this.plugin, player);
        if (this.sessions.byPlayer(other.getUniqueId()) == null) {
          player.hidePlayer(this.plugin, other);
        }
      } catch (Throwable throwable) {
        this.logger.debug("Unable to hide {} from {}", player.getName(), other.getName(), throwable);
      }
    }
  }

  private void unhide(Player player) {
    this.hiddenFromOthers.remove(player.getUniqueId());
    for (Player other : Bukkit.getOnlinePlayers()) {
      if (other.equals(player)) {
        continue;
      }
      try {
        other.showPlayer(this.plugin, player);
        player.showPlayer(this.plugin, other);
      } catch (Throwable throwable) {
        this.logger.debug("Unable to show {} to {}", player.getName(), other.getName(), throwable);
      }
    }
  }

  /** Called from the join listener so a newly joining player is hidden from everyone still held. */
  public void hideHeldFrom(Player joining) {
    for (UUID uuid : this.hiddenFromOthers) {
      Player other = Bukkit.getPlayer(uuid);
      if (other == null || other.equals(joining)) {
        continue;
      }
      try {
        joining.hidePlayer(this.plugin, other);
      } catch (Throwable throwable) {
        this.logger.debug("Unable to hide a held player from {}", joining.getName(), throwable);
      }
    }
  }

  /** Checks if an IP is currently blocked due to too many connections. */
  public boolean isIpBlocked(String ip) {
    if (ip == null || ip.isBlank()) return false;
    Long until = this.blockedIps.get(ip);
    if (until == null) return false;
    if (System.currentTimeMillis() > until) {
      this.blockedIps.remove(ip);
      return false;
    }
    return true;
  }

  /** Records a connection from an IP and blocks it if the limit is exceeded. */
  public boolean recordConnection(String ip) {
    if (ip == null || ip.isBlank()) return false;
    int limit = this.config.auth.maxConnectionsPerIpPerMinute;
    if (limit <= 0) return false;
    long now = System.currentTimeMillis();
    long windowStart = now - 60_000L;
    List<Long> times = this.ipConnections.computeIfAbsent(ip, k -> new java.util.ArrayList<>());
    synchronized (times) {
      times.removeIf(t -> t < windowStart);
      times.add(now);
      if (times.size() > limit) {
        long blockUntil = now + Math.max(1, this.config.auth.ipBlockDurationSeconds) * 1000L;
        this.blockedIps.put(ip, blockUntil);
        this.logger.warn("IP {} blocked for {}s — too many connections ({} in 60s)",
            ip, Math.max(1, this.config.auth.ipBlockDurationSeconds), times.size());
        return true;
      }
    }
    return false;
  }

  public boolean isHeld(UUID uuid) {
    return this.held.containsKey(uuid);
  }

  /** Forgets a player that left while still authenticating. */
  public void forget(UUID uuid) {
    this.held.remove(uuid);
    this.hiddenFromOthers.remove(uuid);
    this.hideBossBar(uuid);
  }

  // ----------------------------------------------------------------- title / action bar

  public void showAuthTitle(Player player, String title, String subtitle, AuthSession session) {
    if (player == null || !player.isOnline()) {
      return;
    }
    if ((title == null || title.isBlank()) && (subtitle == null || subtitle.isBlank())) {
      return;
    }
    Component titleComponent = Text.component(Text.render(title, this.config, session, 0));
    Component subtitleComponent = Text.component(Text.render(subtitle, this.config, session, 0));
    player.showTitle(Title.title(titleComponent, subtitleComponent,
        Times.times(Duration.ofMillis(400), Duration.ofMillis(6000), Duration.ofMillis(800))));
  }

  public void showAuthActionBar(Player player, String template, AuthSession session) {
    if (player == null || !player.isOnline() || template == null || template.isBlank()) {
      return;
    }
    player.sendActionBar(Text.component(Text.render(template, this.config, session, 0)));
  }

  // ------------------------------------------------------------------ boss bar

  /** Updates the countdown once per second (driven by {@code DiscordAuthPaper}). */
  public void tickBossBars() {
    for (AuthSession session : this.sessions.all()) {
      Player player = player(session);
      if (session.isComplete() || player == null || !player.isOnline()) {
        this.hideBossBar(session.getUuid());
        continue;
      }
      boolean discordStep = session.needsDiscord();
      long totalSeconds = Math.max(1L, discordStep
          ? this.config.auth.codeTtlSeconds
          : this.config.password.timeoutSeconds);
      long remaining = Math.max(0L, (session.getExpiresAt() - System.currentTimeMillis() + 999L) / 1000L);
      float progress = Math.max(0F, Math.min(1F, remaining / (float) totalSeconds));
      String template = discordStep
          ? this.config.messages.bossBarDiscord
          : this.config.messages.bossBarPassword;
      if (template == null || template.isBlank()) {
        this.hideBossBar(session.getUuid());
        continue;
      }
      BossBar bar = this.bossBars.computeIfAbsent(session.getUuid(), uuid ->
          BossBar.bossBar(Component.empty(), progress, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS));
      bar.name(Text.component(Text.render(template, this.config, session, remaining)));
      bar.progress(progress);
      try {
        player.showBossBar(bar);
      } catch (Throwable throwable) {
        this.logger.debug("Unable to show the boss bar to {}", player.getName(), throwable);
      }
    }
    List<UUID> stale = new ArrayList<>();
    for (UUID uuid : this.bossBars.keySet()) {
      if (this.sessions.byPlayer(uuid) == null) {
        stale.add(uuid);
      }
    }
    for (UUID uuid : stale) {
      this.hideBossBar(uuid);
    }
  }

  private void hideBossBar(UUID uuid) {
    BossBar bar = this.bossBars.remove(uuid);
    if (bar == null) {
      return;
    }
    Player player = Bukkit.getPlayer(uuid);
    if (player != null) {
      player.hideBossBar(bar);
    }
  }

  // ------------------------------------------------------------------ tab list

  public void applyTabName(Player player) {
    if (player == null || !player.isOnline() || !this.config.tab.enabled) {
      return;
    }
    LinkStore.Link link = this.links.getLink(player.getUniqueId());
    if (link == null) {
      return;
    }
    Component display = Text.tab(this.config.tab.format, player.getName(), link, this.config);
    String hover = this.config.tab.hover;
    if (hover != null && !hover.isBlank()) {
      display = display.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
          Text.tab(hover, player.getName(), link, this.config)));
    }
    player.playerListName(display);
  }

  public void scheduleTabUpdate(Player player) {
    if (player == null || !this.config.tab.enabled) {
      return;
    }
    if (this.config.tab.skipWhenTabPluginInstalled
        && Bukkit.getPluginManager().isPluginEnabled("TAB")) {
      return;
    }
    this.applyTabName(player);
    for (long delay : new long[] {1000L, 3000L, 7000L, 15000L}) {
      this.scheduler.runLater(() -> this.applyTabName(player), delay);
    }
  }

  /** Refresh the stored Discord nick/global name at most every N minutes. */
  public void refreshDiscordIdentity(UUID uuid) {
    LinkStore.Link link = this.links.getLink(uuid);
    if (link == null || this.bot == null || link.discordId == null || link.discordId.isBlank()) {
      return;
    }
    if (!this.links.needsIdentityRefresh(uuid, this.config.discord.identityRefreshMinutes)) {
      return;
    }
    this.links.touchIdentityRefresh(uuid);
    this.bot.resolveMember(link.discordId, member -> {
      this.links.setDiscordIdentity(uuid, member.username, member.nick, member.global);
      this.identityChanged(uuid);
    });
  }

  /** Paper's own Geyser/Floodgate check is just the configured prefix list. */
  public boolean needsPassword(Player player, boolean bedrock) {
    if (this.config.auth.testMode) {
      return false;
    }
    if (!this.config.password.enabled) {
      return false;
    }
    if (this.onlineMode() && !this.config.password.requireWhenOnlineMode) {
      return false;
    }
    if (bedrock && this.config.auth.bedrockBypassPassword) {
      return false;
    }
    return true;
  }

  public boolean bypassesDiscord(Player player) {
    if (this.config.auth.testMode) {
      return true;
    }
    if (this.service.bypassesDiscord(player.getName())) {
      return true;
    }
    return this.isBedrockName(player.getName()) && this.config.auth.bedrockBypassVerification;
  }

  /** Chat prompt for clients without dialog support. */
  public void showChatPrompt(AuthSession session) {
    Player player = player(session);
    if (player == null) {
      return;
    }
    if (session.needsDiscord()) {
      this.send(session, Text.build(this.config.messages.chat, this.config, session));
      this.showAuthTitle(player, this.config.messages.title, this.config.messages.subtitle, session);
      this.showAuthActionBar(player, this.config.messages.actionBar, session);
    }
    if (session.needsPassword()) {
      if (!session.needsDiscord()) {
        this.showAuthTitle(player, this.config.messages.passwordTitle,
            this.config.messages.passwordSubtitle, session);
      }
      this.send(session, this.service.passwordPrompt(session.getUuid()));
      this.showAuthActionBar(player, this.config.messages.passwordActionBar, session);
    }
  }
}
