package net.enabify.recon.platform;

import net.enabify.recon.config.ConfigManager;
import net.enabify.recon.config.LangManager;
import net.enabify.recon.config.QueueManager;
import net.enabify.recon.config.UserManager;
import net.enabify.recon.http.RateLimiter;
import net.enabify.recon.logging.ReconLogger;
import net.enabify.recon.util.NonceTracker;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.logging.Logger;

/**
 * Reconプラグインのプラットフォーム非依存インターフェース
 * Bukkit、BungeeCord、Velocityの各メインクラスが実装する
 *
 * HTTPサーバーやコマンドロジックなど、共通コードから参照される
 */
public interface ReconPlatform {

    /**
     * プラグインのデータフォルダを取得
     */
    File getDataFolder();

    /**
     * プラグインのロガーを取得
     */
    Logger getPluginLogger();

    /**
     * プラグインバージョンを取得
     */
    String getPluginVersion();

    /**
     * 設定マネージャーを取得
     */
    ConfigManager getConfigManager();

    /**
     * 言語マネージャーを取得
     */
    LangManager getLangManager();

    /**
     * ユーザーマネージャーを取得
     */
    UserManager getUserManager();

    /**
     * キューマネージャーを取得
     */
    QueueManager getQueueManager();

    /**
     * ロガーを取得
     */
    ReconLogger getReconLogger();

    /**
     * Nonceトラッカーを取得
     */
    NonceTracker getNonceTracker();

    /**
     * レート制限を取得
     */
    RateLimiter getRateLimiter();

    /**
     * コマンド実行サービスを取得
     */
    CommandExecutionService getCommandExecutionService();

    /**
     * JARリソースからファイルをデータフォルダに保存する（存在しない場合のみ）
     *
     * @param resourcePath リソースパス（例: "config.yml", "lang/en.yml"）
     */
    default void saveResource(String resourcePath) {
        File outFile = new File(getDataFolder(), resourcePath);
        if (!outFile.exists()) {
            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (in != null) {
                    Files.copy(in, outFile.toPath());
                }
            } catch (Exception e) {
                getPluginLogger().warning("Failed to save resource: " + resourcePath);
            }
        }
    }
}
