package net.enabify.recon.proxy.velocity;

import net.enabify.recon.config.QueueManager;
import net.enabify.recon.model.ReconUser;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Velocity用プレイヤー参加リスナー
 * プレイヤーがプロキシに接続した際にキュー実行と自動登録を行う
 */
public class VelocityPlayerListener {

    private final ReconVelocity plugin;
    private final ProxyServer proxyServer;

    public VelocityPlayerListener(ReconVelocity plugin, ProxyServer proxyServer) {
        this.plugin = plugin;
        this.proxyServer = proxyServer;
    }

    @Subscribe(order = PostOrder.NORMAL)
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getUsername();

        // 1秒遅延で実行: 接続処理が完了してから実行
        proxyServer.getScheduler().buildTask(plugin, () -> {
            // 自動ユーザー作成
            handleAutoRegistration(player);

            // キューコマンド実行
            List<QueueManager.QueuedCommand> queue =
                    plugin.getQueueManager().getAndClearQueue(playerName);

            if (queue.isEmpty()) return;

            plugin.getPluginLogger().info("Executing " + queue.size() +
                    " queued command(s) for player " + playerName);

            for (QueueManager.QueuedCommand qc : queue) {
                try {
                    // プレイヤーとしてコマンドを実行
                    proxyServer.getCommandManager().executeAsync(player, qc.command);
                    plugin.getReconLogger().logQueueExecution(playerName, qc.command, qc.user);
                } catch (Exception e) {
                    plugin.getPluginLogger().warning("Failed to execute queued command for " +
                            playerName + ": " + qc.command + " - " + e.getMessage());
                }
            }
        }).delay(1, TimeUnit.SECONDS).schedule();
    }

    /**
     * 自動ユーザー作成処理
     */
    private void handleAutoRegistration(Player player) {
        if (!plugin.getConfigManager().isAutoRegistration()) {
            return;
        }

        String playerName = player.getUsername();
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

        plugin.getPluginLogger().info("Automatically created Recon profile for player: " + playerName);

        // プレイヤーに通知（§カラーコード対応）
        LegacyComponentSerializer serializer = LegacyComponentSerializer.legacySection();
        player.sendMessage(serializer.deserialize(plugin.getLangManager().get("auto_registration.created")));
        player.sendMessage(serializer.deserialize(plugin.getLangManager().format("auto_registration.username",
                Collections.singletonMap("username", playerName))));
        player.sendMessage(serializer.deserialize(plugin.getLangManager().format("auto_registration.password",
                Collections.singletonMap("password", password))));
        player.sendMessage(serializer.deserialize(plugin.getLangManager().get("auto_registration.password_once")));
        player.sendMessage(serializer.deserialize(plugin.getLangManager().get("auto_registration.change_password")));
    }
}
