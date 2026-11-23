package net.omegasmp.discordauth.core;

import org.junit.Test;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ConfigDefaultsTest {

  @Test
  public void applyDefaultsDoesNotThrow() {
    Config config = new Config();
    config.applyDefaults();
    assertNotNull(config.discord);
    assertNotNull(config.discord.commandsOnlyChannelIds);
  }

  @Test
  public void nullCommandsOnlyListBecomesEmpty() {
    Config config = new Config();
    config.discord.commandsOnlyChannelIds = null;
    config.applyDefaults();
    assertNotNull(config.discord.commandsOnlyChannelIds);
    assertTrue(config.discord.commandsOnlyChannelIds.isEmpty());
  }
}
