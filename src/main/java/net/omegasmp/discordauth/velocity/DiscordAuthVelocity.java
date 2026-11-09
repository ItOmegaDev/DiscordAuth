package net.omegasmp.discordauth.velocity;

import com.google.inject.Inject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import net.elytrium.limboapi.api.command.LimboCommandMeta;
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent;
import net.elytrium.limboapi.api.player.GameMode;
import net.omegasmp.discordauth.core.AccountStore;
import net.omegasmp.discordauth.core.AuthService;
import net.omegasmp.discordauth.core.AuthSession;
import net.omegasmp.discordauth.core.BanStore;
import net.omegasmp.discordauth.core.Config;
import net.omegasmp.discordauth.core.LinkStore;
import net.omegasmp.discordauth.core.Secrets;
import net.omegasmp.discordauth.core.SessionRegistry;
import net.omegasmp.discordauth.core.StorageException;
import net.omegasmp.discordauth.core.Text;
import net.omegasmp.discordauth.discord.DiscordBot;
import org.slf4j.Logger;

/**
 * Velocity entry point of the unified DiscordAuth jar.
 *
 * <p>The same jar also contains a Paper entry point; Velocity only loads this class, Paper only
 * loads {@code net.omegasmp.discordauth.paper.DiscordAuthPaper}.</p>
 */
@Plugin(
    id = "discord-auth",
    name = "DiscordAuth",
    version = "2.0.0",
    description = "Unified Discord + password authentication for Velocity and Paper",
    authors = {"OmegaSMP"},
    dependencies = {@Dependency(id = "limboapi", optional = true)}
)
public class DiscordAuthVelocity {

  /** The limbo commands only exist so the client sees them; every command is handled in chat. */
  private static final SimpleCommand LIMBO_NOOP = invocation -> {
  };

  private final ProxyServer server;
  private final Logger logger;
  private final Path dataDirectory;

  private Config config;
  private AccountStore accounts;
  private LinkStore links;
  private BanStore bans;
  private SessionRegistry sessions;
  private LimboFactory limboFactory;
  private Limbo limbo;
  private AuthService service;
  private VelocityHost host;
  private DiscordBot bot;

