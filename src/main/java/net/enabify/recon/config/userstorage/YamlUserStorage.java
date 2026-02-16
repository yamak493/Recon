package net.enabify.recon.config.userstorage;

import net.enabify.recon.Recon;
import net.enabify.recon.model.ReconUser;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * users.yml ベースのユーザー保存実装
 */
public class YamlUserStorage implements UserStorage {

    private final Recon plugin;
    private final File usersFile;

    public YamlUserStorage(Recon plugin) {
        this.plugin = plugin;
        this.usersFile = new File(plugin.getDataFolder(), "users.yml");
    }

    @Override
    public void initialize() throws Exception {
        if (!usersFile.exists()) {
            File parent = usersFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Failed to create plugin data folder: " + parent.getAbsolutePath());
            }
            if (!usersFile.createNewFile()) {
                throw new IOException("Failed to create users.yml");
            }
        }
    }

    @Override
    public Map<String, ReconUser> loadAllUsers() {
        Map<String, ReconUser> result = new HashMap<>();
        YamlConfiguration usersConfig = YamlConfiguration.loadConfiguration(usersFile);

        for (String key : usersConfig.getKeys(false)) {
            ConfigurationSection section = usersConfig.getConfigurationSection(key);
            if (section == null) {
                continue;
            }

            ReconUser user = new ReconUser(key, section.getString("password", ""));
            user.setIpWhitelist(section.getStringList("ip-whitelist"));
            user.setOp(section.getBoolean("op", false));
            user.setQueue(section.getBoolean("queue", false));
            user.setPlayer(section.getString("player", null));
            user.setPermissions(section.getStringList("permissions"));
            result.put(key, user);
        }

        return result;
    }

    @Override
    public void saveAllUsers(Collection<ReconUser> users) throws Exception {
        YamlConfiguration usersConfig = new YamlConfiguration();

        for (ReconUser user : users) {
            String key = user.getUser();
            usersConfig.set(key + ".password", user.getPassword());
            usersConfig.set(key + ".ip-whitelist", user.getIpWhitelist());
            usersConfig.set(key + ".op", user.isOp());
            usersConfig.set(key + ".queue", user.isQueue());
            usersConfig.set(key + ".player", user.getPlayer());
            usersConfig.set(key + ".permissions", user.getPermissions());
        }

        usersConfig.save(usersFile);
    }

    @Override
    public void upsertUser(ReconUser user) throws Exception {
        Map<String, ReconUser> users = loadAllUsers();
        users.put(user.getUser(), user);
        saveAllUsers(users.values());
    }

    @Override
    public void deleteUser(String username) throws Exception {
        Map<String, ReconUser> users = loadAllUsers();
        users.remove(username);
        saveAllUsers(users.values());
    }

    @Override
    public String getBackendName() {
        return "yaml";
    }

    @Override
    public boolean isDatabaseBackend() {
        return false;
    }
}
