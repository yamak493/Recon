package net.enabify.recon.proxy.velocity;

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
import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Velocity用 Recon プラグインメインクラス
 * ReconPlatformを実装し、プロキシ環境でHTTP APIを提供する
 */
@Plugin(
        id = "recon",
        name = "Recon",
        version = "1.3-SNAPSHOT",
        description = "Reliable REST API alternative to Minecraft RCON",
        authors = {"Enabify"}
)
public class ReconVelocity implements ReconPlatform {

    private final ProxyServer proxyServer;
    private final Logger slf4jLogger;
    private final Path dataDirectory;
    private final java.util.logging.Logger julLogger;

    private ConfigManager configManager;
    private LangManager langManager;
    private UserManager userManager;
    private QueueManager queueManager;
    private ReconLogger reconLogger;
    private NonceTracker nonceTracker;
    private RateLimiter rateLimiter;
    private VelocityCommandRunner commandRunner;
    private ReconHttpServer httpServer;

    @Inject
    public ReconVelocity(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.slf4jLogger = logger;
        this.dataDirectory = dataDirectory;
        this.julLogger = new VelocityLoggerAdapter(logger);
    }

    @Subscribe(order = PostOrder.NORMAL)
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // ロゴ表示
        slf4jLogger.info("==============================");
        slf4jLogger.info("       Recon v1.3-SNAPSHOT (Velocity)");
        slf4jLogger.info("  REST API for Minecraft");
        slf4jLogger.info("==============================");

        // データフォルダ作成
        File dataFolder = dataDirectory.toFile();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        // 設定ファイル読み込み
        configManager = new ConfigManager(dataFolder, julLogger);
        langManager = new LangManager(dataFolder, configManager, julLogger);
        userManager = new UserManager(dataFolder, configManager, julLogger);
        queueManager = new QueueManager(dataFolder, configManager, julLogger);

        slf4jLogger.info("User storage backend: " + userManager.getStorageBackendName());

        // ユーティリティ初期化
        reconLogger = new ReconLogger(dataFolder);
        nonceTracker = new NonceTracker();
        rateLimiter = new RateLimiter(configManager.getRateLimit());
        commandRunner = new VelocityCommandRunner(this, proxyServer);

        // コマンド登録
        proxyServer.getCommandManager().register(
                proxyServer.getCommandManager().metaBuilder("reconv").build(),
                new VelocityReconCommand(this)
        );

        // イベントリスナー登録
        proxyServer.getEventManager().register(this, new VelocityPlayerListener(this, proxyServer));

        // HTTPサーバー起動
        try {
            httpServer = new ReconHttpServer(this);
            httpServer.start();
        } catch (Exception e) {
            slf4jLogger.error("Failed to start HTTP server: " + e.getMessage());
            slf4jLogger.error("Recon API will not be available.");
        }

        // 定期クリーンアップタスク（5分毎）
        proxyServer.getScheduler().buildTask(this, () -> {
            nonceTracker.cleanup();
            rateLimiter.cleanup();
            queueManager.cleanExpiredEntries();
        }).repeat(5, TimeUnit.MINUTES).schedule();

        reconLogger.log("Recon plugin enabled (Velocity).");
        slf4jLogger.info("Recon enabled successfully. HTTP API on port " + configManager.getPort());
    }

    @Subscribe(order = PostOrder.NORMAL)
    public void onProxyShutdown(ProxyShutdownEvent event) {
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
            reconLogger.log("Recon plugin disabled (Velocity).");
        }

        slf4jLogger.info("Recon disabled.");
    }

    // --- ReconPlatform implementation ---

    @Override
    public File getDataFolder() {
        return dataDirectory.toFile();
    }

    @Override
    public java.util.logging.Logger getPluginLogger() {
        return julLogger;
    }

    @Override
    public String getPluginVersion() {
        return "1.3-SNAPSHOT";
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

    /**
     * ProxyServerインスタンスを取得する
     */
    public ProxyServer getProxyServer() {
        return proxyServer;
    }
}
