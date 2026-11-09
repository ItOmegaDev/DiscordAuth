package net.omegasmp.discordauth.core;

/** Platform-neutral scheduler facade. */
public interface Scheduler {

  /** Runs off the main/server thread (database writes, BCrypt, JDA lookups). */
  void runAsync(Runnable task);

  /** Runs on the platform's main thread (dialog updates, teleports, chat). */
  void runLater(Runnable task);

  /** Runs on the platform's main thread after {@code delayMillis}. */
  void runLater(Runnable task, long delayMillis);

  /** Repeats on the platform's main thread. */
  void repeat(Runnable task, long periodMillis);

  /** Repeats off the main thread. */
  void repeatAsync(Runnable task, long periodMillis);
}
