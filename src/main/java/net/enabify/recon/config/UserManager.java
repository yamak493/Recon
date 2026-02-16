package net.enabify.recon.config;

import net.enabify.recon.Recon;
import net.enabify.recon.model.ReconUser;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * users.yml の管理クラス
 * API接続ユーザーの認証情報・権限を管理する
 */
public class UserManager {

    private final Recon plugin;
    private final File usersFile;
    private YamlConfiguration usersConfig;
    private final Map<String, ReconUser> users = new HashMap<>();

    public UserManager(Recon plugin) {
        this.plugin = plugin;
        this.usersFile = new File(plugin.getDataFolder(), "users.yml");
        loadUsers();
    }

    /**
     * users.ymlからユーザー情報を読み込む
     */
    public void loadUsers() {
        if (!usersFile.exists()) {
            try {
                usersFile.getParentFile().mkdirs();
                usersFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to create users.yml: " + e.getMessage());
            }
        }

        usersConfig = YamlConfiguration.loadConfiguration(usersFile);
        users.clear();

        for (String key : usersConfig.getKeys(false)) {
            ConfigurationSection section = usersConfig.getConfigurationSection(key);
            if (section == null) continue;

            ReconUser user = new ReconUser(key, section.getString("password", ""));
            user.setIpWhitelist(section.getStringList("ip-whitelist"));
            user.setOp(section.getBoolean("op", false));
            user.setQueue(section.getBoolean("queue", false));
            user.setPlayer(section.getString("player", null));
            user.setPermissions(section.getStringList("permissions"));
            users.put(key, user);
        }
    }

    /**
     * 全ユーザー情報をusers.ymlに保存する
     */
    public void saveUsers() {
        usersConfig = new YamlConfiguration();

        for (Map.Entry<String, ReconUser> entry : users.entrySet()) {
            String key = entry.getKey();
            ReconUser user = entry.getValue();

            usersConfig.set(key + ".password", user.getPassword());
            usersConfig.set(key + ".ip-whitelist", user.getIpWhitelist());
            usersConfig.set(key + ".op", user.isOp());
            usersConfig.set(key + ".queue", user.isQueue());
            usersConfig.set(key + ".player", user.getPlayer());
            usersConfig.set(key + ".permissions", user.getPermissions());
        }

        try {
            usersConfig.save(usersFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save users.yml: " + e.getMessage());
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
    public void addUser(ReconUser user) {
        users.put(user.getUser(), user);
        saveUsers();
    }

    /**
     * ユーザーを削除する
     */
    public void removeUser(String username) {
        users.remove(username);
        saveUsers();
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
        return Collections.unmodifiableMap(users);
    }
}
