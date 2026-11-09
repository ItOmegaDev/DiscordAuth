package net.omegasmp.discordauth.paper;

/**
 * Discord identity of a player, as seen by this Paper server.
 *
 * <p>In {@code role=auth} it is built from {@code links.json}; in {@code role=bridge} it is
 * received from the proxy over the {@code discordauth:identity} plugin channel.</p>
 */
public record PaperIdentity(boolean linked, String nick, String username, String global, String discordId) {

  public static final PaperIdentity NONE = new PaperIdentity(false, null, null, null, null);

  /** Nickname, otherwise the global display name, otherwise the plain username. */
  public String displayName(boolean stripUnsupported) {
    for (String candidate : new String[] {this.nick, this.global, this.username}) {
      if (candidate == null || candidate.isBlank()) {
        continue;
      }
      String value = stripUnsupported ? net.omegasmp.discordauth.core.Text.stripUnsupported(candidate) : candidate;
      if (!stripUnsupported || net.omegasmp.discordauth.core.Text.isReadable(value)) {
        return value;
      }
    }
    return "";
  }
}
