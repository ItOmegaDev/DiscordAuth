package net.omegasmp.discordauth.discord;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.omegasmp.discordauth.core.AuthService;
import net.omegasmp.discordauth.core.BanStore;
import net.omegasmp.discordauth.core.Config;
import net.omegasmp.discordauth.core.DurationParser;
import net.omegasmp.discordauth.core.LinkStore;
import net.omegasmp.discordauth.core.Scheduler;
import net.omegasmp.discordauth.core.Text;
import org.slf4j.Logger;

/**
 * The JDA bot shared by both platforms.
 *
 * <p>Commands: {@code /verify <code>} (link a Minecraft account), {@code /account},
 * {@code /password}, {@code /unlink}, {@code /password-reset <player>} and the moderation
 * commands {@code /ban}, {@code /tempban}, {@code /unban}, {@code /kick}, {@code /banlist}.</p>
 *
 * <p>Passwords are never typed into the Minecraft chat: they go through a Discord modal and every
 * reply is ephemeral (only the caller sees it).</p>
 */
public class DiscordBot extends ListenerAdapter {

  private static final String OPTION_CODE = "code";
  private static final String OPTION_PLAYER = "player";
  private static final String OPTION_REASON = "reason";
  private static final String OPTION_DURATION = "duration";

  private static final String MODAL_PASSWORD = "da_password";
  private static final String MODAL_UNLINK = "da_unlink";
  private static final String MODAL_RESET_PREFIX = "da_reset:";
  private static final String BUTTON_BLOCK_IP = "da_blockip:";
  private static final String INPUT_CURRENT = "current";
  private static final String INPUT_NEW = "new";
  private static final String INPUT_CONFIRM = "confirm";
  private static final String INPUT_NICK = "nick";

  /** Discord's limit for a modal id — {@code da_reset:} + a 16 character nick still fits. */
  private static final int MAX_MODAL_ID = 32;
  /** Attempts are forgotten after this long so the map cannot grow forever. */
  private static final long ATTEMPT_TTL_MILLIS = 3_600_000L;

  private static final class Attempts {

    private int count;
    private long blockedUntil;
    private long touchedAt;
  }

  private final AuthService service;
  private final Config config;
  private final Scheduler scheduler;
  private final Logger logger;
  private final String token;
  private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
  /** Last time a "commands only" notice was posted per channel, used to throttle spam. */
  private final Map<String, Long> lastCommandsOnlyNotice = new ConcurrentHashMap<>();
  /** Monitors the Discord gateway and alerts admins when the bot stays offline. */
  private ScheduledExecutorService alertMonitor;
  private volatile long offlineSince = 0L;
  private volatile boolean offlineAlertSent = false;
  private volatile JDA jda;

  public DiscordBot(AuthService service, Config config, Scheduler scheduler, Logger logger, String token) {
    this.service = service;
    this.config = config;
    this.scheduler = scheduler;
    this.logger = logger;
    this.token = token;
  }

  /** Starts JDA off the main thread: {@code build()} blocks until the gateway session is up. */
  public void start() {
    if (this.token == null || this.token.isBlank()) {
      this.logger.warn("No Discord token in secrets.json (or DISCORD_TOKEN) — Discord verification is disabled.");
      return;
    }
    this.scheduler.runAsync(() -> {
      try {
        this.jda = JDABuilder.createLight(this.token)
            .setActivity(Activity.playing("Minecraft | /verify"))
            .enableIntents(GatewayIntent.GUILD_MESSAGES)
            .addEventListeners(this)
            .build();
        this.logger.info("Connecting to Discord…");
      } catch (Throwable throwable) {
        this.logger.error("Unable to start the Discord bot", throwable);
        this.scheduleOfflineAlert(5_000L);
      }
    });
  }

  public void stop() {
    ScheduledExecutorService monitor = this.alertMonitor;
    this.alertMonitor = null;
    if (monitor != null) {
      monitor.shutdownNow();
    }
    JDA current = this.jda;
    this.jda = null;
    if (current != null) {
      try {
        current.shutdownNow();
      } catch (Throwable throwable) {
        this.logger.debug("Unable to shut the Discord bot down cleanly", throwable);
      }
    }
  }

