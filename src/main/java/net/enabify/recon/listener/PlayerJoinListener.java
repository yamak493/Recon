package net.enabify.recon.listener;

import net.enabify.recon.Recon;
import net.enabify.recon.config.QueueManager;
import net.enabify.recon.model.ReconUser;
import net.enabify.recon.util.SchedulerUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.List;
import java.util.UUID;

/**
 * プレイヤー参加時にキューに溜まったコマンドを実行するリスナー
 */
public class PlayerJoinListener implements Listener {

    private final Recon plugin;

    public PlayerJoinListener(Recon plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();

        // 1秒（20ティック）遅延で実行: 参加処理が完了してから実行
        SchedulerUtil.runForEntityLater(plugin, player, () -> {
            // 自動ユーザー作成
            handleAutoRegistration(player);

            List<QueueManager.QueuedCommand> queue =
                    plugin.getQueueManager().getAndClearQueue(playerName);

            if (queue.isEmpty()) return;

            plugin.getLogger().info("Executing " + queue.size() +
                    " queued command(s) for player " + playerName);

            for (QueueManager.QueuedCommand qc : queue) {
                try {
                    // 元のAPIユーザーの権限設定を取得
                    ReconUser reconUser = plugin.getUserManager().getUser(qc.user);

                    if (reconUser != null) {
                        // ユーザー設定に基づいて権限付きで実行
                        plugin.getCommandRunner().executeWithPermissions(player, reconUser, qc.command);
                    } else {
                        // ユーザーが削除されている場合は通常実行
                        Bukkit.dispatchCommand(player, qc.command);
                    }

                    plugin.getReconLogger().logQueueExecution(playerName, qc.command, qc.user);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to execute queued command for " +
                            playerName + ": " + qc.command + " - " + e.getMessage());
                }
            }
        }, 20L);
    }

    /**
     * 自動ユーザー作成処理
     */
    private void handleAutoRegistration(Player player) {
        if (!plugin.getConfigManager().isAutoRegistration()) {
            return;
        }

        String playerName = player.getName();
        // すでに同名のユーザーがいるか、またはこのプレイヤーに紐づくユーザーがいるか確認
        if (plugin.getUserManager().userExists(playerName) ||
                plugin.getUserManager().findByPlayer(playerName) != null) {
            return;
        }

        // パスワードをランダム生成 (8桁)
        String password = UUID.randomUUID().toString().substring(0, 8);
        ReconUser newUser = new ReconUser(playerName, password);
        newUser.setPlayer(playerName);

        // ユーザー追加
        plugin.getUserManager().addUser(newUser);

        plugin.getLogger().info("Automatically created Recon profile for player: " + playerName);

        // プレイヤーに通知
        player.sendMessage("§b[Recon] §aThe Recon user was created automatically.");
        player.sendMessage("§b[Recon] §7Username: §f" + playerName);
        player.sendMessage("§b[Recon] §7Password: §f" + password);
        player.sendMessage("§b[Recon] §cThis password will only be shown once. Please keep it safe.");
        player.sendMessage("§b[Recon] §7To change the password, use §f/recon edit pw:<password>§7.");
    }
}
