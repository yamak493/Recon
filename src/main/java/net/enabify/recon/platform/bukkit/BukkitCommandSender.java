package net.enabify.recon.platform.bukkit;

import net.enabify.recon.platform.PlatformCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Bukkit CommandSender を PlatformCommandSender にラップするアダプター
 */
public class BukkitCommandSender implements PlatformCommandSender {

    private final CommandSender sender;

    public BukkitCommandSender(CommandSender sender) {
        this.sender = sender;
    }

    @Override
    public void sendMessage(String message) {
        sender.sendMessage(message);
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
        return sender instanceof Player;
    }

    @Override
    public String getPlayerName() {
        if (sender instanceof Player) {
            return ((Player) sender).getName();
        }
        return null;
    }

    /**
     * 元のBukkit CommandSenderを取得する
     */
    public CommandSender getBukkitSender() {
        return sender;
    }
}
