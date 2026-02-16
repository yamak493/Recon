package net.enabify.recon.config;

import net.enabify.recon.Recon;
import net.enabify.recon.config.userstorage.SqlUserStorage;
import net.enabify.recon.config.userstorage.UserStorage;
import net.enabify.recon.config.userstorage.YamlUserStorage;
import net.enabify.recon.model.ReconUser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reconユーザー情報の管理クラス
 * 保存基盤として users.yml / MySQL / MariaDB を選択可能
 */
public class UserManager {

    private final Recon plugin;
    private UserStorage storage;
    private final Map<String, ReconUser> users = new ConcurrentHashMap<>();

    public UserManager(Recon plugin) {
        this.plugin = plugin;
        this.storage = createStorage(plugin.getConfigManager());
        initializeStorage();
    }

    /**
     * 設定再読み込み時に、ストレージ種別変更を反映する
     */
    public synchronized void reloadStorageBackend() {
        this.storage = createStorage(plugin.getConfigManager());
        initializeStorage();
    }

    private void initializeStorage() {
        try {
            storage.initialize();
            loadUsers();

            if (storage.isDatabaseBackend()
                    && plugin.getConfigManager().isMigrateUsersFromYamlOnFirstRun()) {
                migrateFromYamlIfNeeded();
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize user storage '"
                    + storage.getBackendName() + "': " + e.getMessage());
            plugin.getLogger().warning("Falling back to YAML user storage.");

            this.storage = new YamlUserStorage(plugin);
            try {
                this.storage.initialize();
                loadUsers();
            } catch (Exception fallbackEx) {
                plugin.getLogger().severe("Failed to initialize fallback YAML storage: "
                        + fallbackEx.getMessage());
            }
        }
    }

    private UserStorage createStorage(ConfigManager configManager) {
        ConfigManager.UserStorageType storageType = configManager.getUserStorageType();
        if (storageType == ConfigManager.UserStorageType.MYSQL
                || storageType == ConfigManager.UserStorageType.MARIADB) {
            return new SqlUserStorage(plugin, storageType, configManager.getDatabaseSettings());
        }
        return new YamlUserStorage(plugin);
    }

    private void migrateFromYamlIfNeeded() {
        if (!users.isEmpty()) {
            return;
        }

        try {
            YamlUserStorage yamlStorage = new YamlUserStorage(plugin);
            yamlStorage.initialize();
            Map<String, ReconUser> yamlUsers = yamlStorage.loadAllUsers();
            if (yamlUsers.isEmpty()) {
                return;
            }

            storage.saveAllUsers(yamlUsers.values());
            users.clear();
            users.putAll(yamlUsers);
            plugin.getLogger().info("Imported " + yamlUsers.size()
                    + " user(s) from users.yml to " + storage.getBackendName() + " storage.");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to import users.yml into database storage: "
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
            plugin.getLogger().severe("Failed to load users from "
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
            plugin.getLogger().severe("Failed to save users to "
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
            plugin.getLogger().severe("Failed to upsert user '" + user.getUser() + "' to "
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
            plugin.getLogger().severe("Failed to delete user '" + username + "' from "
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
