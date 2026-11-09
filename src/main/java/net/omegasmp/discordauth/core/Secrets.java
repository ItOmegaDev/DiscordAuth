package net.omegasmp.discordauth.core;

import org.yaml.snakeyaml.Yaml;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Secrets loaded from {@code secrets.yml} (YAML) for easy editing.
 * The {@code DISCORD_TOKEN} environment variable always wins.
 */
public class Secrets {

  public String discordToken = "";

  private static final Yaml YAML = new Yaml();

  public static Secrets load(Path dataDirectory, Logger logger) {
    Secrets secrets = new Secrets();
    Path yamlFile = dataDirectory.resolve("secrets.yml");
    Path jsonFile = dataDirectory.resolve("secrets.json");
    try {
      Files.createDirectories(dataDirectory);
      // Migration from JSON
      if (Files.exists(jsonFile) && !Files.exists(yamlFile)) {
        migrateJsonToYaml(jsonFile, yamlFile, logger);
      }
      if (Files.exists(yamlFile)) {
        try (Reader reader = Files.newBufferedReader(yamlFile, StandardCharsets.UTF_8)) {
          Object loaded = YAML.load(reader);
          if (loaded instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) loaded;
            Object token = map.get("discordToken");
            if (token != null) {
              secrets.discordToken = String.valueOf(token).trim();
            }
          }
        }
      }
      if (secrets.discordToken.isBlank()) {
        // Try legacy token from config
        String legacy = readLegacyToken(dataDirectory.resolve("config.yml"));
        if (legacy == null) {
          legacy = readLegacyTokenJson(dataDirectory.resolve("config.json"));
        }
        if (legacy != null && !legacy.isBlank()) {
          secrets.discordToken = legacy.trim();
          logger.info("Moved the Discord token from config to secrets.yml.");
          stripLegacyToken(dataDirectory.resolve("config.yml"), logger);
          stripLegacyTokenJson(dataDirectory.resolve("config.json"), logger);
        }
      }
      String fromEnv = System.getenv("DISCORD_TOKEN");
      if (fromEnv != null && !fromEnv.isBlank()) {
        secrets.discordToken = fromEnv.trim();
        logger.info("Using the Discord token from the DISCORD_TOKEN environment variable.");
      }
      // Save
      try (Writer writer = Files.newBufferedWriter(yamlFile, StandardCharsets.UTF_8)) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("discordToken", secrets.discordToken);
        YAML.dump(map, writer);
      }
      Config.protect(yamlFile);
    } catch (IOException exception) {
      logger.error("Unable to read/write secrets.yml", exception);
    }
    return secrets;
  }

  public static void migrateLegacyToken(Path dataDirectory, Logger logger) {
    // Already handled in load()
  }

  private static String readLegacyToken(Path configFile) {
    if (!Files.isRegularFile(configFile)) return null;
    try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
      Object loaded = YAML.load(reader);
      if (loaded instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) loaded;
        Object discord = map.get("discord");
        if (discord instanceof Map) {
          @SuppressWarnings("unchecked")
          Map<String, Object> d = (Map<String, Object>) discord;
          Object token = d.get("botToken");
          if (token != null) return String.valueOf(token);
        }
      }
    } catch (Throwable ignored) { }
    return null;
  }

  private static String readLegacyTokenJson(Path configFile) {
    if (!Files.isRegularFile(configFile)) return null;
    try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
      com.google.gson.JsonObject root = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
      if (!root.has("discord") || !root.get("discord").isJsonObject()) return null;
      com.google.gson.JsonObject discord = root.getAsJsonObject("discord");
      if (!discord.has("botToken") || !discord.get("botToken").isJsonPrimitive()) return null;
      return discord.get("botToken").getAsString();
    } catch (Throwable ignored) { }
    return null;
  }

  private static void migrateJsonToYaml(Path jsonFile, Path yamlFile, Logger logger) {
    try {
      String json = Files.readString(jsonFile, StandardCharsets.UTF_8);
      @SuppressWarnings("unchecked")
      Map<String, Object> map = Config.GSON.fromJson(json, Map.class);
      if (map == null) return;
      try (Writer writer = Files.newBufferedWriter(yamlFile, StandardCharsets.UTF_8)) {
        YAML.dump(map, writer);
      }
      logger.info("Migrated config.json to config.yml.");
    } catch (Exception e) {
      logger.warn("Unable to migrate config.json to config.yml: " + e.getMessage());
    }
  }

  private static void stripLegacyToken(Path configFile, Logger logger) {
    if (!Files.isRegularFile(configFile)) return;
    try {
      Object loaded = YAML.load(Files.newBufferedReader(configFile, StandardCharsets.UTF_8));
      if (!(loaded instanceof Map)) return;
      @SuppressWarnings("unchecked")
      Map<String, Object> map = (Map<String, Object>) loaded;
      Object discord = map.get("discord");
      if (!(discord instanceof Map)) return;
      @SuppressWarnings("unchecked")
      Map<String, Object> d = (Map<String, Object>) discord;
      if (d.remove("botToken") == null) return;
      try (Writer writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
        YAML.dump(map, writer);
      }
      Config.protect(configFile);
      logger.info("Removed the old Discord token from config.yml.");
    } catch (Throwable e) {
      logger.warn("Unable to remove the old token from config.yml.");
    }
  }

  private static void stripLegacyTokenJson(Path configFile, Logger logger) {
    if (!Files.isRegularFile(configFile)) return;
    try {
      com.google.gson.JsonObject root = com.google.gson.JsonParser.parseReader(
          Files.newBufferedReader(configFile, StandardCharsets.UTF_8)).getAsJsonObject();
      if (!root.has("discord") || !root.get("discord").isJsonObject()) return;
      com.google.gson.JsonObject discord = root.getAsJsonObject("discord");
      if (discord.remove("botToken") == null) return;
      try (Writer writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
        Config.GSON.toJson(root, writer);
      }
      Config.protect(configFile);
    } catch (Throwable ignored) { }
  }

  public boolean hasToken() {
    return this.discordToken != null && !this.discordToken.isBlank();
  }
}
