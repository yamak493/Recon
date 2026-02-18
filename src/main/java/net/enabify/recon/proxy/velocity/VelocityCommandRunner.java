package net.enabify.recon.proxy.velocity;

import net.enabify.recon.execution.ExecutionResult;
import net.enabify.recon.model.ReconUser;
import net.enabify.recon.platform.CommandExecutionService;
import net.enabify.recon.platform.ReconPlatform;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.velocitypowered.api.permission.Tristate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Velocity用コマンド実行サービス
 * CommandManager.executeImmediatelyAsConsoleを使用してコマンドを実行する
 */
public class VelocityCommandRunner implements CommandExecutionService {

    private final ReconPlatform platform;
    private final ProxyServer proxyServer;

    public VelocityCommandRunner(ReconPlatform platform, ProxyServer proxyServer) {
        this.platform = platform;
        this.proxyServer = proxyServer;
    }

    @Override
    public ExecutionResult executeCommand(ReconUser reconUser, String command, boolean queue) {
        if (reconUser.getPlayer() != null && !reconUser.getPlayer().isEmpty()) {
            return executeAsPlayer(reconUser, command, queue);
        } else {
            return executeAsConsole(command);
        }
    }

    /**
     * コンソールとしてコマンドを実行
     */
    private ExecutionResult executeAsConsole(String command) {
        try {
            BufferedVelocityCommandSource sender = new BufferedVelocityCommandSource();
            // executeImmediatelyAsConsole は Velocity 3.3.0+ で使用可能
            // 代替としてexecuteAsyncを使用
            CompletableFuture<Boolean> future =
                    proxyServer.getCommandManager().executeAsync(sender, command)
                            .thenApply(result -> true)
                            .exceptionally(ex -> false);

            boolean success;
            try {
                success = future.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                return new ExecutionResult(false, null, null,
                        platform.getLangManager().format("error.internal",
                                Collections.singletonMap("error", e.getMessage())));
            }

            String response = sender.getOutput();
            String plainResponse = sender.getPlainOutput();
            return new ExecutionResult(success, response, plainResponse, null);
        } catch (Exception e) {
            return new ExecutionResult(false,
                    platform.getLangManager().format("error.command_execution",
                            Collections.singletonMap("error", e.getMessage())), "", null);
        }
    }

    /**
     * プレイヤーとしてコマンドを実行
     */
    private ExecutionResult executeAsPlayer(ReconUser reconUser, String command, boolean queueIfOffline) {
        Optional<Player> optPlayer = proxyServer.getPlayer(reconUser.getPlayer());

        if (!optPlayer.isPresent()) {
            // プレイヤーがオフライン
            if (queueIfOffline) {
                platform.getQueueManager().addToQueue(reconUser.getPlayer(), command, reconUser.getUser());
                return new ExecutionResult(true,
                        platform.getLangManager().get("queue.player_offline_queued"),
                        platform.getLangManager().get("queue.player_offline_queued"), null);
            } else {
                return new ExecutionResult(false, null, null,
                        platform.getLangManager().format("error.player_offline_queue_disabled",
                                Collections.singletonMap("player", reconUser.getPlayer())));
            }
        }

        Player player = optPlayer.get();

        try {
            CompletableFuture<Boolean> future =
                    proxyServer.getCommandManager().executeAsync(player, command)
                            .thenApply(result -> true)
                            .exceptionally(ex -> false);

            boolean success;
            try {
                success = future.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                return new ExecutionResult(false, null, null,
                        platform.getLangManager().format("error.internal",
                                Collections.singletonMap("error", e.getMessage())));
            }

            return new ExecutionResult(success, "", "", null);
        } catch (Exception e) {
            return new ExecutionResult(false,
                    platform.getLangManager().format("error.command_execution",
                            Collections.singletonMap("error", e.getMessage())), "", null);
        }
    }

    /**
     * Velocity用のバッファリングCommandSource
     * コンソールコマンドの出力をキャプチャする
     */
    private static class BufferedVelocityCommandSource implements CommandSource {

        private final List<String> messages = new ArrayList<>();

        @Override
        public boolean hasPermission(String permission) {
            return true; // コンソール相当なので全権限
        }

        @Override
        public Tristate getPermissionValue(String permission) {
            return Tristate.TRUE;
        }

        @Override
        public void sendMessage(Component message) {
            String plain = PlainTextComponentSerializer.plainText().serialize(message);
            messages.add(plain);
        }

        /**
         * キャプチャしたメッセージを結合して返す
         */
        public String getOutput() {
            return String.join("\n", messages);
        }

        /**
         * プレーンテキスト出力を返す（既にプレーンテキストとしてキャプチャ済み）
         */
        public String getPlainOutput() {
            return getOutput();
        }
    }
}
