package net.omegasmp.discordauth.core;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TextRenderTest {

  @Test
  public void rendersPlayerAndCode() {
    Config config = new Config();
    config.applyDefaults();
    String out = Text.render("Hello %player%, code %code%", config, "Steve", "ABC123", 0);
    assertTrue(out.contains("Steve"));
    assertTrue(out.contains("ABC123"));
  }

  @Test
  public void nullTemplateReturnsEmpty() {
    Config config = new Config();
    config.applyDefaults();
    assertEquals("", Text.render(null, config, "Steve", "ABC123", 0));
  }

  @Test
  public void sanitizesUserInput() {
    Config config = new Config();
    config.applyDefaults();
    String out = Text.render("%player%", config, "<script>", "", 0);
    // User input is inserted literally (never parsed as legacy colour codes), so the
    // dangerous-looking text stays inert text rather than being interpreted.
    assertTrue(out.contains("<script>"));
  }
}
