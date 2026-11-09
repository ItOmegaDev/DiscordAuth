package net.omegasmp.discordauth.core;

import java.util.LinkedHashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Text rendering shared by Velocity and Paper.
 *
 * <p>User-controlled values (Discord nicknames, usernames) are always inserted as literal
 * components, never parsed as legacy colour codes. This closes the colour/MiniMessage injection
 * that the old plugin had through {@code tab.format}.</p>
 */
public final class Text {

  public static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder()
      .character('&')
      .hexColors()
      .build();

  private Text() {
  }

  public static Component component(String message) {
    return SERIALIZER.deserialize(message == null ? "" : message);
  }

  /** Replaces plain tokens. Longest tokens first so {@code %max_total%} is not eaten by {@code %max%}. */
  public static String render(String template, Config config, AuthSession session, long seconds) {
    return render(template, config,
        session == null ? "" : session.getUsername(),
        session == null ? "" : session.getCode(),
        seconds);
  }

  public static String render(String template, Config config, String playerName, String code, long seconds) {
    if (template == null) {
      return "";
    }
    String player = sanitizeUser(playerName);
    return template
        .replace("%max_total%", String.valueOf(Math.max(1, config.discord.maxAccountsPerDiscord)))
        .replace("%java_max%", String.valueOf(config.discord.maxJavaAccountsPerDiscord))
        .replace("%bedrock_max%", String.valueOf(config.discord.maxBedrockAccountsPerDiscord))
        .replace("%max%", String.valueOf(config.password.maxLength))
        .replace("%min%", String.valueOf(config.password.minLength))
        .replace("%player%", player)
        .replace("%code%", code == null ? "" : code)
        .replace("%guild%", config.discord.guildId == null ? "" : config.discord.guildId)
        .replace("%seconds%", String.valueOf(seconds))
        .replace("%time%", formatTime(seconds))
        .replace("%register_command%", primary(config.commands.register, "register"))
        .replace("%login_command%", primary(config.commands.login, "login"))
        .replace("%admin_command%", primary(config.commands.admin, "discordauth"))
        .replace("%minutes%", String.valueOf(Math.max(1, config.auth.codeTtlSeconds / 60)));
  }

  /**
   * Builds a component from a message template, turning {@code %discord_link%} into a clickable
   * invite button and {@code %copy_code%} into a clickable "copy code" button.
   */
  public static Component build(String template, Config config, AuthSession session) {
    if (template == null) {
      return Component.empty();
    }
    Component result = Component.empty();
    String[] linkParts = template.split("%discord_link%", -1);
    for (int i = 0; i < linkParts.length; i++) {
      if (i > 0) {
        result = result.append(discordLinkComponent(config));
      }
      String[] codeParts = linkParts[i].split("%copy_code%", -1);
      for (int j = 0; j < codeParts.length; j++) {
        if (j > 0) {
          result = result.append(codeComponent(config, session == null ? "" : session.getCode()));
        }
        if (!codeParts[j].isEmpty()) {
          result = result.append(component(render(codeParts[j], config, session, 0)));
        }
      }
    }
    return result;
  }

  /** Tab-line rendering with literal user values (no colour-code injection). */
  public static Component tab(String template, String player, LinkStore.Link link, Config config) {
    Map<String, Component> values = new LinkedHashMap<>();
    values.put("%player%", Component.text(sanitizeUser(player)));
    values.put("%discord_nick%", literal(link == null ? null : discordDisplay(link, "nickname", config)));
    values.put("%discord_username%", literal(link == null ? null : link.discordName));
    values.put("%discord_global%", literal(link == null ? null : link.discordGlobal));
    values.put("%discord_id%", literal(link == null ? null : link.discordId));
    values.put("%discord%", literal(link == null ? null : discordDisplay(link, config.tab.nameSource, config)));
    return interpolate(template, values);
  }

  private static Component literal(String value) {
    return Component.text(sanitizeUser(value));
  }

