package net.omegasmp.discordauth.velocity;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
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
import org.slf4j.Logger;

/**
 * Velocity side of the plugin: limbo, chat UI, transfer release, TAB branding and the backend
 * bridge. All authentication decisions come from the shared {@link AuthService}.
 */
public class VelocityHost implements AuthHost {

  /** One-shot permission to skip the password for the reconnect our own Transfer causes. */
  private static final class TransferTicket {

    private final long expiresAt;
    private final String address;

    private TransferTicket(long expiresAt, String address) {
      this.expiresAt = expiresAt;
      this.address = address == null ? "" : address;
    }

    private boolean matches(String playerAddress) {
      if (System.currentTimeMillis() > this.expiresAt) {
        return false;
      }
      if (this.address.isEmpty() || playerAddress == null || playerAddress.isEmpty()) {
        return true;
      }
      return this.address.equals(playerAddress);
    }
  }

  private final ProxyServer server;
  private final Logger logger;
  private final Config config;
  private final AccountStore accounts;
  private final LinkStore links;
  private final BanStore bans;
  private final SessionRegistry sessions;
  private final Scheduler scheduler;
  private final Limbo limbo;
  private final LimboFactory limboFactory;

  private AuthService service;

  private final Map<UUID, VelocityLimboHandler> handlers = new ConcurrentHashMap<>();
  private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
  /** Remembered password logins: uuid -> "ip|expiresAt" (only when password.sessionMinutes > 0). */
  private final Map<UUID, String> passwordSessions = new ConcurrentHashMap<>();
  /** Last known IP per player for new-IP detection. */
  private final Map<UUID, String> lastKnownIp = new ConcurrentHashMap<>();
  /** IP connection tracking: ip -> list of connection timestamps. */
  private final Map<String, List<Long>> ipConnections = new ConcurrentHashMap<>();
  /** Blocked IPs: ip -> unblock timestamp. */
  private final Map<String, Long> blockedIps = new ConcurrentHashMap<>();
  private final Map<UUID, TransferTicket> transferTickets = new ConcurrentHashMap<>();
  private final Set<UUID> tabWarned = ConcurrentHashMap.newKeySet();

  private DiscordBot bot;
  private MinecraftChannelIdentifier identityChannel;
  private TabHook tabHook;

  public VelocityHost(ProxyServer server, Logger logger, Config config, AccountStore accounts,
      LinkStore links, BanStore bans, SessionRegistry sessions, Scheduler scheduler,
      Limbo limbo, LimboFactory limboFactory) {
    this.server = server;
    this.logger = logger;
    this.config = config;
    this.accounts = accounts;
    this.links = links;
    this.bans = bans;
    this.sessions = sessions;
    this.scheduler = scheduler;
    this.limbo = limbo;
    this.limboFactory = limboFactory;
  }

  public void setService(AuthService service) {
    this.service = service;
  }

  public void setBot(DiscordBot bot) {
    this.bot = bot;
  }

  public Limbo limbo() {
    return this.limbo;
  }

  public AuthService service() {
    return this.service;
  }

  public DiscordBot bot() {
    return this.bot;
  }

  public void registerHandler(UUID uuid, VelocityLimboHandler handler) {
    this.handlers.put(uuid, handler);
  }

  public void unregisterHandler(UUID uuid, VelocityLimboHandler handler) {
    this.handlers.remove(uuid, handler);
    BossBar bar = this.bossBars.remove(uuid);
    Player player = this.server.getPlayer(uuid).orElse(null);
    if (bar != null && player != null) {
      player.hideBossBar(bar);
    }
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
    return this.server.getConfiguration().isOnlineMode();
  }

