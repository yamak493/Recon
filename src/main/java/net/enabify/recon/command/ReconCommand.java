package net.enabify.recon.command;

import net.enabify.recon.Recon;
import net.enabify.recon.platform.bukkit.BukkitCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * Bukkit用 /recon コマンドハンドラー
 * 実際のロジックは ReconCommandLogic に委譲する
 */
public class ReconCommand implements CommandExecutor {

    private final ReconCommandLogic logic;

    public ReconCommand(Recon plugin) {
        this.logic = new ReconCommandLogic(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return logic.onCommand(new BukkitCommandSender(sender), args);
    }
}