  @Inject
  public DiscordAuthVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
    this.server = server;
    this.logger = logger;
    this.dataDirectory = dataDirectory;
  }

  @Subscribe
  public void onProxyInitialize(ProxyInitializeEvent event) {
    this.config = Config.load(this.dataDirectory, this.logger);
    if ("paper".equals(this.config.platform)) {
      this.logger.error("config.yml says platform=paper, but this is a Velocity proxy — disabling. "
          + "Set platform to \"auto\" or \"velocity\".");
      return;
    }
    if ("bridge".equals(this.config.role)) {
      this.logger.warn("role=bridge is Paper-only; continuing as a full Velocity auth plugin.");
    }

    try {
      this.links = new LinkStore(this.dataDirectory.resolve("links.json"), this.logger);
      this.accounts = new AccountStore(this.dataDirectory.resolve("accounts.json"), this.logger);
      this.bans = new BanStore(this.dataDirectory.resolve("bans.json"), this.logger);
    } catch (StorageException exception) {
      this.logger.error("A data file could not be read — disabling so nobody can bypass authentication. "
          + "Fix or restore the file and restart.", exception);
      return;
    }
    this.sessions = new SessionRegistry();

    Optional<PluginContainer> container = this.server.getPluginManager().getPlugin("limboapi");
    if (container.isEmpty()) {
      this.logger.error("LimboAPI was not found. DiscordAuth cannot create a limbo — verification is disabled.");
      return;
    }
    try {
      this.limboFactory = (LimboFactory) container.get().getInstance().orElseThrow();
    } catch (Throwable throwable) {
      this.logger.error("Unable to hook into LimboAPI", throwable);
      return;
    }

    Dimension dimension;
    try {
      dimension = Dimension.valueOf(this.config.limbo.dimension.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      this.logger.warn("Unknown limbo dimension '{}', falling back to THE_END", this.config.limbo.dimension);
      dimension = Dimension.THE_END;
    }
    VirtualWorld world = this.limboFactory.createVirtualWorld(
        dimension,
        this.config.limbo.x, this.config.limbo.y, this.config.limbo.z,
        this.config.limbo.yaw, this.config.limbo.pitch);
    this.limbo = this.limboFactory.createLimbo(world)
        .setName("DiscordAuth")
        .setGameMode(GameMode.ADVENTURE)
        .setReducedDebugInfo(true)
        .setViewDistance(4)
        .setSimulationDistance(4);

    this.host = new VelocityHost(this.server, this.logger, this.config, this.accounts, this.links,
        this.bans, this.sessions, new VelocityScheduler(this.server, this), this.limbo,
        this.limboFactory);
    this.service = new AuthService(this.host);
    this.host.setService(this.service);

    this.registerLimboCommands();
    this.registerLinkCommand();

    List<String> adminNames = this.config.commands.admin;
    CommandMeta meta = this.server.getCommandManager().metaBuilder(adminNames.get(0))
        .aliases(adminNames.subList(1, adminNames.size()).toArray(new String[0]))
        .plugin(this)
        .build();
    this.server.getCommandManager().register(meta, new VelocityAdminCommand(this.host));

    Secrets secrets = Secrets.load(this.dataDirectory, this.logger);
    this.bot = new DiscordBot(this.service, this.config,
        new VelocityScheduler(this.server, this), this.logger, secrets.discordToken);
    this.host.setBot(this.bot);
    this.bot.start();

    this.service.start();
    this.server.getScheduler().buildTask(this, this.host::tickBossBars).repeat(1L, java.util.concurrent.TimeUnit.SECONDS).schedule();

    this.checkProxyConfiguration();
    this.host.hookTabPlugin();
    this.host.registerBridgeChannel();

    if (this.config.auth.testMode) {
      this.logger.warn("⚠️ TEST MODE is ON — ALL authentication (Discord + password) is DISABLED! "
          + "Anyone can join with any nickname. Disable testMode in config.yml before going live!");
    }
    this.logger.info("DiscordAuth 2.0.0 enabled on Velocity ({} linked account(s), {} password(s)). "
            + "Discord first on a new account, then the password.",
        this.links.size(), this.accounts.size());
  }

  @Subscribe
  public void onProxyShutdown(ProxyShutdownEvent event) {
    if (this.bot != null) {
      this.bot.stop();
    }
    if (this.host != null) {
      this.host.unregisterBridgeChannel();
    }
  }

  /**
   * Puts the limbo commands into the client's command list (LimboAPI sends them inside the
   * {@code AvailableCommands} packet), so they are highlighted and can be tab-completed.
   */
  private void registerLimboCommands() {
    Object plugin = this;
    PluginContainer container = this.server.getPluginManager().getPlugin("discord-auth").orElse(null);
    if (container != null && container.getInstance().isPresent()) {
      plugin = container.getInstance().get();
    }

    CommandNode<CommandSource> password = RequiredArgumentBuilder
        .<CommandSource, String>argument("password", StringArgumentType.word())
        .build();
    CommandNode<CommandSource> passwordTwice = RequiredArgumentBuilder
        .<CommandSource, String>argument("password", StringArgumentType.word())
        .then(RequiredArgumentBuilder.<CommandSource, String>argument("confirm", StringArgumentType.word()))
        .build();

    this.limbo.registerCommand(
        new LimboCommandMeta(this.config.commands.login, List.of(password), plugin), LIMBO_NOOP);
    this.limbo.registerCommand(
        new LimboCommandMeta(this.config.commands.register, List.of(passwordTwice), plugin), LIMBO_NOOP);
  }

  /** {@code /discordlink}: an already logged-in player links the current account from Discord. */
  private void registerLinkCommand() {
    List<String> names = this.config.commands.link;
    CommandMeta meta = this.server.getCommandManager().metaBuilder(names.get(0))
        .aliases(names.subList(1, names.size()).toArray(new String[0]))
        .plugin(this)
        .build();
    this.server.getCommandManager().register(meta, (SimpleCommand) invocation -> this.startLink(invocation.source()));
  }

  private void startLink(CommandSource source) {
    if (!(source instanceof Player player)) {
      source.sendMessage(Text.component(this.config.messages.discordLinkPlayerOnly));
      return;
    }
    UUID uuid = player.getUniqueId();
    if (this.links.isLinked(uuid)) {
      source.sendMessage(Text.component(Text.render(
          this.config.messages.discordLinkAlready, this.config, player.getUsername(), null, 0)));
      return;
    }
    for (AuthSession pending : this.sessions.allLinks()) {
      if (pending.getUuid().equals(uuid)) {
        this.sessions.removeLink(pending.getCode());
      }
    }
    AuthSession session = this.service.createLinkSession(uuid, player.getUsername());
    session.setHandle(player);
    player.sendMessage(Text.build(this.config.messages.discordLinkPrompt, this.config, session));
    this.logger.info("{} requested a Discord link code from the game.", player.getUsername());
  }

  @Subscribe
  public void onLoginLimboRegister(LoginLimboRegisterEvent event) {
    if (this.limbo == null) {
      return;
    }
    Player player = event.getPlayer();
    String ip = this.host.addressOf(player);
    // IP rate limiting: block IPs that connect too frequently
    if (this.host.isIpBlocked(ip)) {
      player.disconnect(Text.component("&c&lBlocked\n&7Your IP is temporarily blocked due to suspicious activity."));
      this.logger.warn("{} ({}) blocked — IP rate limit exceeded.", player.getUsername(), ip);
      return;
    }
    if (this.host.recordConnection(ip)) {
      player.disconnect(Text.component("&c&lBlocked\n&7Your IP is temporarily blocked due to suspicious activity."));
      this.logger.warn("{} ({}) blocked — too many connections from this IP.", player.getUsername(), ip);
      return;
    }
    BanStore.Ban ban = this.bans.find(player.getUniqueId(), player.getUsername());
    if (ban != null) {
      player.disconnect(this.service.banComponent(ban));
      this.logger.info("{} tried to join but is banned ({}).", player.getUsername(), ban.reason);
      return;
    }

    boolean bedrock = this.host.isBedrockName(player.getUsername());
    boolean needsDiscord = !this.config.auth.testMode
        && !this.links.isLinked(player.getUniqueId()) && !this.host.bypassesDiscord(player);
    boolean needsPassword = this.host.needsPassword(player, bedrock);
    if (!needsDiscord && !needsPassword) {
      return;
    }
    event.addOnJoinCallback(() -> this.holdInLimbo(player, needsDiscord, needsPassword));
  }

  private void holdInLimbo(Player player, boolean needsDiscord, boolean needsPassword) {
    if (player == null) {
      return;
    }
    AuthSession previous = this.sessions.byPlayer(player.getUniqueId());
    if (previous != null) {
      this.service.dropSession(previous);
      this.host.unregisterHandler(player.getUniqueId(), null);
    }
    AuthSession session = this.service.createSession(
        player.getUniqueId(), player.getUsername(), needsDiscord, needsPassword,
        this.host.addressOf(player));
    session.setHandle(player);
    this.limbo.spawnPlayer(player, new VelocityLimboHandler(this.host, session));
    this.logger.info("{} is not fully authorized — holding in the limbo (discord={}, password={}{}).",
        player.getUsername(), needsDiscord, needsPassword,
        needsDiscord ? ", code " + session.getCode() : "");
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    UUID uuid = event.getPlayer().getUniqueId();
    AuthSession session = this.sessions.byPlayer(uuid);
    if (session != null) {
      this.service.dropSession(session);
    }
    this.host.unregisterHandler(uuid, null);
  }

  @Subscribe
  public void onPostLogin(PostLoginEvent event) {
    BanStore.Ban ban = this.bans.find(event.getPlayer().getUniqueId(), event.getPlayer().getUsername());
    if (ban != null) {
      event.getPlayer().disconnect(this.service.banComponent(ban));
      this.logger.info("{} tried to join but is banned ({}).", event.getPlayer().getUsername(), ban.reason);
      return;
    }
    if (this.config.debug) {
      this.logger.info("[trace] {} finished the login phase.", event.getPlayer().getUsername());
    }
  }

  @Subscribe
  public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
    if (event.getInitialServer().isEmpty()) {
      Optional<RegisteredServer> main = this.server.getServer(this.config.auth.mainServer);
      if (main.isPresent()) {
        event.setInitialServer(main.get());
      } else {
        this.logger.warn("{} has no initial server and '{}' is not registered in velocity.toml.",
            event.getPlayer().getUsername(), this.config.auth.mainServer);
      }
    }
  }

  @Subscribe
  public void onServerConnected(ServerConnectedEvent event) {
    this.host.scheduleTabUpdate(event.getPlayer());
    this.host.scheduleIdentityPush(event.getPlayer());
    this.host.refreshDiscordIdentity(event.getPlayer().getUniqueId());
  }

  @Subscribe
  public void onPluginMessage(PluginMessageEvent event) {
    if (this.host != null) {
      this.host.onPluginMessage(event);
    }
  }

  @Subscribe
  public void onKickedFromServer(KickedFromServerEvent event) {
    if (this.config.debug) {
      this.logger.info("[trace] {} was kicked from '{}' (duringConnect={}): {}",
          event.getPlayer().getUsername(),
          event.getServer().getServerInfo().getName(),
          event.kickedDuringServerConnect(),
          event.getServerKickReason().map(Text.SERIALIZER::serialize).orElse(""));
    }
  }

  /** Makes sure the proxy has the two flags the transfer release depends on. */
  private void checkProxyConfiguration() {
    if (this.server.getConfiguration().isOnlineMode()) {
      this.logger.info("Proxy: online-mode=true — Mojang checks every nick{}.",
          this.config.password.enabled && this.config.password.requireWhenOnlineMode
              ? ", plus the password step" : "");
    } else if (!this.config.password.enabled) {
      this.logger.warn("Proxy: online-mode=false and password.enabled=false — ANY player can take ANY nick! "
          + "Turn passwords on in config.json.");
    } else {
      this.logger.info("Proxy: online-mode=false — the password is the only nickname gate.");
    }
    if (!this.config.auth.transferOnVerify) {
      return;
    }
    Path toml = Paths.get("velocity.toml");
    if (!Files.isRegularFile(toml)) {
      return;
    }
    try {
      String content = Files.readString(toml, StandardCharsets.UTF_8);
      List<String> missing = new ArrayList<>();
      if (!content.matches("(?s).*accepts-transfers\\s*=\\s*true.*")) {
        missing.add("accepts-transfers = true");
      }
      if (!content.matches("(?s).*kick-existing-players\\s*=\\s*true.*")) {
        missing.add("kick-existing-players = true");
      }
      if (!missing.isEmpty()) {
        this.logger.warn("velocity.toml is missing {}. Without it the player would stay in the limbo "
            + "after authorization — add it and restart the proxy.", String.join(" and ", missing));
      }
    } catch (Exception exception) {
      this.logger.debug("Unable to check velocity.toml", exception);
    }
  }
}
