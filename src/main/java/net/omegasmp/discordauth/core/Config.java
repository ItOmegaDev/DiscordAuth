package net.omegasmp.discordauth.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.yaml.snakeyaml.Yaml;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Configuration loaded from {@code config.yml} (YAML) for easy editing.
 *
 * <p>On first start a commented {@code config.yml} is generated from the bundled template.
 * The file is read with SnakeYAML, converted to JSON via Gson, then parsed into this class,
 * so all default values and validation still apply.</p>
 */
public class Config {

  public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  private static final Yaml YAML = new Yaml();

  /** {@code auto} (default), {@code velocity} or {@code paper}. Mismatch disables the plugin. */
  public String platform = "auto";
  /** {@code auth} (default) or {@code bridge} (Paper only: only forwards Discord identity). */
  public String role = "auth";
  /** {@code auto} (default), {@code dialog} or {@code chat}. */
  public String ui = "auto";
  /** Verbose logging. */
  public boolean debug = false;

  public Discord discord = new Discord();
  public Auth auth = new Auth();
  public LimboSettings limbo = new LimboSettings();
  public PaperSettings paper = new PaperSettings();
  public Tab tab = new Tab();
  public Bridge bridge = new Bridge();
  public Commands commands = new Commands();
  public Password password = new Password();
  public Messages messages = new Messages();

  // ---- Nested classes (same as before) ----

  public static class Discord {
    public String guildId = "";
    public String verifiedRoleId = "";
    public String adminRoleId = "";
    public List<String> adminUserIds = new ArrayList<>();
    public String inviteUrl = "";
    public boolean requireGuildMember = true;
    public int maxAccountsPerDiscord = 2;
    public int maxJavaAccountsPerDiscord = 1;
    public int maxBedrockAccountsPerDiscord = 1;
    public int identityRefreshMinutes = 30;
  }

  public static class Auth {
    public String mainServer = "main";
    public int codeTtlSeconds = 300;
    public int codeLength = 6;
    public int maxAttemptsPerUser = 5;
    public int attemptCooldownSeconds = 60;
    public boolean transferOnVerify = true;
    public String transferAddress = "";
    public int transferDelayMillis = 1200;
    public int transferReconnectTimeoutMillis = 15000;
    public String transferFallbackAddress = "127.0.0.1:25565";
    public boolean bedrockBypassVerification = false;
    public List<String> bedrockUsernamePrefixes = new ArrayList<>(List.of("."));
    public boolean bedrockBypassPassword = false;
    /** TEST MODE: skips ALL authentication. For testing only! */
    public boolean testMode = false;
    /** Max connections from one IP per minute before temporary block (0 = disabled). */
    public int maxConnectionsPerIpPerMinute = 10;
    /** How long (seconds) an IP is blocked after exceeding the limit. */
    public int ipBlockDurationSeconds = 300;
    public List<String> verificationBypassNames = new ArrayList<>();
  }

  public static class LimboSettings {
    public String dimension = "THE_END";
    public double x = 0.5;
    public double y = 64.0;
    public double z = 0.5;
    public float yaw = 0.0f;
    public float pitch = 0.0f;
  }

  public static class PaperSettings {
    public boolean freeze = true;
    public boolean blindness = true;
    public boolean hidePlayers = true;
    public boolean adventure = true;
    public int timeoutSeconds = 300;
    public int dialogColumns = 2;
    public int dialogMinProtocol = 771;
    public boolean discordButtonInPasswordDialog = true;
  }

  public static class Tab {
    public boolean enabled = true;
    public String nameSource = "nickname";
    public String format = "&#39D353%player%\n&8&o%discord%";
    public String hover = "&8Minecraft&7: &f%player%\n&#39D353Discord&7: &f%discord%\n&8Discord ID&7: &f%discord_id%";
    public String unknownDiscord = "unknown";
    public boolean stripUnsupported = true;
    public boolean skipWhenTabPluginInstalled = true;
    public boolean registerTabPluginPlaceholders = true;
    public int tabPluginPlaceholderRefreshMillis = 1000;
  }

