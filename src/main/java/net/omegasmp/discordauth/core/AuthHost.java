package net.omegasmp.discordauth.core;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

/**
 * Everything the shared {@link AuthService} needs from the platform it runs on.
 *
 * <p>Implemented by the Velocity adapter (limbo, chat UI, transfer) and by the Paper adapter
 * (hold lobby, dialog UI, in-place release).</p>
 */
public interface AuthHost {

  Config config();

  Scheduler scheduler();

  Logger logger();

  AccountStore accounts();

  LinkStore links();

  BanStore bans();

  SessionRegistry sessions();

  /** True when the proxy/server verifies nicknames with Mojang. */
  boolean onlineMode();

  /** Geyser/Floodgate detection by username prefix. */
  boolean isBedrockName(String name);

  String addressOf(AuthSession session);

  /** Sends a message to the player behind the session, if it is still connected. */
  void send(AuthSession session, Component message);

  /** Disconnects the player behind the session. */
  void disconnect(AuthSession session, Component reason);

  /** Shows (or refreshes) the UI for the session's current step. */
  void showUi(AuthSession session);

  /** Platform-specific release after every step is complete. */
  void release(AuthSession session);

  /** Tells an online player that their password changed from Discord. */
  void passwordChanged(UUID uuid, String name);

  /** Refreshes tab list / bridge data after a link or identity change. */
  void identityChanged(UUID uuid);

  /** Called after a successful password step (used for IP-bound login sessions on Velocity). */
  default void passwordSucceeded(AuthSession session) {
  }

  /** Periodic cleanup for platform-specific transient state (tickets, remembered sessions). */
  default void housekeeping() {
  }

  /** Blocks an IP and kicks the player behind it (used by the Discord "this wasn't me" button). */
  default void blockIpAndKick(String ip, java.util.UUID uuid) {
  }

  /** Called after a failed password attempt so a dialog UI can be shown again. */
  default void passwordFailed(AuthSession session) {
  }

  UUID onlineUuid(String name);

  boolean kickOnline(String name, Component reason);
}
