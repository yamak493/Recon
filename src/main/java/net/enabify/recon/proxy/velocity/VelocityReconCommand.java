package net.enabify.recon.proxy.velocity;

import net.enabify.recon.command.ReconCommandLogic;
import net.enabify.recon.platform.ReconPlatform;
import com.velocitypowered.api.command.SimpleCommand;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Velocity用 /recon コマンド
 * ReconCommandLogic に委譲する
 */
public class VelocityReconCommand implements SimpleCommand {

    private final ReconCommandLogic logic;

    public VelocityReconCommand(ReconPlatform platform) {
        this.logic = new ReconCommandLogic(platform);
    }

    @Override
    public void execute(Invocation invocation) {
        VelocityCommandSender sender = new VelocityCommandSender(invocation.source());
        logic.onCommand(sender, invocation.arguments());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        List<String> completions = logic.tabComplete(invocation.arguments());
        return completions != null ? completions : Arrays.asList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("recon.create.own");
    }
}