  public static class Bridge {
    public boolean enabled = true;
    public String channel = "discordauth:identity";
    public String placeholderPrefix = "discordauth";
    public String unlinkedPlaceholder = "";
    public boolean debug = false;
  }

  public static class Commands {
    public List<String> register = new ArrayList<>(List.of("register", "reg", "r"));
    public List<String> login = new ArrayList<>(List.of("login", "l"));
    public List<String> admin = new ArrayList<>(List.of("discordauth", "dauth"));
    public List<String> link = new ArrayList<>(List.of("discordlink", "linkdiscord"));
    public List<String> discord = new ArrayList<>(List.of("discord", "dc"));
  }

  public static class Password {
    public boolean enabled = true;
    public boolean requireWhenOnlineMode = false;
    public int sessionMinutes = 20;
    public int transferTicketSeconds = 30;
    public int minLength = 8;
    public int maxLength = 64;
    public int maxAttempts = 5;
    public int cooldownSeconds = 60;
    public int ipMaxAttempts = 10;
    public int ipCooldownSeconds = 300;
    public int timeoutSeconds = 60;
  }

  public static class Messages {
    // All message fields with defaults (same as before)
    public String title = "&#39D353&lACCOUNT VERIFICATION";
    public String subtitle = "&8Code: &#39D353&l%code%";
    public String chat = "&8&m                                                  \n&#39D353&lVerify your account\n&8Step 1: &7Join our Discord — %discord_link%\n&8Step 2: &7Type &#39D353/verify %copy_code%";
    public String actionBar = "&8Code: &#39D353&l%code% &8— &7/verify %code% &8in Discord";
    public String bossBarDiscord = "&#39D353&lDISCORD &8— &f%time% &7left";
    public String bossBarPassword = "&#39D353&lPASSWORD &8— &f%time% &7left";
    public String linkText = "&#39D353&l[ OPEN DISCORD ]";
    public String linkHover = "&#39D353Click to open the Discord server\n&7Then type &f/verify <code>";
    public String linkTextFallback = "&#39D353Discord server &fProject OmegaSMP";
    public String codeText = "&#39D353&l%code%";
    public String codeHover = "&#39D353Click to copy the code";
    public String verified = "&#39D353&l✔ &7Account linked! Sending you to the server…";
    public String transferKick = "&#39D353&l✔ &7Account linked!\n&8The transfer failed — reconnect to the server and you will join the game directly.";
    public String timeoutKick = "&4&lVerification timed out\n&7Join again to get a new code.\n&8Discord: &#39D353%discord_link%";
    public String passwordTimeoutKick = "&4&lTimed out\n&7Join again and enter your password: &f/%login_command% <password>";
    public String chatIgnored = "&cOnly &f/%login_command% <password>&c and &f/%register_command% <password> <password>&c work in chat. Verify the code in Discord: &f/verify <code>&c.";
    public String discordSuccess = "✅ Account **%player%** has been linked! Sending you to the server…";
    public String discordSuccessNeedsPassword = "✅ Discord account linked to **%player%**! Return to the game and finish signing in with your password.";
    public String discordAlreadyLinked = "ℹ️ Your Discord is already linked to the account **%player%**.";
    public String passwordRegister = "&8&m                                                  \n&#39D353&lCREATE A PASSWORD\n&8Choose a password (%min%-%max% characters) and type:\n&f/%register_command% <password> <password>";
    public String passwordLogin = "&8&m                                                  \n&#39D353&lSIGN IN\n&8Type your password:\n&f/%login_command% <password>";
    public String passwordHint = "&8Only &f/%login_command% <password> &8and &f/%register_command% <password> <password> &8are available while signing in.";
    public String discordLinkedInGame = "&#39D353&l✔ &7Discord linked!";
    public String passwordSuccess = "&#39D353&l✔ &7Signed in! Sending you to the server…";
    public String passwordAccepted = "&#39D353&l✔ &7Password correct!";
    public String passwordCreated = "&#39D353&l✔ &7Password created!";
    public String passwordChanged = "&#39D353&l✔ &7Password changed.";
    public String passwordWrong = "&c✘ Wrong password&8. Try again: &f/%login_command% <password>&8.";
    public String passwordBlocked = "&c⏳ Too many failed attempts&8. Try again in &f%seconds% s&8.";
    public String passwordUsage = "&cUsage: &f/%register_command% <password> <password> &cor &f/%login_command% <password>&8.";
    public String passwordTitle = "&#39D353&lAUTHORIZATION";
    public String passwordSubtitle = "&7Enter your password in chat";
    public String passwordActionBar = "&8/%login_command% <password> &7or &8/%register_command% <password> <password>";
    public String passwordMismatch = "&cPasswords do not match&8.";
    public String passwordTooShort = "&cPassword too short&8 (minimum &f%min%&8 characters).";
    public String passwordTooLong = "&cPassword too long&8 (maximum &f%max%&8 characters).";
    public String passwordAlreadyRegistered = "&cA password already exists&8. Use &f/%login_command% <password>&8.";
    public String passwordNotRegistered = "&cCreate a password first&8: &f/%register_command% <password> <password>&8.";
    public String discordWrongCode = "❌ Invalid or expired code.";
    public String discordUserLinkedToOther = "❌ Your Discord is already linked to the account **%player%**.";
    public String discordAccountLimit = "❌ This Discord already has the maximum of **%max_total%** Minecraft accounts.";
    public String discordJavaAccountLimit = "❌ This Discord already has its Java account.";
    public String discordBedrockAccountLimit = "❌ This Discord already has its Bedrock account.";
    public String discordGuildOnly = "❌ This command only works on the project's Discord server.";
    public String discordRateLimited = "⏳ Too many failed attempts.";
    public String discordNoAccount = "❌ Your Discord is not linked to a Minecraft account.";
    public String discordAccountListHeader = "🔗 **Linked accounts**";
    public String discordTargetNotYours = "❌ **%player%** is not linked to your Discord.";
    public String discordChooseAccount = "ℹ️ You have several linked accounts.";
    public String discordNoPassword = "❌ The account **%player%** has no password yet.";
    public String discordPasswordChanged = "✅ Password for **%player%** has been changed.";
    public String discordUnlinkMismatch = "❌ Nickname does not match.";
    public String discordUnlinked = "✅ Account **%player%** has been unlinked from Discord.";
    public String discordLinkPlayerOnly = "❌ Only an in-game player can request a Discord link code.";
    public String discordLinkAlready = "✅ **%player%** is already linked to Discord.";
    public String discordLinkPrompt = "🔗 **Discord link code:** `%code%`\nRun `/verify %code%` in Discord.";
    public String discordLinkSuccess = "✅ Linked **%player%** to your Discord.";
    public String discordLinkSuccessPlayer = "✅ **%player%** is now linked to Discord.";
    public String discordAdminOnly = "❌ This command is available to administrators only.";
    public String discordTargetNotLinked = "❌ The player **%player%** is not linked to any Discord account.";
    public String discordResetDone = "✅ Password for **%player%** has been changed by an administrator.";
    public String passwordChangedDiscord = "&#39D353&l✔ &7Password changed via Discord&8.";
    public String banKickMessage = "&c&lYOU ARE BANNED\n&7Reason: &f%reason%";
    public String kickMessage = "&c&lKICKED\n&7Reason: &f%reason%";
    public String discordBanDone = "✅ **%player%** has been banned permanently.";
    public String discordTempBanDone = "✅ **%player%** has been banned.";
    public String discordUnbanDone = "✅ Ban for **%player%** has been lifted.";
    public String discordBanNotFound = "❌ No ban found for **%player%**.";
    public String discordBanAlready = "❌ **%player%** is already banned.";
    public String discordBanSelf = "❌ You cannot ban yourself.";
    public String discordKicked = "✅ **%player%** has been kicked.";
    public String discordKickOffline = "❌ **%player%** is not online.";
    public String discordBanListEmpty = "✅ No active bans.";
    public String discordBanListHeader = "📋 **Active bans (%count%):**";
    public String discordBanListEntry = "• **%player%** — %reason% (until %expires%)";
    public String discordDurationInvalid = "❌ Invalid duration.";
    public String discordAccountInfo = "**%player%** — password: %password%, linked: %linked%";
    public String discordAccountPasswordYes = "✔ set";
    public String discordAccountPasswordNo = "— not set";
    public String discordInvite = "&#39D353&lDISCORD &8— &f%discord_link%";
    public String discordPasswordMismatch = "❌ Passwords do not match.";
    public String discordPasswordRule = "❌ Password must be %min%-%max% characters.";
    public String discordPasswordWrongOld = "❌ Wrong current password.";
    public String discordReasonNone = "no reason";
    public String banExpiresNever = "never";
    public String dialogLoginTitle = "Login";
    public String dialogLoginBody = "Please enter your password to continue.";
    public String dialogPasswordLabel = "Password";
    public String dialogLoginButton = "Login";
    public String dialogCancelButton = "Cancel";
    public String dialogDiscordButton = "Discord";
    public String dialogRegisterTitle = "Create a password";
    public String dialogRegisterBody = "Choose a password to protect your account.";
    public String dialogConfirmLabel = "Confirm password";
    public String dialogRegisterButton = "Create";
    public String dialogVerifyTitle = "Verify your account";
    public String dialogVerifyBody = "Join our Discord and run /verify %code% there.";
    public String dialogCodeLabel = "Your code";
    public String dialogCopyCodeButton = "Copy code";
    public String dialogVerifiedButton = "I've verified";
    public String dialogNotVerified = "Still not verified.";
    public String dialogPasswordMismatch = "Passwords do not match.";
    public String dialogPasswordTooShort = "Password must be at least %min% characters.";
    public String dialogPasswordTooLong = "Password must be at most %max% characters.";
    public String dialogCancelMessage = "&cLogin cancelled.";
    public String dialogStorageError = "&4&lAUTH UNAVAILABLE";
    public String dialogTimeout = "&4&lLogin timed out";
    public String dialogKickOnly = "&4&lLOGIN REQUIRED";
    public String dialogAuthAvailable = "&#39D353&l✔ &7Signed in!";
  }

