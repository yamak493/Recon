package net.enabify.recon.config;

import net.enabify.recon.Recon;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * queues.yml の管理クラス
 * オフラインプレイヤーの実行待ちコマンドを管理する
 */
public class QueueManager {

    private final Recon plugin;
    private final File queuesFile;
    private YamlConfiguration queuesConfig;

    /**
     * キューに保存されたコマンドのデータクラス
     */
    public static class QueuedCommand {
        public final String command;
        public final long timestamp;
        public final String user;

        public QueuedCommand(String command, long timestamp, String user) {
            this.command = command;
            this.timestamp = timestamp;
            this.user = user;
        }
    }

    public QueueManager(Recon plugin) {
        this.plugin = plugin;
        this.queuesFile = new File(plugin.getDataFolder(), "queues.yml");
        loadQueues();
    }

    /**
     * queues.ymlを読み込む
     */
    public void loadQueues() {
        if (!queuesFile.exists()) {
            try {
                queuesFile.getParentFile().mkdirs();
                queuesFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to create queues.yml: " + e.getMessage());
            }
        }
        queuesConfig = YamlConfiguration.loadConfiguration(queuesFile);
    }

    /**
     * queues.ymlに保存する
     */
    public void saveQueues() {
        try {
            queuesConfig.save(queuesFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save queues.yml: " + e.getMessage());
        }
    }

    /**
     * コマンドをキューに追加
     *
     * @param playerName 対象プレイヤー名
     * @param command    実行するコマンド
     * @param userName   リクエスト元ユーザー名
     */
    public void addToQueue(String playerName, String command, String userName) {
        List<Map<String, Object>> queue = getQueueList(playerName);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("command", command);
        entry.put("timestamp", System.currentTimeMillis() / 1000L);
        entry.put("user", userName);
        queue.add(entry);

        queuesConfig.set(playerName, queue);
        saveQueues();
    }

    /**
     * プレイヤーのキューを取得して削除（ログイン時に呼ばれる）
     *
     * @param playerName 対象プレイヤー名
     * @return キューに溜まっていたコマンドのリスト
     */
    public List<QueuedCommand> getAndClearQueue(String playerName) {
        cleanExpiredEntries();

        List<QueuedCommand> result = new ArrayList<>();
        List<Map<?, ?>> queue = queuesConfig.getMapList(playerName);

        for (Map<?, ?> entry : queue) {
            String command = String.valueOf(entry.get("command"));
            long timestamp = 0;
            Object tsObj = entry.get("timestamp");
            if (tsObj instanceof Number) {
                timestamp = ((Number) tsObj).longValue();
            }
            String user = String.valueOf(entry.get("user"));
            result.add(new QueuedCommand(command, timestamp, user));
        }

        queuesConfig.set(playerName, null);
        saveQueues();
        return result;
    }

    /**
     * 期限切れのエントリを削除
     * config.ymlのqueue-expiry-hours設定に基づいて古いキューを削除する
     */
    public void cleanExpiredEntries() {
        long expiryHours = plugin.getConfigManager().getQueueExpiryHours();
        long expirySeconds = expiryHours * 3600L;
        long now = System.currentTimeMillis() / 1000L;
        boolean changed = false;

        for (String playerName : new ArrayList<>(queuesConfig.getKeys(false))) {
            List<Map<?, ?>> queue = queuesConfig.getMapList(playerName);
            List<Map<String, Object>> validEntries = new ArrayList<>();

            for (Map<?, ?> entry : queue) {
                long timestamp = 0;
                Object tsObj = entry.get("timestamp");
                if (tsObj instanceof Number) {
                    timestamp = ((Number) tsObj).longValue();
                }
                if (now - timestamp < expirySeconds) {
                    Map<String, Object> validEntry = new LinkedHashMap<>();
                    validEntry.put("command", entry.get("command"));
                    validEntry.put("timestamp", entry.get("timestamp"));
                    validEntry.put("user", entry.get("user"));
                    validEntries.add(validEntry);
                } else {
                    changed = true;
                }
            }

            if (validEntries.isEmpty()) {
                queuesConfig.set(playerName, null);
                changed = true;
            } else if (changed) {
                queuesConfig.set(playerName, validEntries);
            }
        }

        if (changed) {
            saveQueues();
        }
    }

    /**
     * 指定プレイヤーのキューリストを取得する（内部用）
     */
    private List<Map<String, Object>> getQueueList(String playerName) {
        List<Map<?, ?>> existing = queuesConfig.getMapList(playerName);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<?, ?> map : existing) {
            Map<String, Object> entry = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                entry.put(String.valueOf(e.getKey()), e.getValue());
            }
            result.add(entry);
        }
        return result;
    }
}
