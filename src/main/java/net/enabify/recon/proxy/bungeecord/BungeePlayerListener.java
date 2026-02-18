package net.enabify.recon.proxy.bungeecord;

import net.enabify.recon.config.QueueManager;
import net.enabify.recon.model.ReconUser;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * BungeeCord用プレイヤー参加リスナー
 * プレイヤーがプロキシに接続した際にキュー実行と自動登録を行う
 */
public class BungeePlayerListener implements Listener {

    private final ReconBungee plugin;

    public BungeePlayerListener(ReconBungee plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPostLogin(PostLoginEvent event) {
        ProxiedPlayer player = event.getPlayer();
        String playerName = player.getName();

        // 1秒遅延で実行: 接続処理が完了してから実行
        ProxyServer.getInstance().getScheduler().schedule(plugin, () -> {
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
                    ProxyServer.getInstance().getPluginManager().dispatchCommand(player, qc.command);
                    plugin.getReconLogger().logQueueExecution(playerName, qc.command, qc.user);
                } catch (Exception e) {
                    plugin.getPluginLogger().warning("Failed to execute queued command for " +
                            playerName + ": " + qc.command + " - " + e.getMessage());
                }
            }
        }, 1, TimeUnit.SECONDS);
    }

    /**
     * 自動ユーザー作成処理
     */
    private void handleAutoRegistration(ProxiedPlayer player) {
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

        plugin.getPluginLogger().info("Automatically created Recon profile for player: " + playerName);

        // プレイヤーに通知
        player.sendMessage(new TextComponent(plugin.getLangManager().get("auto_registration.created")));
        player.sendMessage(new TextComponent(plugin.getLangManager().format("auto_registration.username",
                Collections.singletonMap("username", playerName))));
        player.sendMessage(new TextComponent(plugin.getLangManager().format("auto_registration.password",
                Collections.singletonMap("password", password))));
        player.sendMessage(new TextComponent(plugin.getLangManager().get("auto_registration.password_once")));
        player.sendMessage(new TextComponent(plugin.getLangManager().get("auto_registration.change_password")));
    }
}
