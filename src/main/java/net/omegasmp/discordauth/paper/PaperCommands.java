package net.omegasmp.discordauth.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.omegasmp.discordauth.core.AuthSession;
import net.omegasmp.discordauth.core.Config;
import net.omegasmp.discordauth.core.LinkStore;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * Registers every runtime command of the Paper plugin:
 * {@code /login}, {@code /register}, {@code /discordlink}, {@code /discord} and the admin command.
 *
 * <p>Commands are registered through {@link org.bukkit.command.CommandMap} so the aliases from
 * {@code config.json} work without touching {@code plugin.yml}.</p>
 */
public final class PaperCommands implements CommandExecutor, TabCompleter {

  private final DiscordAuthPaper plugin;
  private final PaperHost host;

  public PaperCommands(DiscordAuthPaper plugin, PaperHost host) {
    this.plugin = plugin;
    this.host = host;
  }

  public void register() {
    Config config = this.plugin.config();
    this.registerAliases(config.commands.login, "Enter your password", "login");
    this.registerAliases(config.commands.register, "Create a password", "register");
    this.registerAliases(config.commands.link, "Link your Discord account", "discordlink");
    this.registerAliases(config.commands.discord, "Get the Discord invite", "discord");
    if (config.commands.admin != null && !config.commands.admin.isEmpty()) {
      String primary = config.commands.admin.get(0);
      Command command = new Command(primary, "DiscordAuth administration",
          "/" + primary + " <status|unlink|password|unregister|list>", config.commands.admin) {
        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
          return PaperCommands.this.executeAdmin(sender, args);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
          return PaperCommands.this.onTabComplete(sender, this, alias, args);
        }
      };
      command.setPermission("discordauth.admin");
      this.register(command, primary);
    }
  }

  /** Registers a command under the plugin namespace so Purpur's commands.yml cannot steal it. */
  private void register(Command command, String name) {
    try {
      Bukkit.getCommandMap().register("discordauth", command);
      return;
    } catch (Throwable throwable) {
      this.host.logger().warn("Unable to register /{} under the plugin namespace, retrying with the plugin name",
          name, throwable);
    }
    try {
      Bukkit.getCommandMap().register(this.plugin.getName(), command);
    } catch (Throwable throwable) {
      this.host.logger().error("Unable to register the command /{} — players will not be able to use it", name,
          throwable);
    }
  }

  private void registerAliases(List<String> names, String description, String kind) {
    if (names == null || names.isEmpty()) {
      return;
    }
    String primary = names.get(0);
    Command command = new Command(primary, description, "/" + primary, names) {
      @Override
      public boolean execute(CommandSender sender, String label, String[] args) {
        return PaperCommands.this.executePlayerCommand(sender, kind, args);
      }

      @Override
      public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        return List.of();
      }
    };
    this.register(command, primary);
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    return this.executePlayerCommand(sender, command.getName(), args);
  }

  private boolean executePlayerCommand(CommandSender sender, String kind, String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(Component.text("This command can only be used by a player.", NamedTextColor.RED));
      return true;
    }
    Config config = this.plugin.config();
    String lower = kind.toLowerCase(Locale.ROOT);

    if (config.commands.discord.contains(lower)) {
      player.sendMessage(net.omegasmp.discordauth.core.Text.build(
          config.messages.discordInvite, config, null));
      return true;
    }
    if (config.commands.link.contains(lower)) {
      this.startLink(player);
      return true;
    }

    AuthSession session = this.host.sessions().byPlayer(player.getUniqueId());
    if (session == null) {
      player.sendMessage(Component.text("You are already signed in.", NamedTextColor.GRAY));
      return true;
    }
    if (config.commands.login.contains(lower)) {
      Component message = this.host.service().login(session, prepend(lower, args));
      this.send(player, session, message);
      return true;
    }
    if (config.commands.register.contains(lower)) {
      Component message = this.host.service().register(session, prepend(lower, args));
      this.send(player, session, message);
      return true;
    }
    this.host.send(session, this.host.service().hint(session));
    return true;
  }

  private static String[] prepend(String label, String[] args) {
    String[] result = new String[args.length + 1];
    result[0] = label;
    System.arraycopy(args, 0, result, 1, args.length);
    return result;
  }

  private void send(Player player, AuthSession session, Component message) {
    if (message != null) {
      player.sendMessage(message);
    }
  }

  /** {@code /discordlink}: create a code that an already playing player types in Discord. */
  private void startLink(Player player) {
    Config config = this.plugin.config();
    if (this.host.links().isLinked(player.getUniqueId())) {
      player.sendMessage(net.omegasmp.discordauth.core.Text.component(net.omegasmp.discordauth.core.Text
          .render(config.messages.discordLinkAlready, config, player.getName(), null, 0)));
      return;
    }
    for (AuthSession pending : this.host.sessions().allLinks()) {
      if (pending.getUuid().equals(player.getUniqueId())) {
        this.host.sessions().removeLink(pending.getCode());
      }
    }
    AuthSession session = this.host.service().createLinkSession(player.getUniqueId(), player.getName());
    session.setHandle(player);
    player.sendMessage(net.omegasmp.discordauth.core.Text.build(
        config.messages.discordLinkPrompt, config, session));
    this.host.logger().info("{} requested a Discord link code from the game.", player.getName());
  }

  // ------------------------------------------------------------------ admin command

  private boolean executeAdmin(CommandSender sender, String[] args) {
    if (!sender.hasPermission("discordauth.admin")) {
      sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
      return true;
    }
    if (args.length < 2) {
      sender.sendMessage(Component.text(
          "Usage: /discordauth <status|unlink|password|unregister|list> <player>", NamedTextColor.YELLOW));
      return true;
    }
    String sub = args[0].toLowerCase(Locale.ROOT);
    String target = args[1];
    LinkStore store = this.host.links();

    switch (sub) {
      case "status" -> {
        LinkStore.Link link = store.getLinkByName(target);
        if (link == null) {
          sender.sendMessage(Component.text(target + " is not linked to Discord.", NamedTextColor.RED));
          return true;
        }
        sender.sendMessage(Component.text(target + " -> Discord ID " + link.discordId
            + (link.discordName == null || link.discordName.isBlank() ? "" : " (@" + link.discordName + ")"),
            NamedTextColor.GREEN));
      }
      case "unlink" -> {
        String uuid = store.getUuidByName(target);
        if (uuid == null) {
          sender.sendMessage(Component.text("No link found for " + target, NamedTextColor.RED));
          return true;
        }
        UUID id = UUID.fromString(uuid);
        store.unlink(id);
        this.host.identityChanged(id);
        sender.sendMessage(Component.text("Link for " + target + " removed.", NamedTextColor.GREEN));
      }
      case "password" -> {
        String uuid = store.getUuidByName(target);
        if (uuid == null) {
          sender.sendMessage(Component.text("No account for " + target + " (not linked).", NamedTextColor.RED));
          return true;
        }
        boolean registered = this.host.accounts().isRegistered(UUID.fromString(uuid));
        sender.sendMessage(Component.text(target + (registered ? " has a password."
            : " has no password yet."), registered ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
      }
      case "unregister" -> {
        String uuid = store.getUuidByName(target);
        UUID id = uuid == null ? null : UUID.fromString(uuid);
        if (id == null && target.length() == 36) {
          try {
            id = UUID.fromString(target);
          } catch (IllegalArgumentException ignored) {
            // not a UUID after all
          }
        }
        if (id == null || !this.host.accounts().unregister(id)) {
          sender.sendMessage(Component.text("No password for " + target, NamedTextColor.RED));
          return true;
        }
        sender.sendMessage(Component.text(
            "Password for " + target + " removed - the player will create a new one with /register.",
            NamedTextColor.GREEN));
      }
      case "list" -> {
        sender.sendMessage(Component.text("Linked accounts: " + store.size()
            + ", with a password: " + this.host.accounts().size(), NamedTextColor.AQUA));
        for (AuthSession session : this.host.sessions().all()) {
          sender.sendMessage(Component.text("  " + session.getUsername() + " -> code " + session.getCode()
              + (session.needsPassword() ? " + password" : ""), NamedTextColor.GRAY));
        }
      }
      default -> sender.sendMessage(Component.text(
          "Unknown action: " + sub + " (status|unlink|password|unregister|list)", NamedTextColor.RED));
    }
    return true;
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    if (!sender.hasPermission("discordauth.admin")) {
      return List.of();
    }
    if (args.length == 1) {
      return List.of("status", "unlink", "password", "unregister", "list").stream()
          .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
          .toList();
    }
    if (args.length == 2) {
      List<String> names = new ArrayList<>();
      for (Player player : Bukkit.getOnlinePlayers()) {
        names.add(player.getName());
      }
      return names.stream()
          .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
          .toList();
    }
    return List.of();
  }
}
