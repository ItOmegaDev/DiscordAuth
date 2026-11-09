package net.omegasmp.discordauth.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;

/**
 * Platform-neutral authentication logic: sessions, the Discord-then-password sequence, password
 * checks, Discord account operations and moderation.
 *
 * <p>Both adapters (Velocity and Paper) drive the same service, so a bug fixed here is fixed on
 * both platforms and the JSON stores stay byte-compatible between them.</p>
 */
public class AuthService {

  /** Shown by {@code /account} in Discord. */
  public static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

  /** Wrong-password counters per source IP; they survive a reconnect. */
  private static final class IpAttempts {

    private int failures;
    private long blockedUntil;
    private long lastAttemptAt;
  }

  private final AuthHost host;
  private final Config config;
  private final AccountStore accounts;
  private final LinkStore links;
  private final BanStore bans;
  private final SessionRegistry sessions;

  private final Map<String, IpAttempts> passwordAttemptsByIp = new ConcurrentHashMap<>();

  public AuthService(AuthHost host) {
    this.host = host;
    this.config = host.config();
    this.accounts = host.accounts();
    this.links = host.links();
    this.bans = host.bans();
    this.sessions = host.sessions();
  }

  public Config config() {
    return this.config;
  }

  public AuthHost host() {
    return this.host;
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

  /** Starts the expiry loop (every 5 seconds, same cadence as 1.x). */
  public void start() {
    this.host.scheduler().repeat(this::expireSessions, 5_000L);
  }

  // ------------------------------------------------------------------ sessions

  /**
   * Creates a new pending session. The expiry starts at the Discord code TTL and is shortened to
   * the password timeout once the Discord step is done.
   */
  public AuthSession createSession(UUID uuid, String username, boolean needsDiscord,
      boolean needsPassword, String address) {
    long ttlSeconds = needsDiscord ? this.config.auth.codeTtlSeconds : this.config.password.timeoutSeconds;
    AuthSession session = new AuthSession(
        uuid,
        username,
        this.sessions.generateCode(this.config.auth.codeLength),
        System.currentTimeMillis() + Math.max(30L, ttlSeconds) * 1000L,
        needsDiscord,
        needsPassword,
        false,
        this.host.isBedrockName(username),
        address);
    this.sessions.add(session);
    return session;
  }

  /** Code for {@code /discordlink}: links an already playing account, no password step. */
  public AuthSession createLinkSession(UUID uuid, String username) {
    long ttl = Math.max(30L, this.config.auth.codeTtlSeconds);
    AuthSession session = new AuthSession(
        uuid,
        username,
        this.sessions.generateCode(this.config.auth.codeLength),
        System.currentTimeMillis() + ttl * 1000L,
        true,
        false,
        true,
        this.host.isBedrockName(username),
        "");
    this.sessions.addLink(session);
    return session;
  }

  public void dropSession(AuthSession session) {
    if (session == null) {
      return;
    }
    if (session.isLinkOnly()) {
      this.sessions.removeLink(session.getCode());
    } else {
      this.sessions.remove(session);
    }
  }

  private void expireSessions() {
    for (AuthSession session : this.sessions.all()) {
      if (!session.isExpired()) {
        continue;
      }
      this.sessions.remove(session);
      String template = session.needsDiscord()
          ? this.config.messages.timeoutKick
          : this.config.messages.passwordTimeoutKick;
      this.host.disconnect(session, Text.build(template, this.config, session));
      this.host.logger().info("Authorization for {} expired.", session.getUsername());
    }
    this.sessions.removeExpiredLinks();
    this.host.housekeeping();
    long now = System.currentTimeMillis();
    this.passwordAttemptsByIp.entrySet().removeIf(entry -> now - entry.getValue().lastAttemptAt > 3_600_000L);
  }

  /**
   * Releases the player as soon as every required step is done.
   *
   * @return true when this call released the player.
   */
  public boolean releaseIfComplete(AuthSession session) {
    if (!session.isComplete() || !session.markReleased()) {
      return false;
    }
    this.sessions.remove(session);

    String template = session.isPasswordRequired()
        ? this.config.messages.passwordSuccess
        : this.config.messages.verified;
    this.host.send(session, Text.build(template, this.config, session));
    this.host.logger().info("{} authorized — handing over to the platform.", session.getUsername());
    this.host.release(session);
    return true;
  }

  // ------------------------------------------------------------- Discord step

  /** Verifies a code typed into Discord. Called from the bot thread. */
  public VerifyResult verify(String rawCode, String discordId, String discordName) {
    String wrongCode = Text.render(this.config.messages.discordWrongCode, this.config, "", "", 0);
    if (rawCode == null || rawCode.isBlank()) {
      return VerifyResult.fail(wrongCode);
    }
    String code = rawCode.trim();

    AuthSession session = this.sessions.byCode(code, false);
    boolean linkOnly = false;
    if (session == null) {
      session = this.sessions.byCode(code, true);
      linkOnly = true;
    }
    if (session == null) {
      return VerifyResult.fail(wrongCode);
    }
    if (session.isExpired()) {
      this.dropSession(session);
      return VerifyResult.fail(wrongCode);
    }

    boolean bedrock = this.host.isBedrockName(session.getUsername());
    LinkStore.Link existing = this.links.getLink(session.getUuid());
    if (existing == null) {
      List<LinkStore.Link> linked = this.links.getLinksByDiscordId(discordId);
      int max = Math.max(1, this.config.discord.maxAccountsPerDiscord);
      if (linked.size() >= max) {
        String names = linked.stream()
            .map(link -> link.name)
            .filter(name -> name != null && !name.isBlank())
            .collect(Collectors.joining(", "));
        return VerifyResult.fail(Text.render(this.config.messages.discordAccountLimit, this.config,
            names, session.getCode(), 0));
      }
      long sameTypeCount = linked.stream().filter(link -> this.host.isBedrockName(link.name) == bedrock).count();
      int typeMax = bedrock
          ? Math.max(0, this.config.discord.maxBedrockAccountsPerDiscord)
          : Math.max(0, this.config.discord.maxJavaAccountsPerDiscord);
      if (sameTypeCount >= typeMax) {
        String template = bedrock
            ? this.config.messages.discordBedrockAccountLimit
            : this.config.messages.discordJavaAccountLimit;
        return VerifyResult.fail(Text.render(template, this.config, session.getUsername(), session.getCode(), 0));
      }
    }

    boolean firstLink = existing == null;
    if (firstLink) {
      this.links.link(session.getUuid(), session.getUsername(), discordId, discordName);
    } else {
      this.links.setDiscordName(session.getUuid(), discordName);
    }
    this.host.identityChanged(session.getUuid());

    if (linkOnly) {
      this.sessions.removeLink(session.getCode());
      this.host.send(session, Text.build(this.config.messages.discordLinkSuccessPlayer, this.config, session));
      return VerifyResult.ok(Text.render(
          this.config.messages.discordLinkSuccess, this.config, session.getUsername(), session.getCode(), 0));
    }

    this.completeDiscordStep(session);
    boolean completed = this.releaseIfComplete(session);
    String template;
    if (!completed && session.needsPassword()) {
      template = this.config.messages.discordSuccessNeedsPassword;
    } else {
      template = firstLink ? this.config.messages.discordSuccess : this.config.messages.discordAlreadyLinked;
    }
    return VerifyResult.ok(Text.render(template, this.config, session.getUsername(), session.getCode(), 0));
  }

  /** Marks the Discord step done and moves the UI to the password step. */
  private void completeDiscordStep(AuthSession session) {
    if (!session.markDiscordDone()) {
      return;
    }
    this.sessions.removeCode(session);
    session.extendTo(System.currentTimeMillis() + Math.max(30, this.config.password.timeoutSeconds) * 1000L);
    this.host.send(session, Text.build(this.config.messages.discordLinkedInGame, this.config, session));
    if (session.needsPassword()) {
      this.host.showUi(session);
    }
  }

  /** "I've verified" from the Paper dialog: re-checks the link and continues. */
  public boolean confirmDiscord(AuthSession session) {
    if (session == null) {
      return false;
    }
    if (!session.needsDiscord()) {
      return true;
    }
    if (!this.links.isLinked(session.getUuid())) {
      return false;
    }
    this.completeDiscordStep(session);
    this.releaseIfComplete(session);
    return true;
  }

  // -------------------------------------------------------------- password step

  /** Prompt for the password step ({@code /register} for a new account). */
  public Component passwordPrompt(UUID uuid) {
    String template = this.accounts.isRegistered(uuid)
        ? this.config.messages.passwordLogin
        : this.config.messages.passwordRegister;
    return Text.build(template, this.config, null);
  }

  /** The Discord instructions for a session whose link is still missing. */
  public Component discordPrompt(AuthSession session) {
    return Text.build(this.config.messages.chat, this.config, session);
  }

  /** Everything still pending — used for plain chat and unknown commands. */
  public Component hint(AuthSession session) {
    Component result = null;
    if (session.needsDiscord()) {
      result = this.discordPrompt(session);
    }
    if (session.needsPassword()) {
      Component prompt = this.passwordPrompt(session.getUuid());
      result = result == null ? prompt : result.append(Component.newline()).append(prompt);
    }
    return result == null ? Text.build(this.config.messages.passwordHint, this.config, session) : result;
  }

  /** {@code /login <password>}; hashing runs off the main thread. */
  public Component login(AuthSession session, String[] args) {
    if (session.isComplete()) {
      return null;
    }
    if (!session.needsPassword()) {
      return session.needsDiscord() ? this.discordPrompt(session) : null;
    }
    if (args.length != 2 || args[1].isEmpty()) {
      return Text.build(this.config.messages.passwordUsage, this.config, session);
    }
    long blocked = session.blockedSeconds();
    if (blocked > 0) {
      return this.blockedMessage(session, blocked);
    }
    String ip = this.host.addressOf(session);
    long ipBlocked = this.ipBlockedSeconds(ip);
    if (ipBlocked > 0) {
      return this.blockedMessage(session, ipBlocked);
    }

    String password = args[1];
    this.host.scheduler().runAsync(() -> {
      if (!this.accounts.isRegistered(session.getUuid())) {
        this.host.send(session, Text.build(this.config.messages.passwordNotRegistered, this.config, session));
        this.host.passwordFailed(session);
        return;
      }
      if (!this.accounts.verify(session.getUuid(), password)) {
        session.registerPasswordFailure(
            this.config.password.maxAttempts, this.config.password.cooldownSeconds * 1000L);
        this.registerIpFailure(ip);
        this.host.logger().info("Wrong password for {} ({}).", session.getUsername(), session.getUuid());
        long wait = Math.max(session.blockedSeconds(), this.ipBlockedSeconds(ip));
        this.host.send(session, wait > 0
            ? this.blockedMessage(session, wait)
            : Text.build(this.config.messages.passwordWrong, this.config, session));
        this.host.passwordFailed(session);
        return;
      }
      this.clearIpFailures(ip);
      this.finishPasswordStep(session, this.config.messages.passwordAccepted);
    });
    return null;
  }

  /** {@code /register <password> <password>}; hashing runs off the main thread. */
  public Component register(AuthSession session, String[] args) {
    if (session.isComplete()) {
      return null;
    }
    if (!session.needsPassword()) {
      return session.needsDiscord() ? this.discordPrompt(session) : null;
    }
    if (args.length != 3 || args[1].isEmpty()) {
      return Text.build(this.config.messages.passwordUsage, this.config, session);
    }
    if (session.blockedSeconds() > 0) {
      return this.blockedMessage(session, session.blockedSeconds());
    }
    String password = args[1];
    if (!password.equals(args[2])) {
      return Text.build(this.config.messages.passwordMismatch, this.config, session);
    }
    if (password.length() < this.config.password.minLength) {
      return Text.build(this.config.messages.passwordTooShort, this.config, session);
    }
    if (password.length() > this.config.password.maxLength) {
      return Text.build(this.config.messages.passwordTooLong, this.config, session);
    }

    this.host.scheduler().runAsync(() -> {
      if (!this.accounts.register(session.getUuid(), session.getUsername(), password)) {
        this.host.send(session, Text.build(this.config.messages.passwordAlreadyRegistered, this.config, session));
        return;
      }
      this.finishPasswordStep(session, this.config.messages.passwordCreated);
    });
    return null;
  }

  /** Same flow as chat, but already validated by the platform dialog. */
  public void submitPassword(AuthSession session, String password) {
    if (session == null || session.isComplete() || !session.needsPassword()) {
      return;
    }
    if (this.accounts.isRegistered(session.getUuid())) {
      String ip = this.host.addressOf(session);
      this.host.scheduler().runAsync(() -> {
        if (!this.accounts.verify(session.getUuid(), password)) {
          session.registerPasswordFailure(
              this.config.password.maxAttempts, this.config.password.cooldownSeconds * 1000L);
          this.registerIpFailure(ip);
          long wait = Math.max(session.blockedSeconds(), this.ipBlockedSeconds(ip));
          this.host.send(session, wait > 0
              ? this.blockedMessage(session, wait)
              : Text.build(this.config.messages.passwordWrong, this.config, session));
          this.host.passwordFailed(session);
          return;
        }
        this.clearIpFailures(ip);
        this.finishPasswordStep(session, this.config.messages.passwordAccepted);
      });
      return;
    }
    this.host.scheduler().runAsync(() -> {
      if (!this.accounts.register(session.getUuid(), session.getUsername(), password)) {
        this.host.send(session, Text.build(this.config.messages.passwordAlreadyRegistered, this.config, session));
        return;
      }
      this.finishPasswordStep(session, this.config.messages.passwordCreated);
    });
  }

  private void finishPasswordStep(AuthSession session, String confirmationTemplate) {
    session.markPasswordDone();
    this.host.passwordSucceeded(session);
    this.host.send(session, Text.build(confirmationTemplate, this.config, session));
    if (this.releaseIfComplete(session)) {
      return;
    }
    if (session.needsDiscord()) {
      this.host.showUi(session);
    }
  }

  private Component blockedMessage(AuthSession session, long seconds) {
    String template = Text.render(this.config.messages.passwordBlocked, this.config, session, seconds);
    return Text.build(template, this.config, session);
  }

  private void registerIpFailure(String ip) {
    if (ip == null || ip.isBlank() || this.config.password.ipMaxAttempts <= 0) {
      return;
    }
    long now = System.currentTimeMillis();
    this.passwordAttemptsByIp.compute(ip, (key, state) -> {
      IpAttempts value = state == null ? new IpAttempts() : state;
      value.failures++;
      value.lastAttemptAt = now;
      if (value.failures >= this.config.password.ipMaxAttempts) {
        value.failures = 0;
        value.blockedUntil = now + Math.max(1, this.config.password.ipCooldownSeconds) * 1000L;
        this.host.logger().warn("Too many wrong passwords from {} — blocking the address for {}s.",
            ip, Math.max(1, this.config.password.ipCooldownSeconds));
      }
      return value;
    });
  }

  private long ipBlockedSeconds(String ip) {
    if (ip == null || ip.isBlank()) {
      return 0L;
    }
    IpAttempts state = this.passwordAttemptsByIp.get(ip);
    if (state == null) {
      return 0L;
    }
    long remaining = state.blockedUntil - System.currentTimeMillis();
    return remaining > 0 ? remaining / 1000L + 1L : 0L;
  }

  private void clearIpFailures(String ip) {
    if (ip != null && !ip.isBlank()) {
      this.passwordAttemptsByIp.remove(ip);
    }
  }

  // ------------------------------------------------- Discord account operations

  public UUID uuidByDiscordId(String discordId) {
    return parseUuid(this.links.getUuidByDiscordId(discordId));
  }

  public String nameByDiscordId(String discordId) {
    return discordId == null ? null : this.links.getNameByDiscordId(discordId);
  }

  public UUID uuidByName(String name) {
    return parseUuid(name == null ? null : this.links.getUuidByName(name.trim()));
  }

  private static UUID parseUuid(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  public List<LinkStore.Link> linksByDiscordId(String discordId) {
    return this.links.getLinksByDiscordId(discordId);
  }

  public VerifyResult changePasswordFromDiscord(String discordId, String playerName, String current,
      String newPassword, String confirm) {
    LinkStore.Link link = this.linkForDiscord(discordId, playerName);
    if (link == null) {
      return VerifyResult.fail(Text.render(
          playerName == null || playerName.isBlank()
              ? this.config.messages.discordNoAccount
              : this.config.messages.discordTargetNotYours,
          this.config, playerName, null, 0));
    }
    UUID uuid = this.uuidByName(link.name);
    String name = link.name;
    if (uuid == null || name == null) {
      return VerifyResult.fail(Text.render(this.config.messages.discordNoAccount, this.config, "", "", 0));
    }
    VerifyResult invalid = this.validateNewPassword(name, newPassword, confirm);
    if (invalid != null) {
      return invalid;
    }
    if (!this.accounts.isRegistered(uuid)) {
      return VerifyResult.fail(Text.render(this.config.messages.discordNoPassword, this.config, name, null, 0));
    }
    if (current == null || current.isEmpty() || !this.accounts.verify(uuid, current)) {
      this.host.logger().info("Rejected a password change from Discord for Minecraft {} ({}) — wrong current password",
          name, uuid);
      return VerifyResult.fail(Text.render(this.config.messages.discordPasswordWrongOld, this.config, name, null, 0));
    }
    if (!this.accounts.changePassword(uuid, newPassword)) {
      return VerifyResult.fail(Text.render(this.config.messages.discordNoPassword, this.config, name, null, 0));
    }
    this.host.logger().info("Discord user {} changed the password of Minecraft {} ({})", discordId, name, uuid);
    this.host.passwordChanged(uuid, name);
    return VerifyResult.ok(Text.render(this.config.messages.discordPasswordChanged, this.config, name, null, 0));
  }

  public VerifyResult resetPasswordFromDiscord(String playerName, String newPassword, String confirm) {
    UUID uuid = this.uuidByName(playerName);
    if (uuid == null) {
      return VerifyResult.fail(Text.render(this.config.messages.discordTargetNotLinked, this.config, playerName, null, 0));
    }
    LinkStore.Link link = this.links.getLink(uuid);
    String name = link == null || link.name == null ? playerName : link.name;
    VerifyResult invalid = this.validateNewPassword(name, newPassword, confirm);
    if (invalid != null) {
      return invalid;
    }
    if (!this.accounts.isRegistered(uuid) || !this.accounts.changePassword(uuid, newPassword)) {
      return VerifyResult.fail(Text.render(this.config.messages.discordNoPassword, this.config, name, null, 0));
    }
    this.host.logger().info("An administrator set a new password for Minecraft {} ({})", name, uuid);
    this.host.passwordChanged(uuid, name);
    return VerifyResult.ok(Text.render(this.config.messages.discordResetDone, this.config, name, null, 0));
  }

  public VerifyResult unlinkFromDiscord(String discordId, String typedName) {
    List<LinkStore.Link> all = this.links.getLinksByDiscordId(discordId);
    if (all.isEmpty()) {
      return VerifyResult.fail(Text.render(this.config.messages.discordNoAccount, this.config, "", "", 0));
    }
    LinkStore.Link link = this.linkForDiscord(discordId, typedName == null ? null : typedName.trim());
    if (link == null) {
      return VerifyResult.fail(Text.render(this.config.messages.discordUnlinkMismatch,
          this.config, this.nameByDiscordId(discordId), null, 0));
    }
    UUID uuid = this.uuidByName(link.name);
    String name = link.name;
    if (uuid == null) {
      return VerifyResult.fail(Text.render(this.config.messages.discordNoAccount, this.config, "", "", 0));
    }
    this.links.unlink(uuid);
    this.host.logger().info("Discord user {} unlinked Minecraft {} ({})", discordId, name, uuid);
    this.host.identityChanged(uuid);
    return VerifyResult.ok(Text.render(this.config.messages.discordUnlinked, this.config, name, null, 0));
  }

  private LinkStore.Link linkForDiscord(String discordId, String playerName) {
    List<LinkStore.Link> linked = this.links.getLinksByDiscordId(discordId);
    if (linked.isEmpty()) {
      return null;
    }
    if (playerName == null || playerName.isBlank()) {
      return linked.size() == 1 ? linked.get(0) : null;
    }
    for (LinkStore.Link link : linked) {
      if (link.name != null && link.name.equalsIgnoreCase(playerName.trim())) {
        return link;
      }
    }
    return null;
  }

  private VerifyResult validateNewPassword(String name, String newPassword, String confirm) {
    if (newPassword == null || newPassword.isEmpty() || !newPassword.equals(confirm)) {
      return VerifyResult.fail(Text.render(this.config.messages.discordPasswordMismatch, this.config, name, null, 0));
    }
    if (newPassword.length() < this.config.password.minLength
        || newPassword.length() > this.config.password.maxLength) {
      return VerifyResult.fail(Text.render(this.config.messages.discordPasswordRule, this.config, name, null, 0));
    }
    return null;
  }

  /** Text shown by {@code /account} in Discord — one section per linked account. */
  public String accountInfo(String discordId, String discordTag) {
    List<LinkStore.Link> linked = this.links.getLinksByDiscordId(discordId);
    if (linked.isEmpty()) {
      return Text.render(this.config.messages.discordNoAccount, this.config, "", "", 0);
    }
    long javaCount = linked.stream().filter(link -> !this.host.isBedrockName(link.name)).count();
    long bedrockCount = linked.stream().filter(link -> this.host.isBedrockName(link.name)).count();
    String header = Text.render(this.config.messages.discordAccountListHeader, this.config, "", "", 0)
        .replace("%count%", String.valueOf(linked.size()))
        .replace("%java%", String.valueOf(javaCount))
        .replace("%bedrock%", String.valueOf(bedrockCount));
    StringBuilder result = new StringBuilder(header);
    for (LinkStore.Link link : linked) {
      UUID uuid = this.uuidByName(link.name);
      if (uuid == null) {
        continue;
      }
      result.append('\n').append(this.accountInfoForLink(uuid, link, discordId, discordTag));
    }
    return result.toString();
  }

  private String accountInfoForLink(UUID uuid, LinkStore.Link link, String discordId, String discordTag) {
    String name = link.name;
    boolean hasPassword = this.accounts.isRegistered(uuid);
    long lastLogin = this.accounts.lastLogin(uuid);
    long registeredAt = this.accounts.registeredAt(uuid);
    BanStore.Ban ban = this.bans.find(uuid, name);
    return Text.render(this.config.messages.discordAccountInfo, this.config, name, null, 0)
        .replace("%edition%", this.host.isBedrockName(name) ? "Bedrock" : "Java")
        .replace("%uuid%", uuid.toString())
        .replace("%discord%", discordTag == null || discordTag.isBlank() ? "—" : discordTag)
        .replace("%discord_id%", discordId == null || discordId.isBlank() ? "—" : discordId)
        .replace("%discord_username%", valueOrDash(link.discordName))
        .replace("%discord_nick%", valueOrDash(link.discordNick))
        .replace("%discord_global%", valueOrDash(link.discordGlobal))
        .replace("%password%", hasPassword
            ? this.config.messages.discordAccountPasswordYes
            : this.config.messages.discordAccountPasswordNo)
        .replace("%registered%", registeredAt <= 0 ? "—" : DATE_FORMAT.format(Instant.ofEpochMilli(registeredAt)))
        .replace("%linked%", link.linkedAt <= 0 ? "—" : DATE_FORMAT.format(Instant.ofEpochMilli(link.linkedAt)))
        .replace("%last_login%", lastLogin <= 0 ? "—" : DATE_FORMAT.format(Instant.ofEpochMilli(lastLogin)))
        .replace("%ban%", this.banSummary(ban));
  }

  private static String valueOrDash(String value) {
    return value == null || value.isBlank() ? "—" : value;
  }

  // ----------------------------------------------------------------- moderation

  public BanStore.Ban ban(String rawName, long durationMillis, String reason, String bannedBy) {
    String name = rawName == null ? "" : rawName.trim();
    String cleanReason = reason == null || reason.isBlank()
        ? this.config.messages.discordReasonNone
        : reason.trim();
    BanStore.Ban ban = this.bans.add(name, this.host.onlineUuid(name), cleanReason, bannedBy, durationMillis);
    this.host.kickOnline(name, this.banComponent(ban));
    this.host.logger().info("{} banned {} ({}): {}", bannedBy, name,
        ban.permanent() ? "permanent" : "until " + this.banExpiresText(ban), cleanReason);
    return ban;
  }

  public boolean unban(String name) {
    boolean removed = this.bans.remove(name == null ? null : name.trim());
    if (removed) {
      this.host.logger().info("Ban for {} was lifted.", name);
    }
    return removed;
  }

  public boolean kick(String name, String reason, String kickedBy) {
    String cleanReason = reason == null || reason.isBlank()
        ? this.config.messages.discordReasonNone
        : reason.trim();
    boolean kicked = this.host.kickOnline(name, this.kickComponent(cleanReason, kickedBy));
    if (kicked) {
      this.host.logger().info("{} kicked {}: {}", kickedBy, name, cleanReason);
    }
    return kicked;
  }

  public List<BanStore.Ban> activeBans() {
    return this.bans.active();
  }

  public String banSummary(BanStore.Ban ban) {
    if (ban == null) {
      return "—";
    }
    String reason = ban.reason == null || ban.reason.isBlank()
        ? this.config.messages.discordReasonNone
        : ban.reason;
    return reason + " (" + this.banExpiresText(ban) + ")";
  }

  public String banExpiresText(BanStore.Ban ban) {
    if (ban == null || ban.permanent()) {
      return this.config.messages.banExpiresNever;
    }
    return DATE_FORMAT.format(Instant.ofEpochMilli(ban.expiresAt));
  }

  public Component banComponent(BanStore.Ban ban) {
    String reason = ban.reason == null || ban.reason.isBlank()
        ? this.config.messages.discordReasonNone
        : ban.reason;
    String by = ban.bannedBy == null || ban.bannedBy.isBlank() ? "console" : ban.bannedBy;
    String text = Text.render(this.config.messages.banKickMessage, this.config, ban.name, null, 0)
        .replace("%reason%", reason)
        .replace("%expires%", this.banExpiresText(ban))
        .replace("%by%", by);
    return Text.component(text);
  }

  public Component kickComponent(String reason, String by) {
    String text = Text.render(this.config.messages.kickMessage, this.config, "", "", 0)
        .replace("%reason%", reason == null || reason.isBlank() ? this.config.messages.discordReasonNone : reason)
        .replace("%by%", by == null || by.isBlank() ? "console" : by);
    return Text.component(text);
  }

  /** True when {@code name} is one of the configured verification-bypass nicknames. */
  public boolean bypassesDiscord(String name) {
    if (name == null) {
      return false;
    }
    String lower = name.toLowerCase(Locale.ROOT);
    for (String bypass : this.config.auth.verificationBypassNames) {
      if (bypass != null && bypass.equals(lower)) {
        return true;
      }
    }
    return false;
  }

  public boolean isBedrockName(String name) {
    return this.host.isBedrockName(name);
  }

  /** Result of a Discord operation, with a ready-to-send message. */
  public static class VerifyResult {

    private final boolean success;
    private final String message;

    private VerifyResult(boolean success, String message) {
      this.success = success;
      this.message = message;
    }

    public static VerifyResult ok(String message) {
      return new VerifyResult(true, message);
    }

    public static VerifyResult fail(String message) {
      return new VerifyResult(false, message);
    }

    public boolean isSuccess() {
      return this.success;
    }

    public String getMessage() {
      return this.message;
    }
  }
}