  /** Splits on the longest matching token, serializing only the static text between tokens. */
  public static Component interpolate(String template, Map<String, Component> values) {
    if (template == null || template.isEmpty()) {
      return Component.empty();
    }
    Component result = Component.empty();
    StringBuilder literal = new StringBuilder();
    int index = 0;
    while (index < template.length()) {
      String matched = null;
      for (String token : values.keySet()) {
        if (template.startsWith(token, index) && (matched == null || token.length() > matched.length())) {
          matched = token;
        }
      }
      if (matched == null) {
        literal.append(template.charAt(index));
        index++;
        continue;
      }
      if (literal.length() > 0) {
        result = result.append(component(literal.toString()));
        literal.setLength(0);
      }
      result = result.append(values.get(matched));
      index += matched.length();
    }
    if (literal.length() > 0) {
      result = result.append(component(literal.toString()));
    }
    return result;
  }

  public static Component discordLinkComponent(Config config) {
    String url = config.discord.inviteUrl == null ? "" : config.discord.inviteUrl.trim();
    if (url.isEmpty()) {
      return component(config.messages.linkTextFallback);
    }
    return component(config.messages.linkText)
        .clickEvent(ClickEvent.openUrl(url))
        .hoverEvent(HoverEvent.showText(component(config.messages.linkHover)));
  }

  public static Component codeComponent(Config config, String code) {
    String template = config.messages.codeText == null ? "%code%" : config.messages.codeText;
    Component text = component(template.replace("%code%", code == null ? "" : code));
    if (code == null || code.isEmpty()) {
      return text;
    }
    return text
        .clickEvent(ClickEvent.copyToClipboard(code))
        .hoverEvent(HoverEvent.showText(component(config.messages.codeHover)));
  }

  public static String primary(java.util.List<String> names, String fallback) {
    return names == null || names.isEmpty() ? fallback : names.get(0);
  }

  public static String formatTime(long seconds) {
    long safe = Math.max(0L, seconds);
    return String.format("%d:%02d", safe / 60L, safe % 60L);
  }

  /** Removes control characters and caps the length; never removes ordinary text. */
  public static String sanitizeUser(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder builder = new StringBuilder(Math.min(value.length(), 64));
    value.codePoints()
        .filter(codePoint -> codePoint >= 0x20 && codePoint != 0x7F)
        .limit(64)
        .forEach(builder::appendCodePoint);
    return builder.toString().trim();
  }

  public static String discordDisplay(LinkStore.Link link, String source, Config config) {
    if (link == null) {
      return null;
    }
    String first = null;
    for (String candidate : link.displayNameCandidates(source)) {
      if (candidate == null || candidate.isBlank()) {
        continue;
      }
      String value = config.tab.stripUnsupported ? stripUnsupported(candidate) : candidate;
      if (first == null) {
        first = value;
      }
      if (!config.tab.stripUnsupported || isReadable(value)) {
        return value;
      }
    }
    return first;
  }

  public static boolean isReadable(String text) {
    return text != null && text.codePoints().anyMatch(Character::isLetterOrDigit);
  }

  public static boolean hasUnsupportedChars(String text) {
    return text != null && text.codePoints().anyMatch(codePoint -> codePoint > 0xFFFF);
  }

  public static String stripUnsupported(String text) {
    if (text == null) {
      return "";
    }
    StringBuilder builder = new StringBuilder(text.length());
    text.codePoints().filter(codePoint -> codePoint <= 0xFFFF).forEach(builder::appendCodePoint);
    return builder.toString();
  }

  public static String unsupportedCodePoints(String text) {
    if (text == null) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    text.codePoints().filter(codePoint -> codePoint > 0xFFFF)
        .forEach(codePoint -> builder.append(String.format("U+%X ", codePoint)));
    return builder.toString().trim();
  }

  /** Plain-text Discord message safety: no control characters, no mass mentions. */
  public static String discordSafe(String value) {
    String cleaned = sanitizeUser(value);
    return cleaned.replace("@everyone", "@\u200beveryone").replace("@here", "@\u200bhere");
  }
}
