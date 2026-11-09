package net.omegasmp.discordauth.paper;

import java.util.Locale;
import java.util.function.Function;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.omegasmp.discordauth.core.Config;
import org.bukkit.entity.Player;

/**
 * PlaceholderAPI expansion for both Paper modes.
 *
 * <p>{@code role=auth}: the values come from the local {@code links.json}. {@code role=bridge}:
 * they come from the identity cache filled by the proxy. The class is only touched when
 * PlaceholderAPI is installed, so the plugin still runs without it.</p>
 */
public final class PaperPlaceholders extends PlaceholderExpansion {

  private final DiscordAuthPaper plugin;
  private final Config config;
  private final Function<java.util.UUID, PaperIdentity> identities;

  public PaperPlaceholders(DiscordAuthPaper plugin, Config config,
      Function<java.util.UUID, PaperIdentity> identities) {
    this.plugin = plugin;
    this.config = config;
    this.identities = identities;
  }

  @Override
  public String getIdentifier() {
    String prefix = this.config.bridge.placeholderPrefix;
    return prefix == null || prefix.isBlank() ? "discordauth" : prefix;
  }

  @Override
  public String getAuthor() {
    return "OmegaSMP";
  }

  @Override
  public String getVersion() {
    return this.plugin.getPluginMeta().getVersion();
  }

  @Override
  public boolean persist() {
    return true;
  }

  @Override
  public String onPlaceholderRequest(Player player, String params) {
    if (player == null) {
      return "";
    }
    PaperIdentity identity = this.identities.apply(player.getUniqueId());
    boolean linked = identity != null && identity.linked();
    switch (params.toLowerCase(Locale.ROOT)) {
      case "linked":
        return linked ? "yes" : "no";
      case "id":
      case "discord_id":
        return linked ? value(identity.discordId()) : this.unlinked();
      case "username":
      case "user":
        return linked ? value(identity.username()) : this.unlinked();
      case "nick":
      case "nickname":
        return linked ? value(identity.nick()) : this.unlinked();
      case "global":
      case "globalname":
        return linked ? value(identity.global()) : this.unlinked();
      case "name":
      case "display":
        return linked ? identity.displayName(this.stripUnsupported()) : this.unlinked();
      default:
        return null;
    }
  }

  private String unlinked() {
    String value = this.config.bridge.unlinkedPlaceholder;
    return value == null ? "" : value;
  }

  private boolean stripUnsupported() {
    return this.config.tab.stripUnsupported;
  }

  private String value(String raw) {
    if (raw == null) {
      return "";
    }
    return this.stripUnsupported() ? net.omegasmp.discordauth.core.Text.stripUnsupported(raw) : raw;
  }
}
