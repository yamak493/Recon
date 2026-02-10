package net.enabify.recon.execution;

import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.conversations.Conversation;
import org.bukkit.conversations.ConversationAbandonedEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * コンソールコマンド実行時にメッセージをキャプチャするCommandSender
 * Bukkit.dispatchCommand()にこのSenderを渡すことで、コマンドの出力を取得できる
 */
public class BufferedCommandSender implements ConsoleCommandSender {

    private final Server server;
    private final List<String> messages = new ArrayList<>();

    public BufferedCommandSender(Server server) {
        this.server = server;
    }

    /**
     * キャプチャされたメッセージを結合して返す
     */
    public String getOutput() {
        return String.join("\n", messages);
    }

    /**
     * キャプチャされたメッセージのリストを返す
     */
    public List<String> getMessages() {
        return messages;
    }

    @Override
    public void sendMessage(String message) {
        messages.add(message);
    }

    @Override
    public void sendMessage(String[] messages) {
        for (String msg : messages) {
            this.messages.add(msg);
        }
    }

    @Override
    public Server getServer() {
        return server;
    }

    @Override
    public String getName() {
        return "Recon";
    }

    @Override
    public void sendRawMessage(String message) {
        sendMessage(message);
    }

    // --- Conversable implementation ---

    @Override
    public boolean isConversing() {
        return false;
    }

    @Override
    public void acceptConversationInput(String input) {
        // no-op
    }

    @Override
    public boolean beginConversation(Conversation conversation) {
        return false;
    }

    @Override
    public void abandonConversation(Conversation conversation) {
        // no-op
    }

    @Override
    public void abandonConversation(Conversation conversation, ConversationAbandonedEvent event) {
        // no-op
    }

    // --- コンソールとして全権限を持つ ---

    @Override
    public boolean isPermissionSet(String name) {
        return true;
    }

    @Override
    public boolean isPermissionSet(Permission perm) {
        return true;
    }

    @Override
    public boolean hasPermission(String name) {
        return true;
    }

    @Override
    public boolean hasPermission(Permission perm) {
        return true;
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) {
        return new PermissionAttachment(plugin, this);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin) {
        return new PermissionAttachment(plugin, this);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) {
        return new PermissionAttachment(plugin, this);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, int ticks) {
        return new PermissionAttachment(plugin, this);
    }

    @Override
    public void removeAttachment(PermissionAttachment attachment) {
        // no-op
    }

    @Override
    public void recalculatePermissions() {
        // no-op
    }

    @Override
    public Set<PermissionAttachmentInfo> getEffectivePermissions() {
        return new HashSet<>();
    }

    @Override
    public boolean isOp() {
        return true;
    }

    @Override
    public void setOp(boolean value) {
        // no-op
    }

    @Override
    public CommandSender.Spigot spigot() {
        return new CommandSender.Spigot() {
            @Override
            public void sendMessage(net.md_5.bungee.api.chat.BaseComponent component) {
                BufferedCommandSender.this.sendMessage(component.toLegacyText());
            }

            @Override
            public void sendMessage(net.md_5.bungee.api.chat.BaseComponent... components) {
                for (net.md_5.bungee.api.chat.BaseComponent component : components) {
                    BufferedCommandSender.this.sendMessage(component.toLegacyText());
                }
            }
        };
    }
}
