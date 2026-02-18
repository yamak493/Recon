package net.enabify.recon.execution;

import net.enabify.recon.Recon;
import net.enabify.recon.model.ReconUser;
import net.enabify.recon.platform.CommandExecutionService;
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
 *
 * コマンド実行後、dispatchCommandの戻り値(boolean)をsuccessに反映し、
 * キャプチャしたメッセージをresponseに格納して返す
 * 非同期メッセージにも対応するため、実行後数ティック待機してから結果を返す
 */
public class CommandRunner implements CommandExecutionService {

    private final Recon plugin;

    /** コマンド結果待機のティック数（非同期メッセージ対応） */
    private static final long RESPONSE_WAIT_TICKS = 3L;

    /**
     * コマンド実行結果
     * @deprecated ExecutionResult クラスを使用してください
     */
    @Deprecated
    public static class DeprecatedExecutionResult extends ExecutionResult {
        public DeprecatedExecutionResult(boolean success, String response, String plainResponse, String error) {
            super(success, response, plainResponse, error);
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
    @Override
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
     * BufferedCommandSenderでメッセージをキャプチャし、dispatchCommandの戻り値でsuccess判定
     * 実行後、数ティック待機してから結果を返す（非同期メッセージ対応）
     */
    private ExecutionResult executeAsConsole(String command) {
        CompletableFuture<ExecutionResult> future = new CompletableFuture<>();

        SchedulerUtil.runGlobal(plugin, () -> {
            BufferedCommandSender sender = new BufferedCommandSender(Bukkit.getServer());
            boolean success;
            try {
                success = Bukkit.dispatchCommand(sender, command);
            } catch (Exception e) {
                future.complete(new ExecutionResult(false,
                        plugin.getLangManager().format("error.command_execution",
                                java.util.Collections.singletonMap("error", e.getMessage())), "", null));
                return;
            }

            final boolean cmdSuccess = success;

            // 数ティック待機して非同期メッセージも取得してから結果を返す
            SchedulerUtil.runGlobalLater(plugin, () -> {
                String response = sender.getOutput();
                String plainResponse = sender.getPlainOutput();
                String error = cmdSuccess ? null : plugin.getLangManager().get("error.command_returned_false");
                future.complete(new ExecutionResult(cmdSuccess, response, plainResponse, error));
            }, RESPONSE_WAIT_TICKS);
        });

        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new ExecutionResult(false, null, null,
                    plugin.getLangManager().format("error.internal",
                            java.util.Collections.singletonMap("error", e.getMessage())));
        }
    }

    /**
     * プレイヤーとしてコマンドを実行
     * PlayerMessageInterceptorでNettyパイプラインを監視し、プレイヤーへ送信されるメッセージをキャプチャ
     * オフラインの場合はキュー保存またはエラーを返す
     */
    private ExecutionResult executeAsPlayer(ReconUser reconUser, String command, boolean queueIfOffline) {
        Player player = Bukkit.getPlayerExact(reconUser.getPlayer());

        if (player == null || !player.isOnline()) {
            // プレイヤーがオフライン
            if (queueIfOffline) {
                plugin.getQueueManager().addToQueue(reconUser.getPlayer(), command, reconUser.getUser());
                return new ExecutionResult(true,
                        plugin.getLangManager().get("queue.player_offline_queued"),
                        plugin.getLangManager().get("queue.player_offline_queued"), null);
            } else {
                return new ExecutionResult(false, null, null,
                        plugin.getLangManager().format("error.player_offline_queue_disabled",
                                java.util.Collections.singletonMap("player", reconUser.getPlayer())));
            }
        }

        CompletableFuture<ExecutionResult> future = new CompletableFuture<>();

        SchedulerUtil.runForEntity(plugin, player, () -> {
            // Nettyインターセプターを注入してプレイヤーへのメッセージをキャプチャ
            PlayerMessageInterceptor interceptor = new PlayerMessageInterceptor(player);
            boolean interceptorActive = interceptor.inject();

            boolean success;
            try {
                success = executeWithPermissions(player, reconUser, command);
            } catch (Exception e) {
                if (interceptorActive) interceptor.remove();
                future.complete(new ExecutionResult(false,
                        plugin.getLangManager().format("error.command_execution",
                                java.util.Collections.singletonMap("error", e.getMessage())), "", null));
                return;
            }

            final boolean cmdSuccess = success;

            // 数ティック待機して非同期メッセージも取得してから結果を返す
            SchedulerUtil.runForEntityLater(plugin, player, () -> {
                String response;
                String plainResponse;
                if (interceptorActive) {
                    response = interceptor.getOutput();
                    plainResponse = interceptor.getPlainOutput();
                    interceptor.remove();
                } else {
                    response = "";
                    plainResponse = "";
                }
                String error = cmdSuccess ? null : plugin.getLangManager().get("error.command_returned_false");
                future.complete(new ExecutionResult(cmdSuccess, response, plainResponse, error));
            }, RESPONSE_WAIT_TICKS);
        });

        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new ExecutionResult(false, null, null,
                    plugin.getLangManager().format("error.internal",
                            java.util.Collections.singletonMap("error", e.getMessage())));
        }
    }

    /**
     * 権限を適用してプレイヤーとしてコマンドを実行する
     * メインスレッド（またはFoliaのエンティティスレッド）から呼び出す必要がある
     *
     * @param player    対象プレイヤー
     * @param reconUser ユーザー設定（OP・パーミッション情報）
     * @param command   実行するコマンド
     * @return dispatchCommandの戻り値（true: 成功, false: 失敗）
     */
    public boolean executeWithPermissions(Player player, ReconUser reconUser, String command) {
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

            // コマンドを実行し、結果を返す
            return Bukkit.dispatchCommand(player, command);
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
