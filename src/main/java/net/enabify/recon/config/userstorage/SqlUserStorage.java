package net.enabify.recon.config.userstorage;

import net.enabify.recon.Recon;
import net.enabify.recon.config.ConfigManager;
import net.enabify.recon.model.ReconUser;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * MySQL / MariaDB ベースのユーザー保存実装
 */
public class SqlUserStorage implements UserStorage {

    private static final String FIELD_DELIMITER = "\n";

    private final Recon plugin;
    private final ConfigManager.UserStorageType storageType;
    private final ConfigManager.DatabaseSettings databaseSettings;
    private final String tableName;
    private final String jdbcUrl;
    private final String driverClass;

    public SqlUserStorage(Recon plugin,
                          ConfigManager.UserStorageType storageType,
                          ConfigManager.DatabaseSettings databaseSettings) {
        this.plugin = plugin;
        this.storageType = storageType;
        this.databaseSettings = databaseSettings;

        String sanitizedPrefix = sanitizeTablePrefix(databaseSettings.getTablePrefix());
        this.tableName = sanitizedPrefix + "users";

        if (storageType == ConfigManager.UserStorageType.MARIADB) {
            this.driverClass = "org.mariadb.jdbc.Driver";
            this.jdbcUrl = buildMariadbJdbcUrl(databaseSettings);
        } else {
            this.driverClass = "com.mysql.cj.jdbc.Driver";
            this.jdbcUrl = buildMysqlJdbcUrl(databaseSettings);
        }
    }

    @Override
    public void initialize() throws Exception {
        Class.forName(driverClass);

        String createTableSql = "CREATE TABLE IF NOT EXISTS `" + tableName + "` ("
                + "`username` VARCHAR(64) NOT NULL,"
                + "`password` VARCHAR(255) NOT NULL,"
                + "`ip_whitelist` TEXT NOT NULL,"
                + "`op_flag` TINYINT(1) NOT NULL DEFAULT 0,"
                + "`queue_flag` TINYINT(1) NOT NULL DEFAULT 0,"
                + "`player` VARCHAR(64) NULL,"
                + "`permissions` TEXT NOT NULL,"
                + "PRIMARY KEY (`username`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(createTableSql);
        }
    }

    @Override
    public Map<String, ReconUser> loadAllUsers() throws Exception {
        Map<String, ReconUser> users = new HashMap<>();
        String sql = "SELECT username, password, ip_whitelist, op_flag, queue_flag, player, permissions "
                + "FROM `" + tableName + "`";

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {

            while (resultSet.next()) {
                String username = resultSet.getString("username");
                ReconUser user = new ReconUser(username, resultSet.getString("password"));
                user.setIpWhitelist(decodeList(resultSet.getString("ip_whitelist")));
                user.setOp(resultSet.getBoolean("op_flag"));
                user.setQueue(resultSet.getBoolean("queue_flag"));
                user.setPlayer(resultSet.getString("player"));
                user.setPermissions(decodeList(resultSet.getString("permissions")));
                users.put(username, user);
            }
        }

        return users;
    }

    @Override
    public void saveAllUsers(Collection<ReconUser> users) throws Exception {
        String deleteSql = "DELETE FROM `" + tableName + "`";
        String upsertSql = buildUpsertSql();

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement deleteStmt = connection.prepareStatement(deleteSql)) {
                    deleteStmt.executeUpdate();
                }

                if (!users.isEmpty()) {
                    try (PreparedStatement upsertStmt = connection.prepareStatement(upsertSql)) {
                        for (ReconUser user : users) {
                            bindUser(upsertStmt, user);
                            upsertStmt.addBatch();
                        }
                        upsertStmt.executeBatch();
                    }
                }

                connection.commit();
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            }
        }
    }

    @Override
    public void upsertUser(ReconUser user) throws Exception {
        String sql = buildUpsertSql();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindUser(statement, user);
            statement.executeUpdate();
        }
    }

    @Override
    public void deleteUser(String username) throws Exception {
        String sql = "DELETE FROM `" + tableName + "` WHERE `username` = ?";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.executeUpdate();
        }
    }

    @Override
    public String getBackendName() {
        return storageType == ConfigManager.UserStorageType.MARIADB ? "mariadb" : "mysql";
    }

    @Override
    public boolean isDatabaseBackend() {
        return true;
    }

    private Connection getConnection() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", databaseSettings.getUsername());
        properties.setProperty("password", databaseSettings.getPassword());
        return DriverManager.getConnection(jdbcUrl, properties);
    }

    private String buildUpsertSql() {
        return "INSERT INTO `" + tableName + "` "
                + "(`username`, `password`, `ip_whitelist`, `op_flag`, `queue_flag`, `player`, `permissions`) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE "
                + "`password` = VALUES(`password`),"
                + "`ip_whitelist` = VALUES(`ip_whitelist`),"
                + "`op_flag` = VALUES(`op_flag`),"
                + "`queue_flag` = VALUES(`queue_flag`),"
                + "`player` = VALUES(`player`),"
                + "`permissions` = VALUES(`permissions`)";
    }

    private void bindUser(PreparedStatement statement, ReconUser user) throws SQLException {
        statement.setString(1, user.getUser());
        statement.setString(2, user.getPassword());
        statement.setString(3, encodeList(user.getIpWhitelist()));
        statement.setBoolean(4, user.isOp());
        statement.setBoolean(5, user.isQueue());

        String player = user.getPlayer();
        if (player == null || player.trim().isEmpty()) {
            statement.setString(6, null);
        } else {
            statement.setString(6, player);
        }

        statement.setString(7, encodeList(user.getPermissions()));
    }

    private String encodeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value == null) {
                continue;
            }

            if (sb.length() > 0) {
                sb.append(FIELD_DELIMITER);
            }
            sb.append(value.replace("\n", ""));
        }
        return sb.toString();
    }

    private List<String> decodeList(String rawValue) {
        List<String> result = new ArrayList<>();
        if (rawValue == null || rawValue.isEmpty()) {
            return result;
        }

        String[] split = rawValue.split(FIELD_DELIMITER);
        for (String value : split) {
            if (value != null && !value.trim().isEmpty()) {
                result.add(value.trim());
            }
        }
        return result;
    }

    private String sanitizeTablePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "recon_";
        }

        StringBuilder sanitized = new StringBuilder();
        for (char c : prefix.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_') {
                sanitized.append(c);
            }
        }

        if (sanitized.length() == 0) {
            return "recon_";
        }
        return sanitized.toString();
    }

    private String buildMysqlJdbcUrl(ConfigManager.DatabaseSettings settings) {
        return "jdbc:mysql://" + settings.getHost() + ":" + settings.getPort() + "/" + settings.getName()
                + "?useSSL=" + settings.isUseSsl()
                + "&allowPublicKeyRetrieval=true"
                + "&useUnicode=true"
                + "&characterEncoding=utf8"
                + "&serverTimezone=UTC"
                + "&connectTimeout=" + settings.getConnectTimeoutMs()
                + "&socketTimeout=" + settings.getSocketTimeoutMs();
    }

    private String buildMariadbJdbcUrl(ConfigManager.DatabaseSettings settings) {
        return "jdbc:mariadb://" + settings.getHost() + ":" + settings.getPort() + "/" + settings.getName()
                + "?useSsl=" + settings.isUseSsl()
                + "&useUnicode=true"
                + "&characterEncoding=utf8"
                + "&connectTimeout=" + settings.getConnectTimeoutMs()
                + "&socketTimeout=" + settings.getSocketTimeoutMs();
    }
}