  @Override
  public void blockIpAndKick(String ip, java.util.UUID uuid) {
    if (ip != null && !ip.isBlank()) {
      this.blockedIps.put(ip, System.currentTimeMillis() + 3_600_000L); // block for 1 hour
      this.logger.warn("IP {} blocked for 1 hour via Discord button.", ip);
    }
    if (uuid != null) {
      // Kick the player if online
      this.server.getPlayer(uuid).ifPresent(player -> {
        player.disconnect(Text.component("&c&lЗаблоковано\n&7Ваш акаунт було заблоковано через підозрілу активність."));
        this.logger.info("Kicked {} ({}) via Discord button.", player.getUsername(), uuid);
      });
      // Invalidate session
      AuthSession session = this.sessions.byPlayer(uuid);
      if (session != null) {
        this.service.dropSession(session);
      }
      this.forgetPasswordSession(uuid);
      this.lastKnownIp.remove(uuid);
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
    Player player = resolvePlayer(session);
    return player == null ? "" : addressOf(player);
  }

  public String addressOf(Player player) {
    if (player.getRemoteAddress() == null || player.getRemoteAddress().getAddress() == null) {
      return "";
    }
    return player.getRemoteAddress().getAddress().getHostAddress();
  }

  @Override
  public void send(AuthSession session, Component message) {
    if (message == null) {
      return;
    }
    Player player = resolvePlayer(session);
    if (player == null) {
      return;
    }
    try {
      Component prefixed = Text.component(this.config.messages.prefix + " §7- ").append(message);
      player.sendMessage(prefixed);
    } catch (Throwable throwable) {
      this.logger.debug("Unable to send a limbo message to {}", session.getUsername(), throwable);
    }
  }

  @Override
  public void disconnect(AuthSession session, Component reason) {
    Player player = resolvePlayer(session);
    this.sessions.remove(session);
    this.unregisterHandler(session.getUuid(), this.handlers.get(session.getUuid()));
    if (player == null) {
      return;
    }
    try {
      player.disconnect(reason);
    } catch (Throwable throwable) {
      this.logger.debug("Unable to disconnect {}", session.getUsername(), throwable);
    }
  }

  @Override
  public void showUi(AuthSession session) {
    VelocityLimboHandler handler = this.handlers.get(session.getUuid());
    if (handler != null) {
      handler.showUi();
      return;
    }
    // Not in the limbo yet (or already released): fall back to plain chat.
    if (session.needsDiscord()) {
      this.send(session, this.service.discordPrompt(session));
    }
    if (session.needsPassword()) {
      this.send(session, this.service.passwordPrompt(session.getUuid()));
    }
  }

  @Override
  public void release(AuthSession session) {
    Player player = resolvePlayer(session);
    if (player == null) {
      this.logger.warn("{} is authorized, but there is no connection to release — the state is saved.",
          session.getUsername());
      return;
    }
    boolean transfer = this.config.auth.transferOnVerify
        && player.getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_20_5) >= 0;
    if (!transfer) {
      try {
        this.limboFactory.passLoginLimbo(player);
      } catch (Throwable throwable) {
        this.logger.error("Unable to release {} to the main server", session.getUsername(), throwable);
      }
      return;
    }
    InetSocketAddress target = this.transferTarget(player);
    long delay = Math.max(0L, this.config.auth.transferDelayMillis);
    this.logger.info("{} authorized — transferring the client to {} in {} ms.", session.getUsername(), target, delay);
    if (delay == 0L) {
      this.transfer(player, target);
    } else {
      this.scheduler.runLater(() -> this.transfer(player, target), delay);
    }
  }

  @Override
  public void passwordChanged(UUID uuid, String name) {
    this.server.getPlayer(uuid).ifPresent(player ->
        player.sendMessage(Text.component(Text.render(
            this.config.messages.passwordChangedDiscord, this.config, name, null, 0))));
  }

  @Override
  public void identityChanged(UUID uuid) {
    Player player = this.server.getPlayer(uuid).orElse(null);
    if (player == null || !player.isActive()) {
      return;
    }
    this.applyTabName(player);
    this.sendIdentity(player);
  }

  @Override
  public UUID onlineUuid(String name) {
    return this.server.getPlayer(name == null ? "" : name.trim())
        .map(Player::getUniqueId)
        .orElse(null);
  }

  @Override
  public boolean kickOnline(String name, Component reason) {
    Optional<Player> target = this.server.getPlayer(name == null ? "" : name.trim());
    if (target.isEmpty()) {
      return false;
    }
    target.get().disconnect(reason);
    return true;
  }

  @Override
  public void passwordSucceeded(AuthSession session) {
    this.rememberPasswordSession(session);
    // Check if this is a new IP and send Discord alert
    Player player = resolvePlayer(session);
    if (player == null) return;
    String ip = this.addressOf(player);
    String lastIp = this.lastKnownIp.get(session.getUuid());
    if (lastIp != null && !lastIp.equals(ip) && this.bot != null) {
      // New IP detected — send Discord alert
      LinkStore.Link link = this.links.getLink(session.getUuid());
      if (link != null && link.discordId != null && !link.discordId.isBlank()) {
        this.bot.sendLoginAlert(link.discordId, session.getUsername(), ip, session.getUuid().toString());
        this.logger.info("New IP detected for {} ({} -> {}) — Discord alert sent.", session.getUsername(), lastIp, ip);
      }
    }
    this.lastKnownIp.put(session.getUuid(), ip);
  }

