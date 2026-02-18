package net.enabify.recon.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * config.yml の管理クラス
 * サーバー共通の設定を読み込み・保持する
 */
public class ConfigManager {

    private final File dataFolder;
    private final Logger logger;

    private boolean allowSelfRegistration;
    private boolean autoRegistration;
    private int port;
    private List<String> globalIpWhitelist;
    private List<String> requestForwardingTargets;
    private boolean allowQueueForAllUsers;
    private int queueExpiryHours;
    private int rateLimit;
    private String language;
    private UserStorageType userStorageType;
    private boolean migrateUsersFromYamlOnFirstRun;
    private DatabaseSettings databaseSettings;

    public ConfigManager(File dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        loadConfig();
    }

    private void updateConfigWithComments(File configFile, SimpleYamlConfig userConfig) throws Exception {
        List<String> lines;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            lines = new ArrayList<>();
            String line;
            String currentSection = "";
            while ((line = reader.readLine()) != null) {
                String trimmedLine = line.trim();
                // コメント、空行、またはリスト要素("-")はそのまま
                if (trimmedLine.isEmpty() || trimmedLine.startsWith("#") || trimmedLine.startsWith("-")) {
                    lines.add(line);
                    continue;
                }

                if (trimmedLine.contains(":")) {
                    String[] parts = line.split(":", 2);
                    String key = parts[0].trim();
                    String fullPath = key;

                    // インデントからセクションを判定 (簡易版)
                    int indent = 0;
                    while (indent < line.length() && line.charAt(indent) == ' ') {
                        indent++;
                    }

                    if (indent == 0) {
                        currentSection = key;
                        fullPath = key;
                    } else if (indent == 2) {
                        fullPath = currentSection + "." + key;
                    }

                    if (userConfig.contains(fullPath)) {
                        Object userVal = getRawValue(userConfig, fullPath);
                        if (userVal != null) {
                            String dumped;
                            if (userVal instanceof String || userVal instanceof Number || userVal instanceof Boolean) {
                                dumped = String.valueOf(userVal);
                            } else {
                                dumped = new org.yaml.snakeyaml.Yaml().dump(userVal).trim();
                            }

                            // インデントを維持して置換
                            StringBuilder newLine = new StringBuilder();
                            for (int i = 0; i < indent; i++) newLine.append(" ");
                            newLine.append(key).append(": ").append(dumped);
                            lines.add(newLine.toString());
                        } else {
                            lines.add(line);
                        }
                    } else {
                        lines.add(line);
                    }
                } else {
                    lines.add(line);
                }
            }
        }

        // 新しい内容を書き込む
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(configFile), "UTF-8"))) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        }
    }

    /**
     * SimpleYamlConfigから生のオブジェクトを取得
     */
    @SuppressWarnings("unchecked")
    private Object getRawValue(SimpleYamlConfig config, String path) {
        if (!path.contains(".")) {
            return config.getData().get(path);
        }

        String[] parts = path.split("\\.", 2);
        Object section = config.getData().get(parts[0]);
        if (section instanceof Map) {
            return ((Map<String, Object>) section).get(parts[1]);
        }
        return null;
    }

    public void loadConfig() {
        saveDefaultConfig();
        File configFile = new File(dataFolder, "config.yml");
        SimpleYamlConfig config = SimpleYamlConfig.load(configFile);

        // デフォルト設定を取得して同期する
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            if (in != null) {
                SimpleYamlConfig defaults = SimpleYamlConfig.load(in);

                // config-versionが古い、または存在しない場合も更新を走らせる
                int defaultVersion = defaults.getInt("config-version", 0);
                int currentVersion = config.getInt("config-version", 0);

                if (currentVersion < defaultVersion) {
                    // 自動更新: 既存の値を保持しつつ、新しいテンプレート(コメント付き)に差し替える
                    updateConfigWithComments(configFile, config);
                    // 差し替え後に再読み込み
                    config = SimpleYamlConfig.load(configFile);
                    logger.info("Updated config.yml with missing items or new version while preserving comments.");
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to sync config.yml with defaults: " + e.getMessage());
            // e.printStackTrace(); // 運用時は消しても良い
        }

        this.allowSelfRegistration = config.getBoolean("allow-self-registration", false);
        this.autoRegistration = config.getBoolean("auto-registration", false);
        this.port = config.getInt("port", 4161);
        this.globalIpWhitelist = config.getStringList("global-ip-whitelist");
        if (this.globalIpWhitelist == null) {
            this.globalIpWhitelist = new ArrayList<>();
        }

        List<String> forwardingList = config.getStringList("request-forwarding");
        List<String> normalizedForwardingList = new ArrayList<>();
        if (forwardingList != null) {
            for (String target : forwardingList) {
                if (target == null) {
                    continue;
                }
                String trimmed = target.trim();
                if (!trimmed.isEmpty()) {
                    normalizedForwardingList.add(trimmed);
                }
            }
        }
        this.requestForwardingTargets = Collections.unmodifiableList(normalizedForwardingList);

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

    public List<String> getRequestForwardingTargets() {
        return requestForwardingTargets;
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

    /**
     * デフォルトの config.yml をデータフォルダに保存する（存在しない場合のみ）
     */
    private void saveDefaultConfig() {
        File configFile = new File(dataFolder, "config.yml");
        if (!configFile.exists()) {
            dataFolder.mkdirs();
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (in != null) {
                    Files.copy(in, configFile.toPath());
                }
            } catch (Exception e) {
                logger.warning("Failed to save default config.yml: " + e.getMessage());
            }
        }
    }

    private String getStringWithFallback(SimpleYamlConfig config,
                                         String primaryPath,
                                         String fallbackPath,
                                         String defaultValue) {
        if (config.contains(primaryPath)) {
            return config.getString(primaryPath, defaultValue);
        }
        return config.getString(fallbackPath, defaultValue);
    }

    private int getIntWithFallback(SimpleYamlConfig config,
                                   String primaryPath,
                                   String fallbackPath,
                                   int defaultValue) {
        if (config.contains(primaryPath)) {
            return config.getInt(primaryPath, defaultValue);
        }
        return config.getInt(fallbackPath, defaultValue);
    }

    private boolean getBooleanWithFallback(SimpleYamlConfig config,
                                           String primaryPath,
                                           String fallbackPath,
                                           boolean defaultValue) {
        if (config.contains(primaryPath)) {
            return config.getBoolean(primaryPath, defaultValue);
        }
        return config.getBoolean(fallbackPath, defaultValue);
    }
}
