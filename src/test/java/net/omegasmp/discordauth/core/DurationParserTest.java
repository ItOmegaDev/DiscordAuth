package net.omegasmp.discordauth.core;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class DurationParserTest {

  @Test
  public void emptyAndNullReturnZero() {
    assertEquals(0L, DurationParser.parse(null));
    assertEquals(0L, DurationParser.parse(""));
    assertEquals(0L, DurationParser.parse("   "));
  }

  @Test
  public void parsesSingleUnits() {
    assertEquals(60_000L, DurationParser.parse("1m"));
    assertEquals(1_800_000L, DurationParser.parse("30m"));
    assertEquals(3_600_000L, DurationParser.parse("1h"));
    assertEquals(86_400_000L, DurationParser.parse("1d"));
    assertEquals(604_800_000L, DurationParser.parse("1w"));
    assertEquals(1_000L, DurationParser.parse("1s"));
  }

  @Test
  public void parsesCombined() {
    assertEquals(86_400_000L + 43_200_000L, DurationParser.parse("1d12h"));
    assertEquals(90 * 60_000L, DurationParser.parse("1h30m"));
  }

  @Test
  public void invalidReturnsZero() {
    assertEquals(0L, DurationParser.parse("garbage"));
    assertEquals(0L, DurationParser.parse("abc123"));
    assertEquals(0L, DurationParser.parse("10x"));
  }

  @Test
  public void overflowReturnsZero() {
    assertEquals(0L, DurationParser.parse("999999999999w"));
  }

  @Test
  public void humanFormats() {
    assertEquals("0m", DurationParser.human(0));
    assertEquals("30m", DurationParser.human(1_800_000L));
    assertEquals("2d", DurationParser.human(172_800_000L));
    assertEquals("1h 30m", DurationParser.human(5_400_000L));
  }
}