  @Override
  public void housekeeping() {
    this.pruneTransientState();
  }

  /** The player may not be registered in the proxy yet (login limbo), so the captured handle wins. */
  public Player resolvePlayer(AuthSession session) {
    if (session == null) {
      return null;
    }
    if (session.getHandle() instanceof Player player && player.isActive()) {
      return player;
    }
    return this.server.getPlayer(session.getUuid()).orElse(null);
  }

  // --------------------------------------------------------- join / authorization

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

  /** Decides whether the password step is needed for this login. */
  public boolean needsPassword(Player player, boolean bedrock) {
    if (this.config.auth.testMode) {
      return false;
    }
    if (!this.config.password.enabled) {
      return false;
    }
    if (player.isOnlineMode() && !this.config.password.requireWhenOnlineMode) {
      return false;
    }
    if (bedrock && this.config.auth.bedrockBypassPassword) {
      return false;
    }
    if (this.consumeTransferTicket(player)) {
      return false;
    }
    return !this.hasPasswordSession(player);
  }

  public boolean bypassesDiscord(Player player) {
    if (this.config.auth.testMode) {
      return true;
    }
    if (this.service.bypassesDiscord(player.getUsername())) {
      return true;
    }
    return this.isBedrockName(player.getUsername()) && this.config.auth.bedrockBypassVerification;
  }

  private boolean consumeTransferTicket(Player player) {
    TransferTicket ticket = this.transferTickets.remove(player.getUniqueId());
    if (ticket == null) {
      return false;
    }
    boolean accepted = ticket.matches(this.addressOf(player));
    if (this.config.debug) {
      this.logger.info("[trace] transfer ticket of {} {}", player.getUsername(),
          accepted ? "accepted — password step skipped for this reconnect" : "rejected");
    }
    return accepted;
  }

  private boolean hasPasswordSession(Player player) {
    String entry = this.passwordSessions.get(player.getUniqueId());
    if (entry == null) {
      return false;
    }
    int split = entry.lastIndexOf('|');
    if (split <= 0) {
      this.passwordSessions.remove(player.getUniqueId());
      return false;
    }
    long expiresAt;
    try {
      expiresAt = Long.parseLong(entry.substring(split + 1));
    } catch (NumberFormatException exception) {
      this.passwordSessions.remove(player.getUniqueId());
      return false;
    }
    if (System.currentTimeMillis() > expiresAt) {
      this.passwordSessions.remove(player.getUniqueId());
      return false;
    }
    return entry.substring(0, split).equals(this.addressOf(player));
  }

  /** Called by the service after a successful password login. */
  public void rememberPasswordSession(AuthSession session) {
    long seconds = Math.max(0, this.config.password.sessionMinutes) * 60L;
    if (seconds <= 0) {
      this.passwordSessions.remove(session.getUuid());
      return;
    }
    Player player = resolvePlayer(session);
    long expiresAt = System.currentTimeMillis() + seconds * 1000L;
    this.passwordSessions.put(session.getUuid(), (player == null ? "" : this.addressOf(player)) + "|" + expiresAt);
  }

  public void forgetPasswordSession(UUID uuid) {
    this.passwordSessions.remove(uuid);
    this.transferTickets.remove(uuid);
  }

  public void pruneTransientState() {
    long now = System.currentTimeMillis();
    this.passwordSessions.entrySet().removeIf(entry -> {
      int split = entry.getValue().lastIndexOf('|');
      if (split <= 0) {
        return true;
      }
      try {
        return now > Long.parseLong(entry.getValue().substring(split + 1));
      } catch (NumberFormatException exception) {
        return true;
      }
    });
    this.transferTickets.entrySet().removeIf(entry -> now > entry.getValue().expiresAt);
  }

