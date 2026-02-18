package net.enabify.recon.config.userstorage;

import net.enabify.recon.config.SimpleYamlConfig;
import net.enabify.recon.model.ReconUser;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * users.yml ベースのユーザー保存実装
 */
public class YamlUserStorage implements UserStorage {

    private final File usersFile;

    public YamlUserStorage(File dataFolder) {
        this.usersFile = new File(dataFolder, "users.yml");
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
        SimpleYamlConfig usersConfig = SimpleYamlConfig.load(usersFile);

        for (String key : usersConfig.getKeys()) {
            String password = usersConfig.getString(key + ".password", "");
            ReconUser user = new ReconUser(key, password);
            user.setIpWhitelist(usersConfig.getStringList(key + ".ip-whitelist"));
            user.setOp(usersConfig.getBoolean(key + ".op", false));
            user.setQueue(usersConfig.getBoolean(key + ".queue", false));
            user.setPlayer(usersConfig.getString(key + ".player", null));
            user.setPermissions(usersConfig.getStringList(key + ".permissions"));
            result.put(key, user);
        }

        return result;
    }

    @Override
    public void saveAllUsers(Collection<ReconUser> users) throws Exception {
        SimpleYamlConfig usersConfig = new SimpleYamlConfig();

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
