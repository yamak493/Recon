package net.enabify.recon.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Bukkit/Paper/Folia両対応のスケジューラユーティリティ
 * Foliaが検出された場合はリフレクション経由でFoliaのスケジューラAPIを使用する
 */
public class SchedulerUtil {

    private static Boolean folia = null;

    /**
     * 実行環境がFoliaかどうかを検出
     */
    public static boolean isFolia() {
        if (folia == null) {
            try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                folia = true;
            } catch (ClassNotFoundException e) {
                folia = false;
            }
        }
        return folia;
    }

    /**
     * グローバルスレッドで同期実行（コンソールコマンド等）
     * Bukkit: メインスレッドで実行
     * Folia: GlobalRegionSchedulerで実行
     */
    public static <T> CompletableFuture<T> executeGlobal(Plugin plugin, Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();

        if (isFolia()) {
            try {
                Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                Method execMethod = scheduler.getClass().getMethod("execute", Plugin.class, Runnable.class);
                execMethod.invoke(scheduler, plugin, (Runnable) () -> {
                    try {
                        future.complete(task.get());
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        } else {
            Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                try {
                    T result = task.get();
                    future.complete(result);
                    return result;
                } catch (Exception e) {
                    future.completeExceptionally(e);
                    throw e;
                }
            });
        }

        return future;
    }

    /**
     * エンティティ（プレイヤー）のリージョンで同期実行
     * Bukkit: メインスレッドで実行
     * Folia: EntitySchedulerで実行
     */
    @SuppressWarnings("unchecked")
    public static <T> CompletableFuture<T> executeForEntity(Plugin plugin, Entity entity, Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();

        if (isFolia()) {
            try {
                Object entityScheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
                Consumer<Object> consumer = (scheduledTask) -> {
                    try {
                        future.complete(task.get());
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                };
                Method runMethod = entityScheduler.getClass().getMethod("run",
                        Plugin.class, Consumer.class, Runnable.class);
                runMethod.invoke(entityScheduler, plugin, consumer, (Runnable) () ->
                        future.completeExceptionally(new RuntimeException("Entity retired")));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        } else {
            Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                try {
                    T result = task.get();
                    future.complete(result);
                    return result;
                } catch (Exception e) {
                    future.completeExceptionally(e);
                    throw e;
                }
            });
        }

        return future;
    }

    /**
     * エンティティのリージョンで遅延実行
     */
    @SuppressWarnings("unchecked")
    public static void runForEntityLater(Plugin plugin, Entity entity, Runnable task, long delayTicks) {
        if (isFolia()) {
            try {
                Object entityScheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
                Consumer<Object> consumer = (scheduledTask) -> task.run();
                Method runMethod = entityScheduler.getClass().getMethod("runDelayed",
                        Plugin.class, Consumer.class, Runnable.class, long.class);
                runMethod.invoke(entityScheduler, plugin, consumer, null, delayTicks);
            } catch (Exception e) {
                // フォールバック
                Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
            }
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    /**
     * グローバルでタイマー繰り返し実行
     */
    @SuppressWarnings("unchecked")
    public static void runGlobalTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (isFolia()) {
            try {
                Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                Consumer<Object> consumer = (scheduledTask) -> task.run();
                Method runMethod = scheduler.getClass().getMethod("runAtFixedRate",
                        Plugin.class, Consumer.class, long.class, long.class);
                runMethod.invoke(scheduler, plugin, consumer, Math.max(1, delayTicks), periodTicks);
            } catch (Exception e) {
                Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
            }
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        }
    }
}