  private void transfer(Player player, InetSocketAddress target) {
    if (!player.isActive()) {
      return;
    }
    long ticketMillis = Math.max(5, this.config.password.transferTicketSeconds) * 1000L;
    TransferTicket ticket = new TransferTicket(System.currentTimeMillis() + ticketMillis, this.addressOf(player));
    this.transferTickets.put(player.getUniqueId(), ticket);
    if (this.config.debug) {
      this.logger.info("[trace] transfer ticket issued for {} (valid {} s)", player.getUsername(), ticketMillis / 1000);
    }
    try {
      player.transferToHost(target);
    } catch (Throwable throwable) {
      this.transferTickets.remove(player.getUniqueId(), ticket);
      this.logger.error("Unable to transfer {} to {} — falling back to LimboAPI", player.getUsername(), target, throwable);
      try {
        this.limboFactory.passLoginLimbo(player);
      } catch (Throwable inner) {
        this.logger.error("Unable to release {} either", player.getUsername(), inner);
      }
      return;
    }
    long timeout = Math.max(1000L, this.config.auth.transferReconnectTimeoutMillis);
    this.scheduler.runLater(() -> {
      if (player.isActive()) {
        this.logger.warn("[trace] {} did not reconnect within {} ms after the transfer — kicking so they can join again.",
            player.getUsername(), timeout);
        this.transferTickets.remove(player.getUniqueId());
        player.disconnect(Text.build(this.config.messages.transferKick, this.config, null));
      }
    }, timeout);
  }

  private InetSocketAddress transferTarget(Player player) {
    String configured = this.config.auth.transferAddress;
    if (configured != null && !configured.isBlank()) {
      InetSocketAddress parsed = parseAddress(configured.trim());
      if (parsed != null) {
        return parsed;
      }
      this.logger.warn("auth.transferAddress '{}' is not a valid host[:port] — using the player's own address",
          configured);
    }
    Optional<InetSocketAddress> virtualHost = player.getVirtualHost();
    if (virtualHost.isPresent()) {
      return virtualHost.get();
    }
    Optional<String> raw = player.getRawVirtualHost();
    if (raw.isPresent()) {
      InetSocketAddress parsed = parseAddress(raw.get().trim());
      if (parsed != null) {
        return parsed;
      }
    }
    InetSocketAddress fallback = parseAddress(
        this.config.auth.transferFallbackAddress == null ? "" : this.config.auth.transferFallbackAddress.trim());
    return fallback == null ? new InetSocketAddress("127.0.0.1", 25565) : fallback;
  }

  private static InetSocketAddress parseAddress(String value) {
    if (value == null || value.isEmpty()) {
      return null;
    }
    String host = value;
    int port = 25565;
    int colon = value.lastIndexOf(':');
    if (colon > 0 && value.indexOf(':') == colon) {
      try {
        port = Integer.parseInt(value.substring(colon + 1).trim());
      } catch (NumberFormatException exception) {
        return null;
      }
      host = value.substring(0, colon).trim();
    }
    if (host.isEmpty() || port <= 0 || port > 65535) {
      return null;
    }
    return InetSocketAddress.createUnresolved(host, port);
  }

  // --------------------------------------------------------------------- boss bar

  /** Updates the countdown bar once per second. */
  public void tickBossBars() {
    for (AuthSession session : this.sessions.all()) {
      Player player = resolvePlayer(session);
      if (session.isComplete() || player == null || !player.isActive()) {
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
        this.logger.debug("Unable to show the authorization boss bar to {}", session.getUsername(), throwable);
      }
    }
    this.bossBars.keySet().removeIf(uuid -> {
      if (this.sessions.byPlayer(uuid) != null) {
        return false;
      }
      BossBar bar = this.bossBars.get(uuid);
      Player player = this.server.getPlayer(uuid).orElse(null);
      if (bar != null && player != null) {
        player.hideBossBar(bar);
      }
      return true;
    });
  }

  private void hideBossBar(UUID uuid) {
    BossBar bar = this.bossBars.remove(uuid);
    if (bar == null) {
      return;
    }
    Player player = this.server.getPlayer(uuid).orElse(null);
    if (player != null) {
      player.hideBossBar(bar);
    }
  }

  // ---------------------------------------------------------------- backend bridge

  public void registerBridgeChannel() {
    if (!this.config.bridge.enabled) {
      return;
    }
    try {
      this.identityChannel = MinecraftChannelIdentifier.from(this.config.bridge.channel);
      this.server.getChannelRegistrar().register(this.identityChannel);
      this.logger.info("Backend bridge ready on channel '{}' (placeholders %{}_* on the backend).",
          this.config.bridge.channel, this.config.bridge.channel.split(":")[0]);
    } catch (Throwable throwable) {
      this.identityChannel = null;
      this.logger.warn("Unable to register the bridge channel '{}': {}",
          this.config.bridge.channel, throwable.toString());
    }
  }

