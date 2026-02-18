package net.enabify.recon.command;

import net.enabify.recon.Recon;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

/**
 * Bukkit用 /recon コマンドのタブ補完
 * 実際のロジックは ReconCommandLogic に委譲する
 */
public class ReconTabCompleter implements TabCompleter {

    private final ReconCommandLogic logic;

    public ReconTabCompleter(Recon plugin) {
        this.logic = new ReconCommandLogic(plugin);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return logic.tabComplete(args);
    }
}
