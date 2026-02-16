package net.enabify.recon.command;

import net.enabify.recon.Recon;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /recon コマンドのタブ補完
 */
public class ReconTabCompleter implements TabCompleter {

    private final Recon plugin;

    private static final List<String> SUB_COMMANDS = Arrays.asList("create", "edit", "info", "test", "reload", "remove");
    private static final List<String> CREATE_EDIT_PARAMS = Arrays.asList(
            "user:", "u:", "password:", "pw:", "ip:", "i:", "op:", "o:", "player:", "pl:", "permission:", "pe:", "queue:", "q:");
    private static final List<String> INFO_PARAMS = Arrays.asList("user:", "u:");
    private static final List<String> REMOVE_PARAMS = Arrays.asList("user:", "u:");

    public ReconTabCompleter(Recon plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // 第1引数: サブコマンド
            String partial = args[0].toLowerCase();
            completions.addAll(SUB_COMMANDS.stream()
                    .filter(s -> s.startsWith(partial))
                    .collect(Collectors.toList()));
        } else if (args.length >= 2) {
            String subCommand = args[0].toLowerCase();
            String current = args[args.length - 1].toLowerCase();

            switch (subCommand) {
                case "create":
                case "edit":
                    completions.addAll(getParamCompletions(current, CREATE_EDIT_PARAMS, args));
                    break;
                case "info":
                    completions.addAll(getParamCompletions(current, INFO_PARAMS, args));
                    break;
                case "remove":
                    completions.addAll(getParamCompletions(current, REMOVE_PARAMS, args));
                    break;
                default:
                    break;
            }
        }

        return completions;
    }

    /**
     * パラメータの補完候補を返す
     * 既に指定済みのパラメータキーは除外する
     */
    private List<String> getParamCompletions(String current, List<String> allParams, String[] args) {
        // 既に使用済みのキーを収集
        List<String> usedKeys = new ArrayList<>();
        for (int i = 1; i < args.length - 1; i++) {
            int colonIdx = args[i].indexOf(':');
            if (colonIdx > 0) {
                usedKeys.add(args[i].substring(0, colonIdx + 1).toLowerCase());
            }
        }

        // user:の正式名と省略形を対応させてフィルタ
        return allParams.stream()
                .filter(p -> p.startsWith(current))
                .filter(p -> !isAlreadyUsed(p, usedKeys))
                .collect(Collectors.toList());
    }

    /**
     * パラメータが既に使用されているかチェック（省略形も考慮）
     */
    private boolean isAlreadyUsed(String param, List<String> usedKeys) {
        // 正式名と省略形のマッピング
        String[][] pairs = {
                {"user:", "u:"},
                {"password:", "pw:"},
                {"ip:", "i:"},
                {"op:", "o:"},
                {"player:", "pl:"},
                {"permission:", "pe:"},
                {"queue:", "q:"}
        };

        for (String[] pair : pairs) {
            if (param.equals(pair[0]) || param.equals(pair[1])) {
                return usedKeys.contains(pair[0]) || usedKeys.contains(pair[1]);
            }
        }

        return usedKeys.contains(param);
    }
}