  // ---- Load / Save ----

  public static Config load(Path dataDirectory, Logger logger) {
    Path yamlFile = dataDirectory.resolve("config.yml");
    Path jsonFile = dataDirectory.resolve("config.json");
    Config config = new Config();
    try {
      Files.createDirectories(dataDirectory);
      // Migration: if config.json exists but config.yml doesn't, migrate
      if (Files.exists(jsonFile) && !Files.exists(yamlFile)) {
        migrateJsonToYaml(jsonFile, yamlFile, logger);
      }
      if (Files.exists(yamlFile)) {
        try (Reader reader = Files.newBufferedReader(yamlFile, StandardCharsets.UTF_8)) {
          Object loaded = YAML.load(reader);
          if (loaded instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) loaded;
            String json = GSON.toJson(map);
            Config parsed = GSON.fromJson(json, Config.class);
            if (parsed != null) {
              config = parsed;
            }
          }
        }
      } else {
        // First start: write default config.yml from template
        writeDefaultConfig(yamlFile, logger);
        // Re-load
        try (Reader reader = Files.newBufferedReader(yamlFile, StandardCharsets.UTF_8)) {
          Object loaded = YAML.load(reader);
          if (loaded instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) loaded;
            String json = GSON.toJson(map);
            Config parsed = GSON.fromJson(json, Config.class);
            if (parsed != null) {
              config = parsed;
            }
          }
        }
      }
      config.applyDefaults();
      // Write back (normalizes format)
      saveYaml(yamlFile, config, logger);
      Secrets.migrateLegacyToken(dataDirectory, logger);
    } catch (IOException exception) {
      logger.error("Unable to read/write config.yml, using defaults", exception);
    }
    return config;
  }

  private static void migrateJsonToYaml(Path jsonFile, Path yamlFile, Logger logger) {
    try {
      // Read JSON
      String json = Files.readString(jsonFile, StandardCharsets.UTF_8);
      @SuppressWarnings("unchecked")
      Map<String, Object> map = GSON.fromJson(json, Map.class);
      if (map == null) return;
      // Write as YAML
      try (Writer writer = Files.newBufferedWriter(yamlFile, StandardCharsets.UTF_8)) {
        YAML.dump(map, writer);
      }
      logger.info("Migrated config.json to config.yml. You can delete config.json manually.");
    } catch (Exception e) {
      logger.warn("Unable to migrate config.json to config.yml: " + e.getMessage());
    }
  }

  private static void writeDefaultConfig(Path yamlFile, Logger logger) {
    // Write a minimal default config (Gson will fill defaults on load)
    Config defaults = new Config();
    saveYaml(yamlFile, defaults, logger);
  }

  public static void saveYaml(Path file, Config config, Logger logger) {
    try {
      // Convert Config to Map via JSON
      String json = GSON.toJson(config);
      @SuppressWarnings("unchecked")
      Map<String, Object> map = GSON.fromJson(json, Map.class);
      try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
        YAML.dump(map, writer);
      }
      protect(file);
    } catch (IOException exception) {
      logger.error("Unable to write config.yml", exception);
    }
  }

  public static void protect(Path file) {
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    } catch (Throwable ignored) {
    }
  }

  private void applyDefaults() {
    if (this.platform == null || this.platform.isBlank()) this.platform = "auto";
    this.platform = this.platform.trim().toLowerCase(Locale.ROOT);
    if (!this.platform.equals("auto") && !this.platform.equals("velocity") && !this.platform.equals("paper")) {
      this.platform = "auto";
    }
    if (this.role == null || this.role.isBlank()) this.role = "auth";
    this.role = this.role.trim().toLowerCase(Locale.ROOT);
    if (!this.role.equals("auth") && !this.role.equals("bridge")) this.role = "auth";
    if (this.ui == null || this.ui.isBlank()) this.ui = "auto";
    this.ui = this.ui.trim().toLowerCase(Locale.ROOT);
    if (!this.ui.equals("auto") && !this.ui.equals("dialog") && !this.ui.equals("chat")) this.ui = "auto";

    if (this.discord == null) this.discord = new Discord();
    if (this.auth == null) this.auth = new Auth();
    if (this.limbo == null) this.limbo = new LimboSettings();
    if (this.paper == null) this.paper = new PaperSettings();
    if (this.tab == null) this.tab = new Tab();
    if (this.bridge == null) this.bridge = new Bridge();
    if (this.commands == null) this.commands = new Commands();
    if (this.password == null) this.password = new Password();
    if (this.messages == null) this.messages = new Messages();

    this.paper.dialogColumns = Math.max(1, Math.min(4, this.paper.dialogColumns));
    this.paper.dialogMinProtocol = Math.max(0, this.paper.dialogMinProtocol);
    this.auth.codeTtlSeconds = Math.max(30, this.auth.codeTtlSeconds);
    this.auth.codeLength = Math.max(4, Math.min(10, this.auth.codeLength));
    this.password.minLength = Math.max(1, this.password.minLength);
    this.password.maxLength = Math.max(this.password.minLength, this.password.maxLength);
    this.password.maxAttempts = Math.max(1, this.password.maxAttempts);
    this.password.cooldownSeconds = Math.max(0, this.password.cooldownSeconds);
    this.password.ipMaxAttempts = Math.max(0, this.password.ipMaxAttempts);
    this.password.ipCooldownSeconds = Math.max(0, this.password.ipCooldownSeconds);
    this.password.timeoutSeconds = Math.max(10, this.password.timeoutSeconds);
    this.auth.maxAttemptsPerUser = Math.max(1, this.auth.maxAttemptsPerUser);
    this.auth.attemptCooldownSeconds = Math.max(0, this.auth.attemptCooldownSeconds);
    this.auth.transferDelayMillis = Math.max(0, this.auth.transferDelayMillis);
    this.auth.transferReconnectTimeoutMillis = Math.max(1000, this.auth.transferReconnectTimeoutMillis);
    this.tab.tabPluginPlaceholderRefreshMillis = Math.max(50, this.tab.tabPluginPlaceholderRefreshMillis);
    this.discord.maxAccountsPerDiscord = Math.max(1, this.discord.maxAccountsPerDiscord);
    this.discord.maxJavaAccountsPerDiscord = Math.max(0, this.discord.maxJavaAccountsPerDiscord);
    this.discord.maxBedrockAccountsPerDiscord = Math.max(0, this.discord.maxBedrockAccountsPerDiscord);
    this.discord.identityRefreshMinutes = Math.max(0, this.discord.identityRefreshMinutes);

    if (this.auth.bedrockUsernamePrefixes == null) this.auth.bedrockUsernamePrefixes = new ArrayList<>(List.of("."));
    if (this.auth.verificationBypassNames == null) this.auth.verificationBypassNames = new ArrayList<>();
    if (this.discord.adminUserIds == null) this.discord.adminUserIds = new ArrayList<>();
    if (this.commands.register == null || this.commands.register.isEmpty()) this.commands.register = new ArrayList<>(List.of("register", "reg", "r"));
    if (this.commands.login == null || this.commands.login.isEmpty()) this.commands.login = new ArrayList<>(List.of("login", "l"));
    if (this.commands.admin == null || this.commands.admin.isEmpty()) this.commands.admin = new ArrayList<>(List.of("discordauth", "dauth"));
    if (this.commands.link == null || this.commands.link.isEmpty()) this.commands.link = new ArrayList<>(List.of("discordlink", "linkdiscord"));
    if (this.commands.discord == null || this.commands.discord.isEmpty()) this.commands.discord = new ArrayList<>(List.of("discord", "dc"));

    this.auth.bedrockUsernamePrefixes = normalizeList(this.auth.bedrockUsernamePrefixes, ".");
    this.auth.verificationBypassNames = normalizeList(this.auth.verificationBypassNames, null);
    this.discord.adminUserIds = normalizeList(this.discord.adminUserIds, null);
    this.commands.register = normalizeList(this.commands.register, "register");
    this.commands.login = normalizeList(this.commands.login, "login");
    this.commands.admin = normalizeList(this.commands.admin, "discordauth");
    this.commands.link = normalizeList(this.commands.link, "discordlink");
    this.commands.discord = normalizeList(this.commands.discord, "discord");

    if (this.discord.guildId == null) this.discord.guildId = "";
    if (this.discord.verifiedRoleId == null) this.discord.verifiedRoleId = "";
    if (this.discord.adminRoleId == null) this.discord.adminRoleId = "";
    if (this.discord.inviteUrl == null) this.discord.inviteUrl = "";
    if (this.auth.mainServer == null || this.auth.mainServer.isBlank()) this.auth.mainServer = "main";
    if (this.auth.transferAddress == null) this.auth.transferAddress = "";
    if (this.auth.transferFallbackAddress == null || this.auth.transferFallbackAddress.isBlank()) this.auth.transferFallbackAddress = "127.0.0.1:25565";
    if (this.limbo.dimension == null || this.limbo.dimension.isBlank()) this.limbo.dimension = "THE_END";
    if (this.tab.nameSource == null || this.tab.nameSource.isBlank()) this.tab.nameSource = "nickname";
    if (this.tab.format == null) this.tab.format = "&#39D353%player%\n&8&o%discord%";
    if (this.tab.hover == null) this.tab.hover = "&8Minecraft&7: &f%player%\n&#39D353Discord&7: &f%discord%\n&8Discord ID&7: &f%discord_id%";
    if (this.tab.unknownDiscord == null) this.tab.unknownDiscord = "unknown";
    if (this.bridge.channel == null || this.bridge.channel.isBlank()) this.bridge.channel = "discordauth:identity";
    if (this.bridge.placeholderPrefix == null || this.bridge.placeholderPrefix.isBlank()) this.bridge.placeholderPrefix = "discordauth";
    if (this.bridge.unlinkedPlaceholder == null) this.bridge.unlinkedPlaceholder = "";
    if (this.messages.title == null) this.messages.title = "";
    if (this.messages.subtitle == null) this.messages.subtitle = "";
  }

  private static List<String> normalizeList(List<String> list, String fallback) {
    if (list == null) list = new ArrayList<>();
    List<String> result = new ArrayList<>();
    for (String item : list) {
      if (item == null) continue;
      String trimmed = item.trim();
      if (!trimmed.isEmpty() && !result.contains(trimmed)) result.add(trimmed);
    }
    if (result.isEmpty() && fallback != null) result.add(fallback);
    return result;
}
}
