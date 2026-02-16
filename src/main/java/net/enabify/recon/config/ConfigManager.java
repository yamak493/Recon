package net.enabify.recon.config;

import net.enabify.recon.Recon;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * config.yml の管理クラス
 * サーバー共通の設定を読み込み・保持する
 */
public class ConfigManager {

    private final Recon plugin;

    private boolean allowSelfRegistration;
    private boolean autoRegistration;
    private int port;
    private List<String> globalIpWhitelist;
    private boolean allowQueueForAllUsers;
    private int queueExpiryHours;
    private int rateLimit;
    private String language;

    public ConfigManager(Recon plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    /**
     * config.ymlを読み込む（デフォルト値の保存含む）
     */
    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        this.allowSelfRegistration = config.getBoolean("allow-self-registration", false);
        this.autoRegistration = config.getBoolean("auto-registration", false);
        this.port = config.getInt("port", 4161);
        this.globalIpWhitelist = config.getStringList("global-ip-whitelist");
        if (this.globalIpWhitelist == null) {
            this.globalIpWhitelist = new ArrayList<>();
        }
        this.allowQueueForAllUsers = config.getBoolean("allow-queue-for-all-users", false);
        this.queueExpiryHours = config.getInt("queue-expiry-hours", 72);
        this.rateLimit = config.getInt("rate-limit", 30);
        this.language = config.getString("language", "en");
    }

    // --- Getters ---

    public boolean isAllowSelfRegistration() {
        return allowSelfRegistration;
    }

    public boolean isAutoRegistration() {
        // allow-self-registrationがtrueでないと無効
        return allowSelfRegistration && autoRegistration;
    }

    public int getPort() {
        return port;
    }

    public List<String> getGlobalIpWhitelist() {
        return globalIpWhitelist;
    }

    public boolean isAllowQueueForAllUsers() {
        return allowQueueForAllUsers;
    }

    public int getQueueExpiryHours() {
        return queueExpiryHours;
    }

    public int getRateLimit() {
        return rateLimit;
    }

    public String getLanguage() {
        return language;
    }
}
