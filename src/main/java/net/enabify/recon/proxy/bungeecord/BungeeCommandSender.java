package net.enabify.recon.proxy.bungeecord;

import net.enabify.recon.platform.PlatformCommandSender;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;

/**
 * BungeeCord CommandSender を PlatformCommandSender にラップするアダプター
 */
public class BungeeCommandSender implements PlatformCommandSender {

    private final CommandSender sender;

    public BungeeCommandSender(CommandSender sender) {
        this.sender = sender;
    }

    @Override
    public void sendMessage(String message) {
        sender.sendMessage(new TextComponent(message));
    }

    @Override
    public boolean hasPermission(String permission) {
        return sender.hasPermission(permission);
    }

    @Override
    public String getName() {
        return sender.getName();
    }

    @Override
    public boolean isPlayer() {
        return sender instanceof ProxiedPlayer;
    }

    @Override
    public String getPlayerName() {
        if (sender instanceof ProxiedPlayer) {
            return ((ProxiedPlayer) sender).getName();
        }
        return null;
    }

    /**
     * 元のBungeeCord CommandSenderを取得する
     */
    public CommandSender getBungeeSender() {
        return sender;
    }
}
