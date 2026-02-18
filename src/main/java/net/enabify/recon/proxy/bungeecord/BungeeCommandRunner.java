package net.enabify.recon.proxy.bungeecord;

import net.enabify.recon.execution.ExecutionResult;
import net.enabify.recon.model.ReconUser;
import net.enabify.recon.platform.CommandExecutionService;
import net.enabify.recon.platform.ReconPlatform;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * BungeeCord用コマンド実行サービス
 * ProxyServerのdispatchCommandを使用してコマンドを実行する
 */
public class BungeeCommandRunner implements CommandExecutionService {

    private final ReconPlatform platform;

    public BungeeCommandRunner(ReconPlatform platform) {
        this.platform = platform;
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
     * BufferedBungeeCommandSenderでメッセージをキャプチャする
     */
    private ExecutionResult executeAsConsole(String command) {
        try {
            BufferedBungeeCommandSender sender = new BufferedBungeeCommandSender();
            ProxyServer.getInstance().getPluginManager().dispatchCommand(sender, command);

            String response = sender.getOutput();
            String plainResponse = sender.getPlainOutput();
            return new ExecutionResult(true, response, plainResponse, null);
        } catch (Exception e) {
            return new ExecutionResult(false,
                    platform.getLangManager().format("error.command_execution",
                            Collections.singletonMap("error", e.getMessage())), "", null);
        }
    }

    /**
     * プレイヤーとしてコマンドを実行
     * BungeeCordではOP/パーミッション操作は制限されるため、
     * プレイヤーの現在の権限でコマンドを実行する
     */
    private ExecutionResult executeAsPlayer(ReconUser reconUser, String command, boolean queueIfOffline) {
        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(reconUser.getPlayer());

        if (player == null) {
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

        try {
            // BungeeCordではプレイヤーのメッセージキャプチャが困難なため、
            // dispatchCommandで実行し、成功を返す
            ProxyServer.getInstance().getPluginManager().dispatchCommand(player, command);
            return new ExecutionResult(true, "", "", null);
        } catch (Exception e) {
            return new ExecutionResult(false,
                    platform.getLangManager().format("error.command_execution",
                            Collections.singletonMap("error", e.getMessage())), "", null);
        }
    }

    /**
     * BungeeCord用のバッファリングCommandSender
     * コンソールコマンドの出力をキャプチャする
     */
    private static class BufferedBungeeCommandSender implements CommandSender {

        private final List<String> messages = new ArrayList<>();

        @Override
        public String getName() {
            return "Recon";
        }

        @Override
        @SuppressWarnings("deprecation")
        public void sendMessage(String message) {
            messages.add(message);
        }

        @Override
        public void sendMessages(String... messages) {
            for (String msg : messages) {
                this.messages.add(msg);
            }
        }

        @Override
        public void sendMessage(BaseComponent... message) {
            StringBuilder sb = new StringBuilder();
            for (BaseComponent component : message) {
                sb.append(component.toLegacyText());
            }
            messages.add(sb.toString());
        }

        @Override
        public void sendMessage(BaseComponent message) {
            messages.add(message.toLegacyText());
        }

        @Override
        public java.util.Collection<String> getGroups() {
            return Collections.emptyList();
        }

        @Override
        public void addGroups(String... groups) {
        }

        @Override
        public void removeGroups(String... groups) {
        }

        @Override
        public boolean hasPermission(String permission) {
            return true;
        }

        @Override
        public void setPermission(String permission, boolean value) {
        }

        @Override
        public java.util.Collection<String> getPermissions() {
            return Collections.emptyList();
        }

        /**
         * キャプチャしたメッセージを結合して返す
         */
        public String getOutput() {
            return String.join("\n", messages);
        }

        /**
         * カラーコードを除去したプレーンテキストを返す
         */
        public String getPlainOutput() {
            String output = getOutput();
            // §カラーコードとANSIエスケープシーケンスを除去
            return output.replaceAll("§[0-9a-fk-or]", "")
                    .replaceAll("\\u001B\\[[;\\d]*m", "");
        }
    }
}
