package net.omegasmp.discordauth.paper;

import net.omegasmp.discordauth.core.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Paper implementation: main-thread tasks use the Bukkit scheduler, async ones its async pool. */
public class PaperScheduler implements Scheduler {

  private final Plugin plugin;

  public PaperScheduler(Plugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public void runAsync(Runnable task) {
    if (!this.plugin.isEnabled()) {
      return;
    }
    Bukkit.getScheduler().runTaskAsynchronously(this.plugin, task);
  }

  @Override
  public void runLater(Runnable task) {
    this.runLater(task, 50L);
  }

  @Override
  public void runLater(Runnable task, long delayMillis) {
    if (!this.plugin.isEnabled()) {
      return;
    }
    long ticks = Math.max(1L, delayMillis / 50L);
    Bukkit.getScheduler().runTaskLater(this.plugin, task, ticks);
  }

  @Override
  public void repeat(Runnable task, long periodMillis) {
    if (!this.plugin.isEnabled()) {
      return;
    }
    long ticks = Math.max(1L, periodMillis / 50L);
    Bukkit.getScheduler().runTaskTimer(this.plugin, task, ticks, ticks);
  }

  @Override
  public void repeatAsync(Runnable task, long periodMillis) {
    if (!this.plugin.isEnabled()) {
      return;
    }
    long ticks = Math.max(1L, periodMillis / 50L);
    Bukkit.getScheduler().runTaskTimerAsynchronously(this.plugin, task, ticks, ticks);
  }

  /** Runs on the main thread right away, or schedules it when called from an async thread. */
  public void main(Runnable task) {
    if (Bukkit.isPrimaryThread()) {
      task.run();
    } else {
      this.runLater(task, 1L);
    }
  }

  /** Repeating main-thread task; returns the handle so the caller can cancel it. */
  public org.bukkit.scheduler.BukkitTask repeatingTask(Runnable task, long periodMillis) {
    if (!this.plugin.isEnabled()) {
      return null;
    }
    long ticks = Math.max(1L, periodMillis / 50L);
    return Bukkit.getScheduler().runTaskTimer(this.plugin, task, ticks, ticks);
  }

  /** Repeating async task; returns the handle so the caller can cancel it. */
  public org.bukkit.scheduler.BukkitTask repeatingAsyncTask(Runnable task, long periodMillis) {
    if (!this.plugin.isEnabled()) {
      return null;
    }
    long ticks = Math.max(1L, periodMillis / 50L);
    return Bukkit.getScheduler().runTaskTimerAsynchronously(this.plugin, task, ticks, ticks);
  }
}
