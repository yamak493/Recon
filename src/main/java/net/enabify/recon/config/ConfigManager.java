package net.enabify.recon.config;

import net.enabify.recon.Recon;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    private UserStorageType userStorageType;
    private boolean migrateUsersFromYamlOnFirstRun;
    private DatabaseSettings databaseSettings;

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

        boolean hasNewDbEnabled = config.contains("database.enabled");
        boolean databaseEnabled = config.getBoolean("database.enabled", false);

        String rawStorageType = getStringWithFallback(
            config,
            "database.type",
            "user-storage.type",
            "mysql"
        );

        if (hasNewDbEnabled) {
            this.userStorageType = databaseEnabled
                ? UserStorageType.fromConfigValue(rawStorageType)
                : UserStorageType.YAML;
        } else {
            this.userStorageType = UserStorageType.fromConfigValue(rawStorageType);
        }

        this.migrateUsersFromYamlOnFirstRun = getBooleanWithFallback(
            config,
            "migrate-from-yaml-on-first-run",
            "user-storage.migrate-from-yaml-on-first-run",
            true
        );

        this.databaseSettings = new DatabaseSettings(
            getStringWithFallback(config, "database.host", "user-storage.database.host", "127.0.0.1"),
            getIntWithFallback(config, "database.port", "user-storage.database.port", 3306),
            getStringWithFallback(config, "database.name", "user-storage.database.name", "recon"),
            getStringWithFallback(config, "database.username", "user-storage.database.username", "recon"),
            getStringWithFallback(config, "database.password", "user-storage.database.password", "change_me"),
            getStringWithFallback(config, "database.table-prefix", "user-storage.database.table-prefix", "recon_"),
            getBooleanWithFallback(config, "database.use-ssl", "user-storage.database.use-ssl", false),
            getIntWithFallback(config, "database.connect-timeout-ms", "user-storage.database.connect-timeout-ms", 5000),
            getIntWithFallback(config, "database.socket-timeout-ms", "user-storage.database.socket-timeout-ms", 10000)
        );
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

    public UserStorageType getUserStorageType() {
        return userStorageType;
    }

    public boolean isMigrateUsersFromYamlOnFirstRun() {
        return migrateUsersFromYamlOnFirstRun;
    }

    public DatabaseSettings getDatabaseSettings() {
        return databaseSettings;
    }

    public enum UserStorageType {
        YAML,
        MYSQL,
        MARIADB;

        public static UserStorageType fromConfigValue(String value) {
            if (value == null || value.trim().isEmpty()) {
                return YAML;
            }

            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if ("mysql".equals(normalized)) {
                return MYSQL;
            }
            if ("mariadb".equals(normalized)) {
                return MARIADB;
            }
            return YAML;
        }

        public String toConfigValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static class DatabaseSettings {
        private final String host;
        private final int port;
        private final String name;
        private final String username;
        private final String password;
        private final String tablePrefix;
        private final boolean useSsl;
        private final int connectTimeoutMs;
        private final int socketTimeoutMs;

        public DatabaseSettings(String host,
                                int port,
                                String name,
                                String username,
                                String password,
                                String tablePrefix,
                                boolean useSsl,
                                int connectTimeoutMs,
                                int socketTimeoutMs) {
            this.host = host;
            this.port = port;
            this.name = name;
            this.username = username;
            this.password = password;
            this.tablePrefix = tablePrefix;
            this.useSsl = useSsl;
            this.connectTimeoutMs = connectTimeoutMs;
            this.socketTimeoutMs = socketTimeoutMs;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public String getName() {
            return name;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }

        public String getTablePrefix() {
            return tablePrefix;
        }

        public boolean isUseSsl() {
            return useSsl;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public int getSocketTimeoutMs() {
            return socketTimeoutMs;
        }
    }

    private String getStringWithFallback(FileConfiguration config,
                                         String primaryPath,
                                         String fallbackPath,
                                         String defaultValue) {
        if (config.contains(primaryPath)) {
            return config.getString(primaryPath, defaultValue);
        }
        return config.getString(fallbackPath, defaultValue);
    }

    private int getIntWithFallback(FileConfiguration config,
                                   String primaryPath,
                                   String fallbackPath,
                                   int defaultValue) {
        if (config.contains(primaryPath)) {
            return config.getInt(primaryPath, defaultValue);
        }
        return config.getInt(fallbackPath, defaultValue);
    }

    private boolean getBooleanWithFallback(FileConfiguration config,
                                           String primaryPath,
                                           String fallbackPath,
                                           boolean defaultValue) {
        if (config.contains(primaryPath)) {
            return config.getBoolean(primaryPath, defaultValue);
        }
        return config.getBoolean(fallbackPath, defaultValue);
    }
}