  public void unregisterBridgeChannel() {
    if (this.identityChannel != null) {
      this.server.getChannelRegistrar().unregister(this.identityChannel);
      this.identityChannel = null;
    }
  }

  public void onPluginMessage(PluginMessageEvent event) {
    if (this.identityChannel == null || !this.identityChannel.equals(event.getIdentifier())) {
      return;
    }
    Player player = messagePlayer(event);
    if (player == null) {
      return;
    }
    event.setResult(PluginMessageEvent.ForwardResult.handled());
    String message = new String(event.getData(), StandardCharsets.UTF_8);
    if (message.startsWith("REQ")) {
      this.sendIdentity(player);
    }
  }

  private static Player messagePlayer(PluginMessageEvent event) {
    if (event.getSource() instanceof Player source) {
      return source;
    }
    if (event.getTarget() instanceof Player target) {
      return target;
    }
    return null;
  }

  public void sendIdentity(Player player) {
    if (this.identityChannel == null || player == null || !player.isActive()) {
      return;
    }
    LinkStore.Link link = this.links.getLink(player.getUniqueId());
    String nick = link == null ? null : Text.discordDisplay(link, "nickname", this.config);
    String username = link == null ? null : link.discordName;
    String global = link == null ? null : link.discordGlobal;
    String discordId = link == null ? null : link.discordId;
    boolean linked = discordId != null && !discordId.isBlank();
    String payload = String.join("\u0000",
        "ID",
        player.getUniqueId().toString(),
        linked ? "1" : "0",
        clean(nick),
        clean(username),
        clean(global),
        clean(discordId));
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    player.getCurrentServer().ifPresent(connection -> connection.sendPluginMessage(this.identityChannel, bytes));
  }

  public void scheduleIdentityPush(Player player) {
    if (this.identityChannel == null) {
      return;
    }
    this.sendIdentity(player);
    for (long delay : new long[] {1000L, 3000L, 7000L}) {
      this.scheduler.runLater(() -> this.sendIdentity(player), delay);
    }
  }

  private static String clean(String value) {
    return value == null ? "" : value.replace("\u0000", "");
  }

  // ------------------------------------------------------------------ TAB branding

  public void hookTabPlugin() {
    TabHook hook = new TabHook(this.server, this.logger, this.config);
    if (!hook.hook()) {
      return;
    }
    this.tabHook = hook;
    if (this.config.tab.registerTabPluginPlaceholders) {
      hook.registerPlaceholder("%discordauth_nick%", uuid -> this.discordValue(uuid, "nickname"));
      hook.registerPlaceholder("%discordauth_username%", uuid -> this.discordValue(uuid, "username"));
      hook.registerPlaceholder("%discordauth_global%", uuid -> this.discordValue(uuid, "global"));
      hook.registerPlaceholder("%discordauth_id%", uuid -> {
        LinkStore.Link link = this.links.getLink(uuid);
        return link == null || link.discordId == null ? "" : link.discordId;
      });
    }
    this.logger.info("TAB plugin detected: placeholders %discordauth_nick%, %discordauth_username%, "
        + "%discordauth_global%, %discordauth_id% are available.");
  }

  private String discordValue(UUID uuid, String source) {
    LinkStore.Link link = this.links.getLink(uuid);
    if (link == null) {
      return "";
    }
    String value = Text.discordDisplay(link, source, this.config);
    return value == null || value.isBlank() ? this.config.tab.unknownDiscord : value;
  }

  private boolean tabPluginOwnsTabList() {
    return this.tabHook != null && this.config.tab.skipWhenTabPluginInstalled;
  }

  public void scheduleTabUpdate(Player player) {
    if (!this.config.tab.enabled || this.tabPluginOwnsTabList()) {
      return;
    }
    this.applyTabName(player);
    for (long delay : new long[] {1000L, 3000L, 7000L, 15000L}) {
      this.scheduler.runLater(() -> this.applyTabName(player), delay);
    }
  }

