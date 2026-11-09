package net.omegasmp.discordauth.velocity;

import java.time.Duration;
import java.util.Locale;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.Title.Times;
import net.omegasmp.discordauth.core.AuthSession;
import net.omegasmp.discordauth.core.Config;
import net.omegasmp.discordauth.core.Text;

/**
 * One player held in the Velocity limbo.
 *
 * <p>On the proxy the client dialog cannot deliver a password to the plugin, so the UI is the
 * classic chat/title flow. The order matches the Paper dialog: Discord verification first (on the
 * very first join), then the password.</p>
 */
public class VelocityLimboHandler implements LimboSessionHandler {

  private final VelocityHost host;
  private final AuthSession session;
  private volatile LimboPlayer limboPlayer;

  public VelocityLimboHandler(VelocityHost host, AuthSession session) {
    this.host = host;
    this.session = session;
  }

  @Override
  public void onSpawn(Limbo limbo, LimboPlayer player) {
    this.limboPlayer = player;
    player.disableFalling();
    this.host.registerHandler(this.session.getUuid(), this);
    this.showUi();
  }

  /** (Re)draws the prompt for whatever step is pending right now. */
  public void showUi() {
    LimboPlayer player = this.limboPlayer;
    if (player == null) {
      return;
    }
    Config config = this.host.config();
    String name = this.session.getUsername();
    String code = this.session.getCode();

    if (this.session.needsDiscord()) {
      this.send(Text.build(config.messages.chat, config, this.session));
      this.showTitle(player, config.messages.title, config.messages.subtitle, name, code);
      this.showActionBar(player, config.messages.actionBar, name, code);
    }
    if (this.session.needsPassword()) {
      if (!this.session.needsDiscord()) {
        // Password only: the code is irrelevant, so show a neutral title instead.
        this.showTitle(player, config.messages.passwordTitle, config.messages.passwordSubtitle, name, code);
      }
      this.send(this.host.service().passwordPrompt(this.session.getUuid()));
      this.showActionBar(player, config.messages.passwordActionBar, name, code);
    }
  }

  private void showTitle(LimboPlayer player, String titleTemplate, String subtitleTemplate,
      String playerName, String code) {
    if ((titleTemplate == null || titleTemplate.isBlank())
        && (subtitleTemplate == null || subtitleTemplate.isBlank())) {
      return;
    }
    Config config = this.host.config();
    Component title = Text.component(Text.render(titleTemplate, config, playerName, code, 0));
    Component subtitle = Text.component(Text.render(subtitleTemplate, config, playerName, code, 0));
    player.getProxyPlayer().showTitle(Title.title(title, subtitle,
        Times.times(Duration.ofMillis(400), Duration.ofSeconds(12), Duration.ofMillis(800))));
  }

  private void showActionBar(LimboPlayer player, String template, String playerName, String code) {
    if (template == null || template.isBlank()) {
      return;
    }
    player.getProxyPlayer().sendActionBar(
        Text.component(Text.render(template, this.host.config(), playerName, code, 0)));
  }

  @Override
  public void onChat(String message) {
    if (message == null || this.session.isComplete()) {
      return;
    }
    String trimmed = message.trim();
    if (!trimmed.startsWith("/")) {
      this.send(this.host.service().hint(this.session));
      return;
    }
    String commandLine = trimmed.substring(1).trim();
    if (commandLine.isEmpty()) {
      this.send(this.host.service().hint(this.session));
      return;
    }
    String[] args = commandLine.split("\\s+");
    String name = args[0].toLowerCase(Locale.ROOT);
    Config config = this.host.config();

    if (config.commands.login.contains(name)) {
      this.send(this.host.service().login(this.session, args));
      return;
    }
    if (config.commands.register.contains(name)) {
      this.send(this.host.service().register(this.session, args));
      return;
    }
    this.send(this.host.service().hint(this.session));
  }

  @Override
  public void onDisconnect() {
    this.host.unregisterHandler(this.session.getUuid(), this);
    if (!this.session.isComplete()) {
      this.host.service().dropSession(this.session);
    }
  }

  private void send(Component component) {
    LimboPlayer player = this.limboPlayer;
    if (component == null || player == null) {
      return;
    }
    try {
      player.getProxyPlayer().sendMessage(component);
    } catch (Throwable throwable) {
      // The connection may already be gone; nothing useful to do here.
    }
  }
}
