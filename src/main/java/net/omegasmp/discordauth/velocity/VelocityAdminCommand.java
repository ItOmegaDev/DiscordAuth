package net.omegasmp.discordauth.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.omegasmp.discordauth.core.AccountStore;
import net.omegasmp.discordauth.core.AuthSession;
import net.omegasmp.discordauth.core.Config;
import net.omegasmp.discordauth.core.LinkStore;

/**
 * {@code /discordauth status|unlink|password|unregister|list <player>} — the same management
 * command the 1.x plugin had, now backed by the shared stores.
 */
public class VelocityAdminCommand implements SimpleCommand {

  private final VelocityHost host;
  private final Config config;

  public VelocityAdminCommand(VelocityHost host) {
    this.host = host;
    this.config = host.config();
  }

  @Override
  public void execute(Invocation invocation) {
    CommandSource source = invocation.source();
    String[] args = invocation.arguments();

    if (args.length < 2) {
      source.sendMessage(Component.text(
          "Usage: /" + this.prefix() + " <status|unlink|password|unregister|list> <player>",
          NamedTextColor.YELLOW));
      return;
    }

    String sub = args[0].toLowerCase(Locale.ROOT);
    String target = args[1];
    LinkStore store = this.host.links();
    AccountStore accounts = this.host.accounts();

    switch (sub) {
      case "status" -> {
        LinkStore.Link link = store.getLinkByName(target);
        if (link == null) {
          source.sendMessage(Component.text(target + " is not linked to Discord.", NamedTextColor.RED));
          return;
        }
        source.sendMessage(Component.text(target + " -> Discord ID " + link.discordId
            + (link.discordName == null || link.discordName.isBlank() ? "" : " (@" + link.discordName + ")"),
            NamedTextColor.GREEN));
      }
      case "unlink" -> {
        String uuid = store.getUuidByName(target);
        if (uuid == null) {
          source.sendMessage(Component.text("No link found for " + target, NamedTextColor.RED));
          return;
        }
        UUID id = UUID.fromString(uuid);
        store.unlink(id);
        this.host.identityChanged(id);
        source.sendMessage(Component.text("Link for " + target + " removed.", NamedTextColor.GREEN));
      }
      case "password" -> {
        String uuid = store.getUuidByName(target);
        if (uuid == null) {
          source.sendMessage(Component.text("No account for " + target + " (not linked).", NamedTextColor.RED));
          return;
        }
        boolean registered = accounts.isRegistered(UUID.fromString(uuid));
        source.sendMessage(Component.text(target + (registered ? " has a password."
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
        if (id == null || !accounts.unregister(id)) {
          source.sendMessage(Component.text("No password for " + target, NamedTextColor.RED));
          return;
        }
        source.sendMessage(Component.text(
            "Password for " + target + " removed - the player will create a new one with /register.",
            NamedTextColor.GREEN));
      }
      case "list" -> {
        source.sendMessage(Component.text("Linked accounts: " + store.size()
            + ", with a password: " + accounts.size(), NamedTextColor.AQUA));
        for (AuthSession session : this.host.sessions().all()) {
          source.sendMessage(Component.text("  " + session.getUsername() + " -> code " + session.getCode()
              + (session.needsPassword() ? " + password" : ""), NamedTextColor.GRAY));
        }
      }
      default -> source.sendMessage(Component.text(
          "Unknown action: " + sub + " (status|unlink|password|unregister|list)", NamedTextColor.RED));
    }
  }

  @Override
  public boolean hasPermission(Invocation invocation) {
    return invocation.source().hasPermission("discordauth.admin");
  }

  private String prefix() {
    java.util.List<String> names = this.config.commands.admin;
    return names == null || names.isEmpty() ? "discordauth" : names.get(0);
  }
}