  private void startAlertMonitor() {
    if (this.alertMonitor != null) {
      return;
    }
    this.alertMonitor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "discord-alert-monitor");
      thread.setDaemon(true);
      return thread;
    });
    this.alertMonitor.scheduleAtFixedRate(this::checkBotHealth, 60, 60, TimeUnit.SECONDS);
  }

  private void scheduleOfflineAlert(long delayMillis) {
    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "discord-alert-once");
      thread.setDaemon(true);
      return thread;
    });
    executor.schedule(() -> {
      this.checkBotHealth();
      executor.shutdown();
    }, delayMillis, TimeUnit.MILLISECONDS);
  }

  private void checkBotHealth() {
    if (this.isReady()) {
      this.offlineSince = 0L;
      if (this.offlineAlertSent) {
        this.offlineAlertSent = false;
        this.sendAdminAlert("✅ Discord bot is back online.");
      }
      return;
    }
    long now = System.currentTimeMillis();
    if (this.offlineSince == 0L) {
      this.offlineSince = now;
    }
    if (now - this.offlineSince < 120_000L || this.offlineAlertSent) {
      return;
    }
    this.offlineAlertSent = true;
    this.sendAdminAlert("⚠️ Discord bot has been offline for more than 2 minutes. Verification is unavailable.");
  }

  private void sendAdminAlert(String text) {
    JDA current = this.jda;
    List<String> admins = this.config.discord.adminUserIds;
    if (admins == null || admins.isEmpty()) {
      this.logger.warn("Bot offline alert could not be delivered: no adminUserIds configured. {}", text);
      return;
    }
    for (String adminId : admins) {
      if (adminId == null || adminId.isBlank()) {
        continue;
      }
      if (current == null) {
        this.logger.warn("Bot offline alert (no JDA): {}", text);
        continue;
      }
      current.retrieveUserById(adminId).queue(
          user -> user.openPrivateChannel().queue(
              channel -> channel.sendMessage(text).queue(
                  success -> this.logger.info("Admin alert sent to {}", adminId),
                  error -> this.logger.warn("Unable to send admin alert to {}: {}", adminId, error.getMessage())),
              error -> this.logger.warn("Unable to open DM for {}: {}", adminId, error.getMessage())),
          error -> this.logger.warn("Unable to retrieve admin {}: {}", adminId, error.getMessage()));
    }
  }

  public boolean isReady() {
    JDA current = this.jda;
    return current != null && current.getStatus() == JDA.Status.CONNECTED;
  }

  /**
   * Slash commands registered in the guild. Player commands are visible to everyone; staff
   * commands are hidden from members without Administrator, so the command picker of a normal
   * player only lists /verify, /account, /password and /unlink.
   */
  private static List<CommandData> commands() {
    DefaultMemberPermissions staffOnly = DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR);
    return List.of(
        Commands.slash("verify", "Link your Minecraft account")
            .addOption(OptionType.STRING, OPTION_CODE, "The code shown in Minecraft", true),
        Commands.slash("account", "Show your linked Minecraft accounts"),
        Commands.slash("password", "Change a linked account's Minecraft password")
            .addOption(OptionType.STRING, OPTION_PLAYER, "Which linked account (needed if you have two)", false),
        Commands.slash("unlink", "Unlink your Minecraft account from Discord"),
        Commands.slash("password-reset", "Set a new password for a player (administrators)")
            .setDefaultPermissions(staffOnly)
            .addOption(OptionType.STRING, OPTION_PLAYER, "Minecraft nickname", true),
        Commands.slash("ban", "Ban a player permanently (administrators)")
            .setDefaultPermissions(staffOnly)
            .addOption(OptionType.STRING, OPTION_PLAYER, "Minecraft nickname", true)
            .addOption(OptionType.STRING, OPTION_REASON, "Reason", false),
        Commands.slash("tempban", "Ban a player for a period of time (administrators)")
            .setDefaultPermissions(staffOnly)
            .addOption(OptionType.STRING, OPTION_PLAYER, "Minecraft nickname", true)
            .addOption(OptionType.STRING, OPTION_DURATION, "Duration, e.g. 30m, 12h, 7d, 2w", true)
            .addOption(OptionType.STRING, OPTION_REASON, "Reason", false),
        Commands.slash("unban", "Lift a ban (administrators)")
            .setDefaultPermissions(staffOnly)
            .addOption(OptionType.STRING, OPTION_PLAYER, "Minecraft nickname", true),
        Commands.slash("kick", "Kick a player (administrators)")
            .setDefaultPermissions(staffOnly)
            .addOption(OptionType.STRING, OPTION_PLAYER, "Minecraft nickname", true)
            .addOption(OptionType.STRING, OPTION_REASON, "Reason", false),
        Commands.slash("banlist", "List active bans (administrators)")
            .setDefaultPermissions(staffOnly));
  }

  @Override
  public void onReady(ReadyEvent event) {
    this.logger.info("Discord bot logged in as {} ({} guilds).",
        event.getJDA().getSelfUser().getName(), event.getJDA().getGuilds().size());
    this.logger.info("Commands-only channels: {}", this.config.discord.commandsOnlyChannelIds);
    String guildId = this.config.discord.guildId;
    if (guildId == null || guildId.isBlank()) {
      this.jda.updateCommands()
          .addCommands(commands())
          .queue(list -> this.logger.info("Registered {} global slash command(s).", list.size()),
              error -> this.logger.error("Unable to register global slash commands", error));
      this.startAlertMonitor();
      return;
    }
    Guild guild = this.jda.getGuildById(guildId);
    if (guild == null) {
      this.logger.error("Bot is not a member of guild {} — invite it and restart.", guildId);
      return;
    }
    guild.updateCommands()
        .addCommands(commands())
        .queue(list -> this.logger.info("Registered {} slash command(s) in guild {}.", list.size(), guild.getName()),
            error -> this.logger.error("Unable to register slash commands in guild {}", guildId, error));
    this.startAlertMonitor();
  }

  // --------------------------------------------------------------- message guard

  /**
   * Deletes ordinary messages in channels configured as commands-only, including messages written
   * by staff and by the guild owner, so the channel really stays a command surface. Bots and
   * webhooks are left alone (the bot itself posts the notice below). Slash commands are
   * interactions, not messages, so they are unaffected by this guard.
   */
  @Override
  public void onMessageReceived(MessageReceivedEvent event) {
    if (event.getAuthor().isBot() || event.isWebhookMessage()) {
      return;
    }
    String channelId = event.getChannel().getId();
    if (!this.config.discord.commandsOnlyChannelIds.contains(channelId)) {
      return;
    }
    event.getMessage().delete().queue(null,
        error -> this.logger.debug("Unable to delete a commands-only message: {}", error.getMessage()));
    // Throttle the notice so a spammer cannot flood the channel (and the bot's rate limits).
    long now = System.currentTimeMillis();
    Long last = this.lastCommandsOnlyNotice.get(channelId);
    if (last == null || now - last > 15_000L) {
      this.lastCommandsOnlyNotice.put(channelId, now);
      event.getChannel().sendMessage(
              "❌ This channel is for commands only. Use `/verify`, `/account`, `/password` or `/unlink`.")
          .queue(message -> message.delete().queueAfter(5, TimeUnit.SECONDS),
              error -> this.logger.debug("Unable to send the commands-only notice: {}", error.getMessage()));
    }
  }

  // --------------------------------------------------------------- slash commands

  @Override
  public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
    switch (event.getName()) {
      case "verify" -> this.verify(event);
      case "account" -> this.account(event);
      case "password" -> this.passwordModal(event);
      case "unlink" -> this.unlinkModal(event);
      case "password-reset" -> this.resetModal(event);
      case "ban" -> this.ban(event, false);
      case "tempban" -> this.ban(event, true);
      case "unban" -> this.unban(event);
      case "kick" -> this.kick(event);
      case "banlist" -> this.banList(event);
      default -> {
      }
    }
  }

  private void verify(SlashCommandInteractionEvent event) {
    if (!this.guildAllowed(event)) {
      return;
    }
    String userId = event.getUser().getId();
    long blockedSeconds = this.blockedSeconds(userId);
    if (blockedSeconds > 0) {
      event.reply(Text.render(this.config.messages.discordRateLimited, this.config, "", "", blockedSeconds))
          .setEphemeral(true).queue();
      return;
    }

    OptionMapping option = event.getOption(OPTION_CODE);
    if (option == null) {
      event.reply("Usage: `/verify <code>`").setEphemeral(true).queue();
      return;
    }

    String code = option.getAsString().trim();
    Guild guild = event.getGuild();
    Member member = event.getMember();
    event.deferReply(true).queue();

    this.scheduler.runAsync(() -> {
      AuthService.VerifyResult result = this.service.verify(code, userId, event.getUser().getName());
      if (!result.isSuccess()) {
        this.registerFailure(userId);
        event.getHook().sendMessage(result.getMessage()).queue(null, error -> this.logger.debug("Discord queue error: {}", error.getMessage()));
        return;
      }
      this.clearAttempts(userId);
      if (guild != null && member != null) {
        this.grantRole(guild, member);
      }
      event.getHook().sendMessage(result.getMessage()).queue(null, error -> this.logger.debug("Discord queue error: {}", error.getMessage()));
    });
  }

  private void account(SlashCommandInteractionEvent event) {
    if (!this.guildAllowed(event)) {
      return;
    }
    String userId = event.getUser().getId();
    String tag = event.getUser().getAsMention();
    event.deferReply(true).queue();
    this.scheduler.runAsync(() ->
        event.getHook().sendMessage(this.service.accountInfo(userId, tag)).queue(null, error -> this.logger.debug("Discord queue error: {}", error.getMessage())));
  }

  private void passwordModal(SlashCommandInteractionEvent event) {
    if (!this.guildAllowed(event)) {
      return;
    }
    List<LinkStore.Link> linked = this.service.linksByDiscordId(event.getUser().getId());
    if (linked.isEmpty()) {
      event.reply(Text.render(this.config.messages.discordNoAccount, this.config, "", "", 0))
          .setEphemeral(true).queue();
      return;
    }
    String requested = optionText(event, OPTION_PLAYER, "");
    LinkStore.Link chosen = null;
    if (requested.isEmpty()) {
      if (linked.size() > 1) {
        String names = linked.stream().map(link -> link.name).collect(java.util.stream.Collectors.joining(", "));
        event.reply(Text.render(this.config.messages.discordChooseAccount, this.config, names, "", 0))
            .setEphemeral(true).queue();
        return;
      }
      chosen = linked.get(0);
    } else {
      for (LinkStore.Link link : linked) {
        if (link.name != null && link.name.equalsIgnoreCase(requested)) {
          chosen = link;
          break;
        }
      }
    }
    if (chosen == null) {
      event.reply(Text.render(this.config.messages.discordTargetNotYours, this.config, requested, "", 0))
          .setEphemeral(true).queue();
      return;
    }
    int min = this.config.password.minLength;
    int max = this.config.password.maxLength;
    String modalId = MODAL_PASSWORD + ":" + chosen.name;
    if (modalId.length() > MAX_MODAL_ID) {
      modalId = modalId.substring(0, MAX_MODAL_ID);
    }
    Modal modal = Modal.create(modalId, "Change password: " + chosen.name)
        .addComponents(
            ActionRow.of(TextInput.create(INPUT_CURRENT, "Current password", TextInputStyle.SHORT)
                .setPlaceholder("the password you use in game")
                .setRequired(true).setMinLength(1).setMaxLength(max).build()),
            ActionRow.of(TextInput.create(INPUT_NEW, "New password", TextInputStyle.SHORT)
                .setPlaceholder("from " + min + " to " + max + " characters")
                .setRequired(true).setMinLength(min).setMaxLength(max).build()),
            ActionRow.of(TextInput.create(INPUT_CONFIRM, "Confirm new password", TextInputStyle.SHORT)
                .setRequired(true).setMinLength(min).setMaxLength(max).build()))
        .build();
    event.replyModal(modal).queue();
  }

  private void unlinkModal(SlashCommandInteractionEvent event) {
    if (!this.guildAllowed(event)) {
      return;
    }
    String name = this.service.nameByDiscordId(event.getUser().getId());
    if (name == null) {
      event.reply(Text.render(this.config.messages.discordNoAccount, this.config, "", "", 0))
          .setEphemeral(true).queue();
      return;
    }
    Modal modal = Modal.create(MODAL_UNLINK, "Unlink account")
        .addComponents(ActionRow.of(TextInput.create(INPUT_NICK, "Enter your Minecraft nickname", TextInputStyle.SHORT)
            .setPlaceholder(name)
            .setRequired(true).setMinLength(1).setMaxLength(16).build()))
        .build();
    event.replyModal(modal).queue();
  }

  private void resetModal(SlashCommandInteractionEvent event) {
    if (!this.guildAllowed(event)) {
      return;
    }
    if (!this.isAdmin(event.getMember())) {
      event.reply(Text.render(this.config.messages.discordAdminOnly, this.config, "", "", 0))
          .setEphemeral(true).queue();
      return;
    }
    OptionMapping option = event.getOption(OPTION_PLAYER);
    if (option == null) {
      return;
    }
    String name = option.getAsString().trim();
    if (name.isEmpty() || this.service.uuidByName(name) == null) {
      event.reply(Text.render(this.config.messages.discordTargetNotLinked, this.config, name, "", 0))
          .setEphemeral(true).queue();
      return;
    }
    int min = this.config.password.minLength;
    int max = this.config.password.maxLength;
    String modalId = MODAL_RESET_PREFIX + name;
    if (modalId.length() > MAX_MODAL_ID) {
      modalId = modalId.substring(0, MAX_MODAL_ID);
    }
    Modal modal = Modal.create(modalId, "New password for " + name)
        .addComponents(
            ActionRow.of(TextInput.create(INPUT_NEW, "New password", TextInputStyle.SHORT)
                .setPlaceholder("from " + min + " to " + max + " characters")
                .setRequired(true).setMinLength(min).setMaxLength(max).build()),
            ActionRow.of(TextInput.create(INPUT_CONFIRM, "Confirm password", TextInputStyle.SHORT)
                .setRequired(true).setMinLength(min).setMaxLength(max).build()))
        .build();
    event.replyModal(modal).queue();
  }

  // ---------------------------------------------------------------- moderation

  private void ban(SlashCommandInteractionEvent event, boolean temporary) {
    if (!this.requireAdmin(event)) {
      return;
    }
    String name = optionText(event, OPTION_PLAYER, "");
    if (name.isEmpty()) {
      return;
    }
    if (name.equalsIgnoreCase(this.service.nameByDiscordId(event.getUser().getId()))) {
      event.reply(Text.render(this.config.messages.discordBanSelf, this.config, name, "", 0))
          .setEphemeral(true).queue();
      return;
    }
    String reason = optionText(event, OPTION_REASON, this.config.messages.discordReasonNone);
    long duration = 0L;
    if (temporary) {
      duration = DurationParser.parse(optionText(event, OPTION_DURATION, ""));
      if (duration <= 0) {
        event.reply(Text.render(this.config.messages.discordDurationInvalid, this.config, name, "", 0))
            .setEphemeral(true).queue();
        return;
      }
    }

    final long finalDuration = duration;
    final String by = event.getUser().getName();
    event.deferReply(true).queue();
    this.scheduler.runAsync(() -> {
      if (this.service.bans().find(null, name) != null) {
        event.getHook().sendMessage(Text.render(this.config.messages.discordBanAlready, this.config, name, "", 0))
            .queue(null, error -> this.logger.debug("Discord queue error: {}", error.getMessage()));
        return;
      }
      BanStore.Ban ban = this.service.ban(name, finalDuration, reason, by);
      String template = temporary
          ? this.config.messages.discordTempBanDone
          : this.config.messages.discordBanDone;
      String message = Text.render(template, this.config, name, "", 0)
          .replace("%reason%", reason)
          .replace("%duration%", DurationParser.human(finalDuration))
          .replace("%expires%", this.service.banExpiresText(ban));
      event.getHook().sendMessage(message).queue(null, error -> this.logger.debug("Discord queue error: {}", error.getMessage()));
    });
  }

  private void unban(SlashCommandInteractionEvent event) {
    if (!this.requireAdmin(event)) {
      return;
    }
    String name = optionText(event, OPTION_PLAYER, "");
    if (name.isEmpty()) {
      return;
    }
    event.deferReply(true).queue();
    this.scheduler.runAsync(() -> {
      boolean removed = this.service.unban(name);
      String message = removed
          ? Text.render(this.config.messages.discordUnbanDone, this.config, name, "", 0)
          : Text.render(this.config.messages.discordBanNotFound, this.config, name, "", 0);
      event.getHook().sendMessage(message).queue(null, error -> this.logger.debug("Discord queue error: {}", error.getMessage()));
    });
  }

  private void kick(SlashCommandInteractionEvent event) {
    if (!this.requireAdmin(event)) {
      return;
    }
    String name = optionText(event, OPTION_PLAYER, "");
    if (name.isEmpty()) {
      return;
    }
    String reason = optionText(event, OPTION_REASON, this.config.messages.discordReasonNone);
    String by = event.getUser().getName();
    event.deferReply(true).queue();
    this.scheduler.runAsync(() -> {
      boolean kicked = this.service.kick(name, reason, by);
      String message = kicked
          ? Text.render(this.config.messages.discordKicked, this.config, name, "", 0).replace("%reason%", reason)
          : Text.render(this.config.messages.discordKickOffline, this.config, name, "", 0);
      event.getHook().sendMessage(message).queue(null, error -> this.logger.debug("Discord queue error: {}", error.getMessage()));
    });
  }

  private void banList(SlashCommandInteractionEvent event) {
    if (!this.requireAdmin(event)) {
      return;
    }
    event.deferReply(true).queue();
    this.scheduler.runAsync(() -> {
      List<BanStore.Ban> bans = this.service.activeBans();
      if (bans.isEmpty()) {
        event.getHook().sendMessage(Text.render(this.config.messages.discordBanListEmpty, this.config, "", "", 0))
            .queue(null, error -> this.logger.debug("Discord queue error: {}", error.getMessage()));
        return;
      }
      StringBuilder message = new StringBuilder(Text.render(
          this.config.messages.discordBanListHeader, this.config, "", "", 0)
          .replace("%count%", String.valueOf(bans.size())));
      int shown = 0;
      for (BanStore.Ban ban : bans) {
        if (shown++ >= 25) {
          message.append("\n…");
          break;
        }
        message.append('\n').append(Text.render(
            this.config.messages.discordBanListEntry, this.config, ban.name, "", 0)
            .replace("%reason%", ban.reason == null || ban.reason.isBlank()
                ? this.config.messages.discordReasonNone : ban.reason)
            .replace("%expires%", this.service.banExpiresText(ban)));
      }
      event.getHook().sendMessage(message.toString()).queue(null, error -> this.logger.debug("Discord queue error: {}", error.getMessage()));
    });
  }

  private boolean requireAdmin(SlashCommandInteractionEvent event) {
    if (!this.guildAllowed(event)) {
      return false;
    }
    if (!this.isAdmin(event.getMember())) {
      event.reply(Text.render(this.config.messages.discordAdminOnly, this.config, "", "", 0))
          .setEphemeral(true).queue();
      return false;
    }
    return true;
  }

  private static String optionText(SlashCommandInteractionEvent event, String name, String fallback) {
    OptionMapping mapping = event.getOption(name);
    if (mapping == null) {
      return fallback;
    }
    String value = mapping.getAsString().trim();
    return value.isEmpty() ? fallback : value;
  }

  // --------------------------------------------------------------------- modals

  @Override
  public void onButtonInteraction(ButtonInteractionEvent event) {
    String id = event.getComponentId();
    if (!id.startsWith(BUTTON_BLOCK_IP)) {
      return;
    }
    String payload = id.substring(BUTTON_BLOCK_IP.length());
    // payload: uuid|ip
    String[] parts = payload.split("\\|", 2);
    if (parts.length < 2) {
      event.reply("❌ Invalid data.").setEphemeral(true).queue();
      return;
    }
    String uuidStr = parts[0];
    String ip = parts[1];
    java.util.UUID uuid;
    try {
      uuid = java.util.UUID.fromString(uuidStr);
    } catch (IllegalArgumentException exception) {
      event.reply("❌ Invalid data.").setEphemeral(true).queue();
      return;
    }
    // Only the owner of the account that triggered the alert may block the IP.
    boolean ownsAccount = false;
    for (LinkStore.Link link : this.service.linksByDiscordId(event.getUser().getId())) {
      java.util.UUID linkedUuid = this.service.uuidByName(link.name);
      if (linkedUuid != null && linkedUuid.equals(uuid)) {
        ownsAccount = true;
        break;
      }
    }
    if (!ownsAccount) {
      event.reply("❌ You can only block IPs from your own login alerts.").setEphemeral(true).queue();
      return;
    }
    event.deferReply(true).queue();
    this.scheduler.runAsync(() -> {
      try {
        this.service.host().blockIpAndKick(ip, uuid);
        event.getHook().sendMessage("✅ IP " + ip + " blocked. Player kicked from the server.").queue();
      } catch (IllegalArgumentException e) {
        event.getHook().sendMessage("❌ Invalid UUID.").queue();
      }
    });
  }

  @Override
  public void onModalInteraction(ModalInteractionEvent event) {
    String modalId = event.getModalId();
    if (modalId.equals(MODAL_PASSWORD) || modalId.startsWith(MODAL_PASSWORD + ":")) {
      String player = modalId.length() > MODAL_PASSWORD.length() + 1
          ? modalId.substring(MODAL_PASSWORD.length() + 1) : null;
      this.submitPasswordChange(event, player);
    } else if (MODAL_UNLINK.equals(modalId)) {
      this.submitUnlink(event);
    } else if (modalId.startsWith(MODAL_RESET_PREFIX)) {
      this.submitReset(event, modalId.substring(MODAL_RESET_PREFIX.length()));
    }
  }

  private void submitPasswordChange(ModalInteractionEvent event, String player) {
    String userId = event.getUser().getId();
    long blockedSeconds = this.blockedSeconds(userId);
    if (blockedSeconds > 0) {
      event.reply(Text.render(this.config.messages.discordRateLimited, this.config, "", "", blockedSeconds))
          .setEphemeral(true).queue();
      return;
    }

    String current = value(event, INPUT_CURRENT);
    String next = value(event, INPUT_NEW);
    String confirm = value(event, INPUT_CONFIRM);
    event.deferReply(true).queue();

    // BCrypt is deliberately slow — keep it off the Discord gateway thread.
    this.scheduler.runAsync(() -> {
      AuthService.VerifyResult result =
          this.service.changePasswordFromDiscord(userId, player, current, next, confirm);
      this.finishWithAttempts(userId, event, result);
    });
  }

  private void submitUnlink(ModalInteractionEvent event) {
    String userId = event.getUser().getId();
    String typed = value(event, INPUT_NICK);
    Member member = event.getMember();
    Guild guild = event.getGuild();
    event.deferReply(true).queue();
    this.scheduler.runAsync(() -> {
      AuthService.VerifyResult result =
          this.service.unlinkFromDiscord(userId, typed == null ? null : typed.trim());
      if (result.isSuccess() && guild != null && member != null) {
        this.revokeRole(guild, member);
      }
      event.getHook().sendMessage(result.getMessage()).queue(null, error -> this.logger.debug("Discord queue error: {}", error.getMessage()));
    });
  }

  private void submitReset(ModalInteractionEvent event, String playerName) {
    // The modal can be reopened long after the command: re-check the permission here.
    if (!this.isAdmin(event.getMember())) {
      event.reply(Text.render(this.config.messages.discordAdminOnly, this.config, "", "", 0))
          .setEphemeral(true).queue();
      return;
    }
    String next = value(event, INPUT_NEW);
    String confirm = value(event, INPUT_CONFIRM);
    event.deferReply(true).queue();
    this.scheduler.runAsync(() -> {
      AuthService.VerifyResult result = this.service.resetPasswordFromDiscord(playerName, next, confirm);
      event.getHook().sendMessage(result.getMessage()).queue(null, error -> this.logger.debug("Discord queue error: {}", error.getMessage()));
    });
  }

  private void finishWithAttempts(String userId, ModalInteractionEvent event, AuthService.VerifyResult result) {
    if (result.isSuccess()) {
      this.clearAttempts(userId);
    } else {
      this.registerFailure(userId);
    }
    event.getHook().sendMessage(result.getMessage()).queue(null, error -> this.logger.debug("Discord queue error: {}", error.getMessage()));
  }

  // -------------------------------------------------------------------- helpers

  private boolean guildAllowed(SlashCommandInteractionEvent event) {
    Guild guild = event.getGuild();
    String guildId = this.config.discord.guildId;
    boolean guildRequired = this.config.discord.requireGuildMember && guildId != null && !guildId.isBlank();
    if (guild == null || (guildRequired && !guild.getId().equals(guildId))) {
      event.reply(Text.render(this.config.messages.discordGuildOnly, this.config, "", "", 0))
          .setEphemeral(true).queue();
      return false;
    }
    return true;
  }

  private boolean isAdmin(Member member) {
    if (member == null) {
      return false;
    }
    Config.Discord discord = this.config.discord;
    if (discord.adminUserIds != null && discord.adminUserIds.contains(member.getId())) {
      return true;
    }
    if (member.isOwner()) {
      return true;
    }
    String roleId = discord.adminRoleId;
    if (roleId == null || roleId.isBlank()) {
      return false;
    }
    return member.getRoles().stream().anyMatch(role -> role.getId().equals(roleId));
  }

  private static String value(ModalInteractionEvent event, String id) {
    ModalMapping mapping = event.getValue(id);
    return mapping == null ? null : mapping.getAsString();
  }

  /**
   * Sends a Discord DM to the user warning about a login from a new IP.
   * Includes a "This wasn't me" button to block the IP.
   */
  public void sendLoginAlert(String discordId, String playerName, String ip, String uuid) {
    JDA current = this.jda;
    if (current == null || discordId == null || discordId.isBlank()) {
      return;
    }
    current.retrieveUserById(discordId).queue(user -> {
      String buttonId = BUTTON_BLOCK_IP + uuid + "|" + ip;
      Button blockButton = Button.danger(buttonId, "❌ This wasn't me — block");
      String message = "⚠️ **New login!**\n" +
          "Player **" + playerName + "** logged in from IP: `" + ip + "`\n" +
          "If this wasn't you, click the button below to block the IP and kick the player.";
      user.openPrivateChannel().queue(channel ->
          channel.sendMessage(message).setActionRow(blockButton).queue(
              success -> this.logger.info("Login alert sent to Discord user {}", discordId),
              error -> this.logger.warn("Unable to send login alert to {}: {}", discordId, error.getMessage())),
          error -> this.logger.warn("Unable to open DM channel for {}: {}", discordId, error.getMessage()));
    }, error -> this.logger.warn("Unable to retrieve Discord user {}: {}", discordId, error.getMessage()));
  }

  private void grantRole(Guild guild, Member member) {
    String roleId = this.config.discord.verifiedRoleId;
    if (roleId == null || roleId.isBlank()) {
      return;
    }
    Role role = guild.getRoleById(roleId);
    if (role == null) {
      this.logger.warn("Verified role {} was not found in guild {}", roleId, guild.getId());
      return;
    }
    if (member.getRoles().contains(role)) {
      return;
    }
    guild.addRoleToMember(member, role).queue(
        success -> this.logger.info("Granted role {} to {}", role.getName(), member.getUser().getName()),
        error -> this.logger.warn("Unable to grant role {} to {}: {}",
            role.getName(), member.getUser().getName(), error.getMessage()));
  }

  private void revokeRole(Guild guild, Member member) {
    String roleId = this.config.discord.verifiedRoleId;
    if (roleId == null || roleId.isBlank()) {
      return;
    }
    Role role = guild.getRoleById(roleId);
    if (role == null || !member.getRoles().contains(role)) {
      return;
    }
    guild.removeRoleFromMember(member, role).queue(
        success -> this.logger.info("Removed role {} from {}", role.getName(), member.getUser().getName()),
        error -> this.logger.warn("Unable to remove role {} from {}: {}",
            role.getName(), member.getUser().getName(), error.getMessage()));
  }

  private long blockedSeconds(String userId) {
    Attempts entry = this.attempts.get(userId);
    if (entry == null) {
      return 0L;
    }
    long remaining = entry.blockedUntil - System.currentTimeMillis();
    return remaining > 0 ? remaining / 1000L + 1L : 0L;
  }

  private void registerFailure(String userId) {
    long now = System.currentTimeMillis();
    // Drop stale entries first so a long-running server cannot grow this map without bound.
    this.attempts.entrySet().removeIf(entry -> now - entry.getValue().touchedAt > ATTEMPT_TTL_MILLIS);
    Attempts entry = this.attempts.computeIfAbsent(userId, key -> new Attempts());
    entry.count++;
    entry.touchedAt = now;
    if (entry.count >= Math.max(1, this.config.auth.maxAttemptsPerUser)) {
      entry.count = 0;
      entry.blockedUntil = now + Math.max(1, this.config.auth.attemptCooldownSeconds) * 1000L;
    }
  }

  private void clearAttempts(String userId) {
    this.attempts.remove(userId);
  }

  /** Resolves the plain Discord @username (used for links created before usernames were stored). */
  public void resolveUserName(String discordId, Consumer<String> callback) {
    JDA current = this.jda;
    if (current == null || discordId == null || discordId.isBlank()) {
      return;
    }
    current.retrieveUserById(discordId).queue(
        user -> callback.accept(user.getName()),
        error -> this.logger.warn("Unable to resolve Discord user {}: {}", discordId, error.getMessage()));
  }

  /** Everything we can show about a Discord account. */
  public static class MemberInfo {

    public final String username;
    public final String nick;
    public final String global;

    public MemberInfo(String username, String nick, String global) {
      this.username = username;
      this.nick = nick;
      this.global = global;
    }
  }

  /**
   * Resolves the username, the nickname on the project's Discord server and the global display
   * name. The guild-member lookup is a plain REST call, so it works with the intent-less bot.
   */
  public void resolveMember(String discordId, Consumer<MemberInfo> callback) {
    JDA current = this.jda;
    if (current == null || discordId == null || discordId.isBlank()) {
      return;
    }
    String guildId = this.config.discord.guildId;
    Guild guild = guildId == null || guildId.isBlank() ? null : current.getGuildById(guildId);
    if (guild == null) {
      current.retrieveUserById(discordId).queue(
          user -> callback.accept(new MemberInfo(user.getName(), null, user.getGlobalName())),
          error -> this.logger.warn("Unable to resolve Discord user {}: {}", discordId, error.getMessage()));
      return;
    }
    guild.retrieveMemberById(discordId).queue(
        member -> callback.accept(new MemberInfo(
            member.getUser().getName(), member.getNickname(), member.getUser().getGlobalName())),
        error -> {
          this.logger.warn("Unable to resolve Discord member {} in guild {}: {}",
              discordId, guildId, error.getMessage());
          this.resolveUserName(discordId, name -> callback.accept(new MemberInfo(name, null, null)));
        });
  }

  /** Lower-cased command names for logging. */
  public static String describeCommands() {
    return "verify, account, password, unlink, password-reset, ban, tempban, unban, kick, banlist";
  }
}
