package net.omegasmp.discordauth.paper;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.omegasmp.discordauth.core.AuthSession;
import net.omegasmp.discordauth.core.Config;
import net.omegasmp.discordauth.core.Text;
import org.bukkit.entity.Player;

/**
 * Paper client dialogs (Minecraft 1.21.6+).
 *
 * <p>The login screen is the one from the reference screenshot: a {@code Login} title, the
 * "Please enter your password to continue." body, a single password field, a green Login button,
 * a red Cancel button and an extra Discord button. Older clients get the chat UI instead, because
 * the server cannot show a dialog to them.</p>
 *
 * <p>Passwords never travel through chat or commands: the dialog sends them to the plugin through
 * a custom click callback, and the plugin hands them straight to the BCrypt verifier.</p>
 */
public final class PaperDialogs {

  private static final String INPUT_PASSWORD = "password";
  private static final String INPUT_CONFIRM = "confirm";

  private final PaperHost host;

  public PaperDialogs(PaperHost host) {
    this.host = host;
  }

  /** Shows the dialog (or the chat fallback) for the step the session is currently on. */
  public void show(AuthSession session) {
    if (session == null || session.isComplete()) {
      return;
    }
    Player player = this.host.player(session);
    if (player == null || !player.isOnline()) {
      return;
    }
    if (!this.usesDialogs(player)) {
      this.showChatFallback(session, player);
      return;
    }
    if (session.needsDiscord()) {
      this.showVerify(session, player);
    } else if (session.needsPassword()) {
      if (this.host.accounts().isRegistered(session.getUuid())) {
        this.showLogin(session, player);
      } else {
        this.showRegister(session, player);
      }
    }
  }

  /** {@code ui=chat} always wins; {@code auto} checks the client protocol. */
  public boolean usesDialogs(Player player) {
    Config config = this.host.config();
    if ("chat".equalsIgnoreCase(config.ui)) {
      return false;
    }
    if ("dialog".equalsIgnoreCase(config.ui)) {
      return true;
    }
    return player.getProtocolVersion() >= Math.max(0, config.paper.dialogMinProtocol);
  }

  // ------------------------------------------------------------------ login dialog

  private void showLogin(AuthSession session, Player player) {
    Config config = this.host.config();
    Config.Messages messages = config.messages;

    List<DialogInput> inputs = List.of(
        DialogInput.text(INPUT_PASSWORD, Text.component(messages.dialogPasswordLabel))
            .labelVisible(true)
            .maxLength(config.password.maxLength)
            .width(220)
            .build());

    List<ActionButton> actions = new ArrayList<>();
    actions.add(this.button(
        Text.component(messages.dialogLoginButton).color(NamedTextColor.GREEN),
        view -> {
          String password = view.getText(INPUT_PASSWORD);
          if (password == null || password.isEmpty()) {
            return;
          }
          this.host.service().submitPassword(session, password);
        }));
    this.addDiscordButton(actions);

    this.show(player, messages.dialogLoginTitle, messages.dialogLoginBody, inputs, actions,
        this.cancelButton(session), config.paper.dialogColumns);
  }

  // --------------------------------------------------------------- register dialog

  private void showRegister(AuthSession session, Player player) {
    Config config = this.host.config();
    Config.Messages messages = config.messages;

    List<DialogInput> inputs = List.of(
        DialogInput.text(INPUT_PASSWORD, Text.component(messages.dialogPasswordLabel))
            .labelVisible(true)
            .maxLength(config.password.maxLength)
            .width(220)
            .build(),
        DialogInput.text(INPUT_CONFIRM, Text.component(messages.dialogConfirmLabel))
            .labelVisible(true)
            .maxLength(config.password.maxLength)
            .width(220)
            .build());

    List<ActionButton> actions = new ArrayList<>();
    actions.add(this.button(
        Text.component(messages.dialogRegisterButton).color(NamedTextColor.GREEN),
        view -> {
          String password = view.getText(INPUT_PASSWORD);
          String confirm = view.getText(INPUT_CONFIRM);
          this.submitRegistration(session, player, password, confirm);
        }));
    this.addDiscordButton(actions);

    this.show(player, messages.dialogRegisterTitle, messages.dialogRegisterBody, inputs, actions,
        this.cancelButton(session), config.paper.dialogColumns);
  }

