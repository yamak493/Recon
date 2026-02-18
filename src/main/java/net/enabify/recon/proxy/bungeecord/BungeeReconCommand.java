package net.enabify.recon.proxy.bungeecord;

import net.enabify.recon.command.ReconCommandLogic;
import net.enabify.recon.platform.ReconPlatform;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

import java.util.Arrays;
import java.util.List;

/**
 * BungeeCord用 /recon コマンド
 * ReconCommandLogic に委譲する
 */
public class BungeeReconCommand extends Command implements TabExecutor {

    private final ReconCommandLogic logic;

    public BungeeReconCommand(ReconPlatform platform) {
        super("reconb", "recon.create.own");
        this.logic = new ReconCommandLogic(platform);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        logic.onCommand(new BungeeCommandSender(sender), args);
    }

    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        List<String> completions = logic.tabComplete(args);
        return completions != null ? completions : Arrays.asList();
    }
}