  public void applyTabName(Player player) {
    if (player == null || !player.isActive()) {
      return;
    }
    LinkStore.Link link = this.links.getLink(player.getUniqueId());
    if (link == null) {
      return;
    }
    String resolved = Text.discordDisplay(link, this.config.tab.nameSource, this.config);
    this.warnAboutUnsupportedName(player, resolved);
    Component base = Text.tab(this.config.tab.format, player.getUsername(), link, this.config);
    String hover = this.config.tab.hover;
    Component display = hover == null || hover.isBlank()
        ? base
        : base.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
            Text.tab(hover, player.getUsername(), link, this.config)));
    player.getTabList().getEntry(player.getUniqueId()).ifPresentOrElse(
        entry -> entry.setDisplayName(display),
        () -> {
          if (this.config.debug) {
            this.logger.info("[trace] TAB entry for {} is not available yet", player.getUsername());
          }
        });
  }

  private void warnAboutUnsupportedName(Player player, String name) {
    if (name == null || !Text.hasUnsupportedChars(name) || !this.tabWarned.add(player.getUniqueId())) {
      return;
    }
    this.logger.warn("The Discord name of {} contains characters Minecraft cannot render ({}). "
            + "Set tab.nameSource to \"global\" or tab.stripUnsupported to true to avoid empty boxes.",
        player.getUsername(), Text.unsupportedCodePoints(name));
  }

  /** Picks up the current Discord nick/global name at most every {@code identityRefreshMinutes}. */
  public void refreshDiscordIdentity(UUID uuid) {
    LinkStore.Link link = this.links.getLink(uuid);
    if (link == null || this.bot == null || link.discordId == null || link.discordId.isBlank()) {
      return;
    }
    if (!this.links.needsIdentityRefresh(uuid, this.config.discord.identityRefreshMinutes)) {
      return;
    }
    this.links.touchIdentityRefresh(uuid);
    String discordId = link.discordId;
    this.bot.resolveMember(discordId, member -> {
      this.links.setDiscordIdentity(uuid, member.username, member.nick, member.global);
      this.identityChanged(uuid);
    });
  }

  /**
   * Reflective TAB (NEZNAMY) hook: registers {@code %discordauth_*%} player placeholders without a
   * compile-time dependency on TAB.
   */
  private static final class TabHook {

    private final ProxyServer server;
    private final Logger logger;
    private final Config config;

    private Object placeholderManager;
    private Method registerPlayerPlaceholder;
    private Method tabPlayerGetUniqueId;

    private TabHook(ProxyServer server, Logger logger, Config config) {
      this.server = server;
      this.logger = logger;
      this.config = config;
    }

    private boolean hook() {
      try {
        Object tabInstance = this.server.getPluginManager()
            .getPlugin("TAB")
            .flatMap(container -> container.getInstance().map(loaded -> (Object) loaded))
            .orElse(null);
        if (tabInstance == null) {
          return false;
        }
        ClassLoader loader = tabInstance.getClass().getClassLoader();
        Class<?> api = Class.forName("me.neznamy.tab.api.TabAPI", true, loader);
        Object tabApi = api.getMethod("getInstance").invoke(null);
        if (tabApi == null) {
          return false;
        }
        Object manager = api.getMethod("getPlaceholderManager").invoke(tabApi);
        Class<?> managerClass = Class.forName("me.neznamy.tab.api.placeholder.PlaceholderManager", true, loader);
        Class<?> tabPlayerClass = Class.forName("me.neznamy.tab.api.TabPlayer", true, loader);
        this.registerPlayerPlaceholder =
            managerClass.getMethod("registerPlayerPlaceholder", String.class, int.class, Function.class);
        this.tabPlayerGetUniqueId = tabPlayerClass.getMethod("getUniqueId");
        this.placeholderManager = manager;
        return true;
      } catch (Throwable throwable) {
        this.logger.warn("TAB plugin was found but its API could not be hooked: {}", throwable.toString());
        return false;
      }
    }

    private void registerPlaceholder(String identifier, Function<UUID, String> value) {
      if (this.registerPlayerPlaceholder == null || this.placeholderManager == null) {
        return;
      }
      int refresh = Math.max(50, this.config.tab.tabPluginPlaceholderRefreshMillis / 50 * 50);
      Function<Object, String> function = tabPlayer -> {
        try {
          Object uuid = this.tabPlayerGetUniqueId.invoke(tabPlayer);
          return uuid instanceof UUID ? value.apply((UUID) uuid) : "";
        } catch (Throwable throwable) {
          return "";
        }
      };
      try {
        this.registerPlayerPlaceholder.invoke(this.placeholderManager, identifier, refresh, function);
      } catch (Throwable throwable) {
        this.logger.warn("Unable to register the TAB placeholder {}: {}", identifier, throwable.toString());
      }
    }
  }
}
