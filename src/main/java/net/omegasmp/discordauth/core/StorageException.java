package net.omegasmp.discordauth.core;

/** Thrown when a JSON store exists but cannot be parsed. The plugin must fail closed. */
public class StorageException extends RuntimeException {

  public StorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
