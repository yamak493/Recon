package net.enabify.recon.proxy.bungeecord;

import net.enabify.recon.config.ConfigManager;
import net.enabify.recon.config.LangManager;
import net.enabify.recon.config.QueueManager;
import net.enabify.recon.config.UserManager;
import net.enabify.recon.http.RateLimiter;
import net.enabify.recon.http.ReconHttpServer;
import net.enabify.recon.logging.ReconLogger;
import net.enabify.recon.platform.CommandExecutionService;
import net.enabify.recon.platform.ReconPlatform;
import net.enabify.recon.util.NonceTracker;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Plugin;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * BungeeCord用 Recon プラグインメインクラス
 * ReconPlatformを実装し、プロキシ環境でHTTP APIを提供する
 */
public class ReconBungee extends Plugin implements ReconPlatform {

    private ConfigManager configManager;
    private LangManager langManager;
    private UserManager userManager;
    private QueueManager queueManager;
    private ReconLogger reconLogger;
    private NonceTracker nonceTracker;
    private RateLimiter rateLimiter;
    private BungeeCommandRunner commandRunner;
    private ReconHttpServer httpServer;

    @Override
    public void onEnable() {
        // ロゴ表示
        getLogger().info("==============================");
        getLogger().info("       Recon v" + getDescription().getVersion() + " (BungeeCord)");
        getLogger().info("  REST API for Minecraft");
        getLogger().info("==============================");

        // データフォルダ作成
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        // 設定ファイル読み込み
        configManager = new ConfigManager(getDataFolder(), getLogger());
        langManager = new LangManager(getDataFolder(), configManager, getLogger());
        userManager = new UserManager(getDataFolder(), configManager, getLogger());
        queueManager = new QueueManager(getDataFolder(), configManager, getLogger());

        getLogger().info("User storage backend: " + userManager.getStorageBackendName());

        // ユーティリティ初期化
        reconLogger = new ReconLogger(getDataFolder());
        nonceTracker = new NonceTracker();
        rateLimiter = new RateLimiter(configManager.getRateLimit());
        commandRunner = new BungeeCommandRunner(this);

        // コマンド登録
        ProxyServer.getInstance().getPluginManager().registerCommand(this, new BungeeReconCommand(this));

        // イベントリスナー登録
        ProxyServer.getInstance().getPluginManager().registerListener(this, new BungeePlayerListener(this));

        // HTTPサーバー起動
        try {
            httpServer = new ReconHttpServer(this);
            httpServer.start();
        } catch (Exception e) {
            getLogger().severe("Failed to start HTTP server: " + e.getMessage());
            getLogger().severe("Recon API will not be available.");
        }

        // 定期クリーンアップタスク（5分毎）
        ProxyServer.getInstance().getScheduler().schedule(this, () -> {
            nonceTracker.cleanup();
            rateLimiter.cleanup();
            queueManager.cleanExpiredEntries();
        }, 5, 5, TimeUnit.MINUTES);

        reconLogger.log("Recon plugin enabled (BungeeCord).");
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

        // スケジューラーの停止
        ProxyServer.getInstance().getScheduler().cancel(this);

        if (reconLogger != null) {
            reconLogger.log("Recon plugin disabled (BungeeCord).");
        }

        getLogger().info("Recon disabled.");
    }

    // --- ReconPlatform implementation ---

    @Override
    public Logger getPluginLogger() {
        return getLogger();
    }

    @Override
    public String getPluginVersion() {
        return getDescription().getVersion();
    }

    @Override
    public CommandExecutionService getCommandExecutionService() {
        return commandRunner;
    }

    @Override
    public ConfigManager getConfigManager() {
        return configManager;
    }

    @Override
    public LangManager getLangManager() {
        return langManager;
    }

    @Override
    public UserManager getUserManager() {
        return userManager;
    }

    @Override
    public QueueManager getQueueManager() {
        return queueManager;
    }

    @Override
    public ReconLogger getReconLogger() {
        return reconLogger;
    }

    @Override
    public NonceTracker getNonceTracker() {
        return nonceTracker;
    }

    @Override
    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }
}