  private void submitRegistration(AuthSession session, Player player, String password, String confirm) {
    Config config = this.host.config();
    Config.Messages messages = config.messages;
    if (password == null || password.isEmpty() || confirm == null || confirm.isEmpty()) {
      return;
    }
    if (!password.equals(confirm)) {
      this.reshow(session, Text.component(messages.dialogPasswordMismatch));
      return;
    }
    if (password.length() < config.password.minLength) {
      this.reshow(session, Text.component(Text.render(messages.dialogPasswordTooShort, config, session, 0)));
      return;
    }
    if (password.length() > config.password.maxLength) {
      this.reshow(session, Text.component(Text.render(messages.dialogPasswordTooLong, config, session, 0)));
      return;
    }
    this.host.service().submitPassword(session, password);
  }

  // ----------------------------------------------------------------- verify dialog

  private void showVerify(AuthSession session, Player player) {
    Config config = this.host.config();
    Config.Messages messages = config.messages;
    String body = Text.render(messages.dialogVerifyBody, config, session, 0);

    List<ActionButton> actions = new ArrayList<>();
    actions.add(this.button(
        Text.component(messages.dialogVerifiedButton).color(NamedTextColor.GREEN),
        view -> {
          if (this.host.service().confirmDiscord(session)) {
            return;
          }
          this.host.send(session, Text.component(
              Text.render(messages.dialogNotVerified, config, session, 0)));
          this.host.scheduler().runLater(() -> this.showVerify(session, player), 300L);
        }));

    String code = session.getCode() == null ? "" : session.getCode();
    if (!code.isEmpty()) {
      actions.add(ActionButton.builder(Text.component(messages.dialogCopyCodeButton))
          .width(150)
          .action(DialogAction.staticAction(ClickEvent.copyToClipboard(code)))
          .build());
    }
    this.addDiscordButton(actions);

    this.show(player, messages.dialogVerifyTitle, body, List.of(), actions,
        this.cancelButton(session), config.paper.dialogColumns);
  }

  // -------------------------------------------------------------------- helpers

  private void addDiscordButton(List<ActionButton> actions) {
    Config config = this.host.config();
    String url = config.discord.inviteUrl == null ? "" : config.discord.inviteUrl.trim();
    if (url.isEmpty()) {
      return;
    }
    actions.add(ActionButton.builder(Text.component(config.messages.dialogDiscordButton))
        .width(150)
        .action(DialogAction.staticAction(ClickEvent.openUrl(url)))
        .build());
  }

  private ActionButton cancelButton(AuthSession session) {
    Config config = this.host.config();
    return this.button(Text.component(config.messages.dialogCancelButton).color(NamedTextColor.RED),
        view -> this.host.disconnect(session,
            Text.build(config.messages.dialogCancelMessage, config, session)));
  }

  private ActionButton button(Component label, Consumer<DialogResponseView> handler) {
    return ActionButton.builder(label)
        .width(150)
        .action(DialogAction.customClick(
            (view, audience) -> handler.accept(view),
            ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(15)).build()))
        .build();
  }

  private void show(Player player, String title, String body, List<DialogInput> inputs,
      List<ActionButton> actions, ActionButton exit, int columns) {
    Component titleComponent = Text.component(title).colorIfAbsent(NamedTextColor.WHITE);
    Component bodyComponent = Text.component(body).colorIfAbsent(NamedTextColor.GRAY);
    DialogBase base = DialogBase.builder(titleComponent)
        .body(List.of(DialogBody.plainMessage(bodyComponent, 320)))
        .inputs(inputs)
        .canCloseWithEscape(false)
        .afterAction(DialogBase.DialogAfterAction.CLOSE)
        .build();
    DialogType type = DialogType.multiAction(actions, exit, Math.max(1, columns));
    Dialog dialog = Dialog.create(factory -> {
      DialogRegistryEntry.Builder builder = factory.empty();
      builder.base(base).type(type);
    });
    player.showDialog(dialog);
  }

  /** Sends a short error and rebuilds the dialog for the current step. */
  private void reshow(AuthSession session, Component message) {
    this.host.send(session, message);
    this.host.scheduler().runLater(() -> this.show(session), 150L);
  }

  // ----------------------------------------------------------- chat fallback (<1.21.6)

  private void showChatFallback(AuthSession session, Player player) {
    this.host.showChatPrompt(session);
  }
}
