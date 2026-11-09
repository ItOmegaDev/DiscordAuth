package net.omegasmp.discordauth.paper;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * Paper entry point of the unified DiscordAuth jar.
 *
 * <p>{@code role=auth}: the plugin runs the full authentication flow itself (used when the server
 * is exposed directly). {@code role=bridge}: the server only asks the proxy for Discord identities
 * and exposes PlaceholderAPI placeholders — nothing is authenticated here.</p>
 */
public final class DiscordAuthPaper extends JavaPlugin implements Listener {

  private Config config;
  private AccountStore accounts;
  private LinkStore links;
  private BanStore bans;
  private SessionRegistry sessions;
  private PaperScheduler scheduler;
  private AuthService service;
  private PaperHost host;
  private PaperBridge bridge;
  private DiscordBot bot;
  private Logger logger;
  private BukkitTask bossBarTask;
  private BukkitTask housekeepingTask;

  @Override
  public void onEnable() {
    this.logger = this.getSLF4JLogger();
    Path dataDirectory = this.getDataFolder().toPath();

    this.config = Config.load(dataDirectory, this.logger);
    if ("velocity".equals(this.config.platform)) {
      this.logger.error("config.yml says platform=velocity, but this is a Paper server — disabling. "
          + "Set platform to \"auto\" or \"paper\".");
      Bukkit.getPluginManager().disablePlugin(this);
      return;
    }

    try {
      this.links = new LinkStore(dataDirectory.resolve("links.json"), this.logger);
      this.accounts = new AccountStore(dataDirectory.resolve("accounts.json"), this.logger);
      this.bans = new BanStore(dataDirectory.resolve("bans.json"), this.logger);
    } catch (StorageException exception) {
      this.logger.error("A data file could not be read — disabling so nobody can bypass authentication. "
          + "Fix or restore the file and restart.", exception);
      Bukkit.getPluginManager().disablePlugin(this);
      return;
    }
    this.sessions = new SessionRegistry();
    this.scheduler = new PaperScheduler(this);

    if ("bridge".equals(this.config.role)) {
      this.enableBridge();
      return;
    }
    this.enableAuth();
  }

  // ------------------------------------------------------------------ bridge role

  private void enableBridge() {
    this.bridge = new PaperBridge(this, this.config, this.logger);
    this.bridge.register();
    this.registerPlaceholders(uuid -> this.bridge.identity(uuid));
    this.logger.info("DiscordAuth {} enabled as a BRIDGE ({} -> PlaceholderAPI only, no login here).",
        this.getPluginMeta().getVersion(), this.config.bridge.channel);
  }

  // -------------------------------------------------------------------- auth role

  private void enableAuth() {
    this.host = new PaperHost(this, this.logger, this.config, this.accounts, this.links,
        this.bans, this.sessions, this.scheduler);
    this.service = new AuthService(this.host);
    this.host.setService(this.service);
    this.host.setBridge(null);

    Secrets secrets = Secrets.load(this.getDataFolder().toPath(), this.logger);
    this.bot = new DiscordBot(this.service, this.config, this.scheduler, this.logger, secrets.discordToken);
    this.host.setBot(this.bot);
    this.bot.start();

    this.service.start();
    this.registerPlaceholders(uuid -> identityOf(uuid));
    this.bossBarTask = this.scheduler.repeatingTask(this.host::tickBossBars, 1000L);
    this.housekeepingTask = this.scheduler.repeatingAsyncTask(() -> { }, 60_000L);

    Bukkit.getPluginManager().registerEvents(this, this);
    Bukkit.getPluginManager().registerEvents(new PaperAuthListener(this, this.host), this);
    new PaperCommands(this, this.host).register();

    if (this.config.auth.testMode) {
      this.logger.warn("⚠️ TEST MODE is ON — ALL authentication (Discord + password) is DISABLED! "
          + "Anyone can join with any nickname. Disable testMode in config.yml before going live!");
    }
    this.logger.info("DiscordAuth {} enabled on Paper in AUTH mode ({} linked account(s), {} password(s)).",
        this.getPluginMeta().getVersion(), this.links.size(), this.accounts.size());
  }

  private PaperIdentity identityOf(UUID uuid) {
    LinkStore.Link link = this.links.getLink(uuid);
    if (link == null || link.discordId == null || link.discordId.isBlank()) {
      return PaperIdentity.NONE;
    }
    return new PaperIdentity(true, link.discordNick, link.discordName, link.discordGlobal, link.discordId);
  }

  private void registerPlaceholders(java.util.function.Function<UUID, PaperIdentity> identities) {
    if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
      this.logger.info("PlaceholderAPI is not installed — %{}_* placeholders are not available.",
          this.config.bridge.placeholderPrefix);
      return;
    }
    try {
      new PaperPlaceholders(this, this.config, identities).register();
    } catch (Throwable throwable) {
      this.logger.warn("Unable to register the PlaceholderAPI expansion", throwable);
    }
  }

  // -------------------------------------------------------------------- listeners

  /** Holds the player after join when authentication is still pending. */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onJoin(PlayerJoinEvent event) {
    if (this.host == null) {
      return;
    }
    Player player = event.getPlayer();
    this.host.hideHeldFrom(player);
    this.host.scheduleTabUpdate(player);
    this.host.refreshDiscordIdentity(player.getUniqueId());
    this.host.dialogs().show(this.sessions.byPlayer(player.getUniqueId()));
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    if (this.host == null) {
      return;
    }
    UUID uuid = event.getPlayer().getUniqueId();
    AuthSession session = this.sessions.byPlayer(uuid);
    if (session != null) {
      this.service.dropSession(session);
    }
    this.host.forget(uuid);
  }

  // ---------------------------------------------------------------------- lifecycle

  @Override
  public void onDisable() {
    if (this.bot != null) {
      this.bot.stop();
    }
    if (this.bridge != null) {
      this.bridge.unregister();
    }
    if (this.bossBarTask != null) {
      this.bossBarTask.cancel();
    }
    if (this.housekeepingTask != null) {
      this.housekeepingTask.cancel();
    }
  }

  // ----------------------------------------------------------------------- getters

  public Config config() {
    return this.config;
  }

  public PaperHost host() {
    return this.host;
  }

  public AuthService service() {
    return this.service;
  }

  public AccountStore accounts() {
    return this.accounts;
  }

  public LinkStore links() {
    return this.links;
  }

  public BanStore bans() {
    return this.bans;
  }

  public SessionRegistry sessions() {
    return this.sessions;
  }

  public PaperScheduler scheduler() {
    return this.scheduler;
  }
}
