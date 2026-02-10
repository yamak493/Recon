package net.enabify.recon.execution;

import net.enabify.recon.Recon;
import net.enabify.recon.model.ReconUser;
import net.enabify.recon.util.SchedulerUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * コマンド実行エンジン
 * ユーザー設定に基づき、プレイヤーまたはコンソールとしてコマンドを実行する
 * HTTPスレッドからの呼び出しに対応し、適切なスレッドで実行を行う
 */
public class CommandRunner {

    private final Recon plugin;

    /**
     * コマンド実行結果
     */
    public static class ExecutionResult {
        public final boolean success;
        public final String response;
        public final String error;

        public ExecutionResult(boolean success, String response, String error) {
            this.success = success;
            this.response = response;
            this.error = error;
        }
    }

    public CommandRunner(Recon plugin) {
        this.plugin = plugin;
    }

    /**
     * ユーザー設定に基づいてコマンドを実行する
     * HTTPスレッドから呼び出される想定
     *
     * @param reconUser 実行元ユーザーの設定
     * @param command   実行するコマンド
     * @param queue     プレイヤーがオフライン時にキューに保存するか
     * @return 実行結果
     */
    public ExecutionResult executeCommand(ReconUser reconUser, String command, boolean queue) {
        if (reconUser.getPlayer() != null && !reconUser.getPlayer().isEmpty()) {
            // プレイヤーとして実行
            return executeAsPlayer(reconUser, command, queue);
        } else {
            // コンソールとして実行（権限管理不要）
            return executeAsConsole(command);
        }
    }

    /**
     * コンソールとしてコマンドを実行
     * BufferedCommandSenderでメッセージをキャプチャする
     */
    private ExecutionResult executeAsConsole(String command) {
        try {
            CompletableFuture<String> future = SchedulerUtil.executeGlobal(plugin, () -> {
                BufferedCommandSender sender = new BufferedCommandSender(Bukkit.getServer());
                try {
                    Bukkit.dispatchCommand(sender, command);
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
                return sender.getOutput();
            });

            String result = future.get(10, TimeUnit.SECONDS);
            return new ExecutionResult(true, result, null);
        } catch (Exception e) {
            return new ExecutionResult(false, null, "Internal error: " + e.getMessage());
        }
    }

    /**
     * プレイヤーとしてコマンドを実行
     * オフラインの場合はキュー保存またはエラーを返す
     */
    private ExecutionResult executeAsPlayer(ReconUser reconUser, String command, boolean queueIfOffline) {
        Player player = Bukkit.getPlayerExact(reconUser.getPlayer());

        if (player == null || !player.isOnline()) {
            // プレイヤーがオフライン
            if (queueIfOffline) {
                plugin.getQueueManager().addToQueue(reconUser.getPlayer(), command, reconUser.getUser());
                return new ExecutionResult(true,
                        "Player is offline. Command queued for execution on login.", null);
            } else {
                return new ExecutionResult(false, null,
                        "Player '" + reconUser.getPlayer() + "' is offline and queue is disabled.");
            }
        }

        try {
            CompletableFuture<String> future = SchedulerUtil.executeForEntity(plugin, player, () ->
                    executeWithPermissions(player, reconUser, command));

            String result = future.get(10, TimeUnit.SECONDS);
            return new ExecutionResult(true, result, null);
        } catch (Exception e) {
            return new ExecutionResult(false, null, "Internal error: " + e.getMessage());
        }
    }

    /**
     * 権限を適用してプレイヤーとしてコマンドを実行する
     * メインスレッド（またはFoliaのエンティティスレッド）から呼び出す必要がある
     *
     * @param player    対象プレイヤー
     * @param reconUser ユーザー設定（OP・パーミッション情報）
     * @param command   実行するコマンド
     * @return 実行結果メッセージ
     */
    public String executeWithPermissions(Player player, ReconUser reconUser, String command) {
        boolean wasOp = player.isOp();
        PermissionAttachment attachment = null;

        try {
            // OP権限の一時付与
            if (reconUser.isOp() && !wasOp) {
                player.setOp(true);
            }

            // パーミッションの一時付与
            if (reconUser.getPermissions() != null && !reconUser.getPermissions().isEmpty()) {
                attachment = player.addAttachment(plugin);
                for (String perm : reconUser.getPermissions()) {
                    attachment.setPermission(perm, true);
                }
            }

            // コマンドを実行
            boolean result = Bukkit.dispatchCommand(player, command);

            if (result) {
                return "Command executed successfully.";
            } else {
                return "Command returned false (may indicate usage error).";
            }
        } catch (Exception e) {
            return "Error executing command: " + e.getMessage();
        } finally {
            // OP権限を元に戻す
            if (reconUser.isOp() && !wasOp) {
                player.setOp(false);
            }
            // パーミッションアタッチメントを削除
            if (attachment != null) {
                try {
                    player.removeAttachment(attachment);
                } catch (Exception ignored) {
                    // プレイヤーが退出済みの場合等
                }
            }
        }
    }
}
