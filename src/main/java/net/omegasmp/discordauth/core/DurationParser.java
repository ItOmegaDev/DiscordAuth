package net.omegasmp.discordauth.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses durations like {@code 30m}, {@code 12h}, {@code 7d}, {@code 2w} or {@code 1d12h}. */
public final class DurationParser {

  private static final Pattern PATTERN = Pattern.compile("(?i)(\\d+)\\s*([smhdw])");
  /** Ten years; anything larger is treated as invalid rather than silently overflowing. */
  private static final long MAX_MILLIS = 10L * 365L * 86_400_000L;

  private DurationParser() {
  }

  /** @return duration in milliseconds, or {@code 0} when the input is empty/invalid. */
  public static long parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return 0L;
    }
    Matcher matcher = PATTERN.matcher(raw);
    long total = 0L;
    int parts = 0;
    while (matcher.find()) {
      long value;
      try {
        value = Long.parseLong(matcher.group(1));
      } catch (NumberFormatException exception) {
        return 0L;
      }
      long unit = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
        case "s" -> 1_000L;
        case "m" -> 60_000L;
        case "h" -> 3_600_000L;
        case "d" -> 86_400_000L;
        case "w" -> 604_800_000L;
        default -> 0L;
      };
      if (unit == 0L) {
        return 0L;
      }
      try {
        total = Math.addExact(total, Math.multiplyExact(value, unit));
      } catch (ArithmeticException exception) {
        return 0L;
      }
      if (total > MAX_MILLIS) {
        return 0L;
      }
      parts++;
    }
    return parts == 0 ? 0L : total;
  }

  /** {@code 90m -> "1h 30m"}, {@code 172800000 -> "2d"}. */
  public static String human(long millis) {
    if (millis <= 0) {
      return "0m";
    }
    long seconds = millis / 1000L;
    long days = seconds / 86_400L;
    long hours = (seconds % 86_400L) / 3_600L;
    long minutes = (seconds % 3_600L) / 60L;
    long secs = seconds % 60L;
    StringBuilder out = new StringBuilder();
    if (days > 0) {
      out.append(days).append("d ");
    }
    if (hours > 0) {
      out.append(hours).append("h ");
    }
    if (minutes > 0) {
      out.append(minutes).append("m ");
    }
    if (out.isEmpty() || (secs > 0 && days == 0)) {
      out.append(secs).append("s");
    }
    return out.toString().trim();
  }
}
