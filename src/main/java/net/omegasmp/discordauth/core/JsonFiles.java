package net.omegasmp.discordauth.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.slf4j.Logger;

/** Atomic JSON reads/writes shared by every store. */
public final class JsonFiles {

  private JsonFiles() {
  }

  /**
   * Reads a JSON file. A missing file returns {@code fallback}; an unreadable/corrupt file throws
   * {@link StorageException} so the caller can fail closed instead of silently starting empty.
   */
  public static <T> T read(Path file, Type type, T fallback, String displayName) {
    if (!Files.exists(file)) {
      return fallback;
    }
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      T loaded = Config.GSON.fromJson(reader, type);
      if (loaded == null) {
        throw new StorageException(displayName + " is empty or not valid JSON", null);
      }
      return loaded;
    } catch (IOException | com.google.gson.JsonParseException exception) {
      throw new StorageException("Unable to read " + displayName, exception);
    }
  }

  /** Writes to a temporary file first, then atomically replaces the target. */
  public static void write(Path file, Object data, Logger logger, String displayName) {
    try {
      Files.createDirectories(file.getParent());
      Path temp = file.resolveSibling(file.getFileName() + ".tmp");
      try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
        Config.GSON.toJson(data, writer);
      }
      try {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException exception) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      }
      try {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
      } catch (Throwable ignored) {
        // non-posix filesystem
      }
    } catch (IOException exception) {
      logger.error("Unable to write {}", displayName, exception);
    }
  }
}
