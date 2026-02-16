package net.enabify.recon;

import net.enabify.recon.command.ReconCommand;
import net.enabify.recon.command.ReconTabCompleter;
import net.enabify.recon.config.ConfigManager;
import net.enabify.recon.config.LangManager;
import net.enabify.recon.config.QueueManager;
import net.enabify.recon.config.UserManager;
import net.enabify.recon.execution.CommandRunner;
import net.enabify.recon.http.RateLimiter;
import net.enabify.recon.http.ReconHttpServer;
import net.enabify.recon.listener.PlayerJoinListener;
import net.enabify.recon.logging.ReconLogger;
import net.enabify.recon.util.NonceTracker;
import net.enabify.recon.util.SchedulerUtil;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Recon - Reliable REST API alternative to Minecraft RCON
 * ver1.13以上のBukkit/Spigot、Paper/Folia(非同期)に対応
 */
public final class Recon extends JavaPlugin {

    private ConfigManager configManager;
    private LangManager langManager;
    private UserManager userManager;
    private QueueManager queueManager;
    private ReconLogger reconLogger;
    private NonceTracker nonceTracker;
    private RateLimiter rateLimiter;
    private CommandRunner commandRunner;
    private ReconHttpServer httpServer;

    @Override
    public void onEnable() {
        // ロゴ表示
        getLogger().info("==============================");
        getLogger().info("       Recon v" + getDescription().getVersion());
        getLogger().info("  REST API for Minecraft");
        getLogger().info("==============================");

        // Folia検出
        if (SchedulerUtil.isFolia()) {
            getLogger().info("Folia detected. Using region-aware scheduling.");
        }

        // 設定ファイル読み込み
        configManager = new ConfigManager(this);
        langManager = new LangManager(this);
        userManager = new UserManager(this);
        queueManager = new QueueManager(this);

        getLogger().info("User storage backend: " + userManager.getStorageBackendName());

        // ユーティリティ初期化
        reconLogger = new ReconLogger(this);
        nonceTracker = new NonceTracker();
        rateLimiter = new RateLimiter(configManager.getRateLimit());
        commandRunner = new CommandRunner(this);

        // コマンド登録
        PluginCommand reconCmd = getCommand("recon");
        if (reconCmd != null) {
            reconCmd.setExecutor(new ReconCommand(this));
            reconCmd.setTabCompleter(new ReconTabCompleter(this));
        }

        // イベントリスナー登録
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);

        // HTTPサーバー起動
        try {
            httpServer = new ReconHttpServer(this);
            httpServer.start();
        } catch (Exception e) {
            getLogger().severe("Failed to start HTTP server: " + e.getMessage());
            getLogger().severe("Recon API will not be available.");
        }

        // 定期クリーンアップタスク（5分毎）
        SchedulerUtil.runGlobalTimer(this, () -> {
            nonceTracker.cleanup();
            rateLimiter.cleanup();
            queueManager.cleanExpiredEntries();
        }, 6000L, 6000L); // 5分 = 6000ティック

        reconLogger.log("Recon plugin enabled.");
        getLogger().info("Recon enabled successfully. HTTP API on port " + configManager.getPort());
    }

    @Override
    public void onDisable() {
        // HTTPサーバー停止
        if (httpServer != null) {
            httpServer.stop();
        }

        // 設定保存
        if (userManager != null) {
            userManager.saveUsers();
        }
        if (queueManager != null) {
            queueManager.saveQueues();
        }

        if (reconLogger != null) {
            reconLogger.log("Recon plugin disabled.");
        }

        getLogger().info("Recon disabled.");
    }

    // --- Getters ---

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public LangManager getLangManager() {
        return langManager;
    }

    public UserManager getUserManager() {
        return userManager;
    }

    public QueueManager getQueueManager() {
        return queueManager;
    }

    public ReconLogger getReconLogger() {
        return reconLogger;
    }

    public NonceTracker getNonceTracker() {
        return nonceTracker;
    }

    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }

    public CommandRunner getCommandRunner() {
        return commandRunner;
    }
}
