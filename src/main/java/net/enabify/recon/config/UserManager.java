package net.enabify.recon.config;

import net.enabify.recon.config.userstorage.SqlUserStorage;
import net.enabify.recon.config.userstorage.UserStorage;
import net.enabify.recon.config.userstorage.YamlUserStorage;
import net.enabify.recon.model.ReconUser;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Reconユーザー情報の管理クラス
 * 保存基盤として users.yml / MySQL / MariaDB を選択可能
 */
public class UserManager {

    private final File dataFolder;
    private final ConfigManager configManager;
    private final Logger logger;
    private UserStorage storage;
    private final Map<String, ReconUser> users = new ConcurrentHashMap<>();

    public UserManager(File dataFolder, ConfigManager configManager, Logger logger) {
        this.dataFolder = dataFolder;
        this.configManager = configManager;
        this.logger = logger;
        this.storage = createStorage(configManager);
        initializeStorage();
    }

    /**
     * 設定再読み込み時に、ストレージ種別変更を反映する
     */
    public synchronized void reloadStorageBackend() {
        this.storage = createStorage(configManager);
        initializeStorage();
    }

    private void initializeStorage() {
        try {
            storage.initialize();
            loadUsers();

            if (storage.isDatabaseBackend()
                    && configManager.isMigrateUsersFromYamlOnFirstRun()) {
                migrateFromYamlIfNeeded();
            }
        } catch (Exception e) {
            logger.severe("Failed to initialize user storage '"
                    + storage.getBackendName() + "': " + e.getMessage());
            logger.warning("Falling back to YAML user storage.");

            this.storage = new YamlUserStorage(dataFolder);
            try {
                this.storage.initialize();
                loadUsers();
            } catch (Exception fallbackEx) {
                logger.severe("Failed to initialize fallback YAML storage: "
                        + fallbackEx.getMessage());
            }
        }
    }

    private UserStorage createStorage(ConfigManager configManager) {
        ConfigManager.UserStorageType storageType = configManager.getUserStorageType();
        if (storageType == ConfigManager.UserStorageType.MYSQL
                || storageType == ConfigManager.UserStorageType.MARIADB) {
            return new SqlUserStorage(logger, storageType, configManager.getDatabaseSettings());
        }
        return new YamlUserStorage(dataFolder);
    }

    private void migrateFromYamlIfNeeded() {
        if (!users.isEmpty()) {
            return;
        }

        try {
            YamlUserStorage yamlStorage = new YamlUserStorage(dataFolder);
            yamlStorage.initialize();
            Map<String, ReconUser> yamlUsers = yamlStorage.loadAllUsers();
            if (yamlUsers.isEmpty()) {
                return;
            }

            storage.saveAllUsers(yamlUsers.values());
            users.clear();
            users.putAll(yamlUsers);
            logger.info("Imported " + yamlUsers.size()
                    + " user(s) from users.yml to " + storage.getBackendName() + " storage.");
        } catch (Exception e) {
            logger.warning("Failed to import users.yml into database storage: "
                    + e.getMessage());
        }
    }

    /**
     * ストレージからユーザー情報を読み込む
     */
    public synchronized void loadUsers() {
        try {
            Map<String, ReconUser> loadedUsers = storage.loadAllUsers();
            users.clear();
            users.putAll(loadedUsers);
        } catch (Exception e) {
            logger.severe("Failed to load users from "
                    + storage.getBackendName() + ": " + e.getMessage());
        }
    }

    /**
     * 全ユーザー情報を現在のストレージに保存する
     */
    public synchronized void saveUsers() {
        try {
            storage.saveAllUsers(new ArrayList<>(users.values()));
        } catch (Exception e) {
            logger.severe("Failed to save users to "
                    + storage.getBackendName() + ": " + e.getMessage());
        }
    }

    /**
     * ユーザーを取得する
     */
    public ReconUser getUser(String username) {
        return users.get(username);
    }

    /**
     * ユーザーを追加・更新する
     */
    public synchronized void addUser(ReconUser user) {
        ReconUser previous = users.put(user.getUser(), user);
        try {
            storage.upsertUser(user);
        } catch (Exception e) {
            if (previous == null) {
                users.remove(user.getUser());
            } else {
                users.put(previous.getUser(), previous);
            }
            logger.severe("Failed to upsert user '" + user.getUser() + "' to "
                    + storage.getBackendName() + ": " + e.getMessage());
        }
    }

    /**
     * ユーザーを削除する
     */
    public synchronized void removeUser(String username) {
        ReconUser previous = users.remove(username);
        if (previous == null) {
            return;
        }

        try {
            storage.deleteUser(username);
        } catch (Exception e) {
            users.put(username, previous);
            logger.severe("Failed to delete user '" + username + "' from "
                    + storage.getBackendName() + ": " + e.getMessage());
        }
    }

    /**
     * ユーザーが存在するか
     */
    public boolean userExists(String username) {
        return users.containsKey(username);
    }

    /**
     * プレイヤー名からユーザーを検索する
     */
    public ReconUser findByPlayer(String playerName) {
        for (ReconUser user : users.values()) {
            if (playerName.equals(user.getPlayer())) {
                return user;
            }
        }
        return null;
    }

    /**
     * 全ユーザーを取得する（読み取り専用）
     */
    public Map<String, ReconUser> getUsers() {
        return Collections.unmodifiableMap(new HashMap<>(users));
    }

    /**
     * 現在利用中のユーザーストレージ名を取得する
     */
    public String getStorageBackendName() {
        return storage.getBackendName();
    }
}
