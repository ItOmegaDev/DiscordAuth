package net.omegasmp.discordauth.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import java.util.concurrent.TimeUnit;
import net.omegasmp.discordauth.core.Scheduler;

/** Velocity implementation: every task runs on the scheduler's own thread pool. */
public class VelocityScheduler implements Scheduler {

  private final ProxyServer server;
  private final Object plugin;

  public VelocityScheduler(ProxyServer server, Object plugin) {
    this.server = server;
    this.plugin = plugin;
  }

  @Override
  public void runAsync(Runnable task) {
    this.server.getScheduler().buildTask(this.plugin, task).schedule();
  }

  @Override
  public void runLater(Runnable task) {
    this.runLater(task, 1L);
  }

  @Override
  public void runLater(Runnable task, long delayMillis) {
    this.server.getScheduler()
        .buildTask(this.plugin, task)
        .delay(Math.max(0L, delayMillis), TimeUnit.MILLISECONDS)
        .schedule();
  }

  @Override
  public void repeat(Runnable task, long periodMillis) {
    this.server.getScheduler()
        .buildTask(this.plugin, task)
        .repeat(Math.max(50L, periodMillis), TimeUnit.MILLISECONDS)
        .schedule();
  }

  @Override
  public void repeatAsync(Runnable task, long periodMillis) {
    this.repeat(task, periodMillis);
  }
}
