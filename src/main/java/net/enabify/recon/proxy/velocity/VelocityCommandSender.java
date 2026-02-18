package net.enabify.recon.proxy.velocity;

import net.enabify.recon.platform.PlatformCommandSender;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Velocity CommandSource を PlatformCommandSender にラップするアダプター
 */
public class VelocityCommandSender implements PlatformCommandSender {

    private final CommandSource source;

    public VelocityCommandSender(CommandSource source) {
        this.source = source;
    }

    @Override
    public void sendMessage(String message) {
        // §カラーコード付きのレガシーテキストをAdventure Componentに変換
        Component component = LegacyComponentSerializer.legacySection().deserialize(message);
        source.sendMessage(component);
    }

    @Override
    public boolean hasPermission(String permission) {
        return source.hasPermission(permission);
    }

    @Override
    public String getName() {
        if (source instanceof Player) {
            return ((Player) source).getUsername();
        }
        return "Console";
    }

    @Override
    public boolean isPlayer() {
        return source instanceof Player;
    }

    @Override
    public String getPlayerName() {
        if (source instanceof Player) {
            return ((Player) source).getUsername();
        }
        return null;
    }

    /**
     * 元のVelocity CommandSourceを取得する
     */
    public CommandSource getVelocitySource() {
        return source;
    }
}
