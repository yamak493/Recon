package net.enabify.recon.command;

import net.enabify.recon.Recon;
import net.enabify.recon.model.ReconUser;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * /recon コマンドハンドラー
 * create, edit, info, test の各サブコマンドを処理する
 */
public class ReconCommand implements CommandExecutor {

    private final Recon plugin;

    // パラメータの省略形→正式名マッピング
    private static final Map<String, String> SHORT_FORMS = new HashMap<>();

    static {
        SHORT_FORMS.put("u", "user");
        SHORT_FORMS.put("pw", "password");
        SHORT_FORMS.put("i", "ip");
        SHORT_FORMS.put("o", "op");
        SHORT_FORMS.put("pl", "player");
        SHORT_FORMS.put("pe", "permission");
    }

    public ReconCommand(Recon plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "create":
                handleCreate(sender, args);
                break;
            case "edit":
                handleEdit(sender, args);
                break;
            case "info":
                handleInfo(sender, args);
                break;
            case "test":
                handleTest(sender);
                break;
            case "reload":
                handleReload(sender);
                break;
            case "remove":
                handleRemove(sender, args);
                break;
            default:
                sendUsage(sender);
                break;
        }

        return true;
    }

    // ============================
    // /recon create
    // ============================
    private void handleCreate(CommandSender sender, String[] args) {
        Map<String, String> params = parseParams(args, 1);

        boolean isOther = params.containsKey("user");

        if (isOther) {
            // 他ユーザーのプロファイル作成
            handleCreateOther(sender, params);
        } else {
            // 自身のプロファイル作成
            handleCreateOwn(sender, params);
        }
    }

    /**
     * 他ユーザーのプロファイルを作成
     */
    private void handleCreateOther(CommandSender sender, Map<String, String> params) {
        // 権限チェック
        if (!sender.hasPermission("recon.create.other.user")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to create profiles for other users.");
            return;
        }

        String userName = params.get("user");
        String password = params.get("password");

        if (password == null || password.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Password is required. Usage: /recon create u:<user> pw:<password>");
            return;
        }

        if (plugin.getUserManager().userExists(userName)) {
            sender.sendMessage(ChatColor.RED + "User '" + userName + "' already exists. Use /recon edit to modify.");
            return;
        }

        ReconUser user = new ReconUser(userName, password);

        // IP ホワイトリスト
        if (params.containsKey("ip")) {
            if (!sender.hasPermission("recon.create.other.ip-whitelist")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to set IP whitelist.");
                return;
            }
            user.setIpWhitelist(parseCommaSeparated(params.get("ip")));
        }

        // OP権限
        if (params.containsKey("op")) {
            if (!sender.hasPermission("recon.create.other.op")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to set OP flag.");
                return;
            }
            user.setOp(Boolean.parseBoolean(params.get("op")));
        }

        // プレイヤー
        if (params.containsKey("player")) {
            if (!sender.hasPermission("recon.create.other.player")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to set player.");
                return;
            }
            user.setPlayer(params.get("player"));
        }

        // パーミッション
        if (params.containsKey("permission")) {
            if (!sender.hasPermission("recon.create.other.group")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to set permissions.");
                return;
            }
            user.setPermissions(parseCommaSeparated(params.get("permission")));
        }

        plugin.getUserManager().addUser(user);
        plugin.getReconLogger().logCommandExecution(sender.getName(),
                "create other user: " + userName);
        sender.sendMessage(ChatColor.GREEN + "User profile '" + userName + "' created successfully.");
    }

    /**
     * 自身のプロファイルを作成
     */
    private void handleCreateOwn(CommandSender sender, Map<String, String> params) {
        // プレイヤーチェック（コンソールからは自分自身を作れない）
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be executed by a player. Use user: parameter for console.");
            return;
        }

        // 自己登録が許可されているかチェック
        if (!plugin.getConfigManager().isAllowSelfRegistration()) {
            if (!sender.hasPermission("recon.create.own")) {
                sender.sendMessage(ChatColor.RED + "Self-registration is disabled on this server.");
                return;
            }
        }

        if (!sender.hasPermission("recon.create.own")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to create your own profile.");
            return;
        }

        String password = params.get("password");
        if (password == null || password.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Password is required. Usage: /recon create pw:<password>");
            return;
        }

        Player player = (Player) sender;
        String playerName = player.getName();

        // 既に自身のプロファイルがあるか確認
        if (plugin.getUserManager().userExists(playerName)) {
            sender.sendMessage(ChatColor.RED + "Your profile already exists. Use /recon edit to modify.");
            return;
        }

        ReconUser user = new ReconUser(playerName, password);
        user.setPlayer(playerName); // 自身をplayerとして設定

        // IP ホワイトリスト
        if (params.containsKey("ip")) {
            if (!sender.hasPermission("recon.create.own.ip-whitelist")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to set IP whitelist.");
                return;
            }
            user.setIpWhitelist(parseCommaSeparated(params.get("ip")));
        }

        // OP権限
        if (params.containsKey("op")) {
            if (!sender.hasPermission("recon.create.own.op")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to set OP flag.");
                return;
            }
            user.setOp(Boolean.parseBoolean(params.get("op")));
        }

        // パーミッション
        if (params.containsKey("permission")) {
            if (!sender.hasPermission("recon.create.own.group")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to set permissions.");
                return;
            }
            user.setPermissions(parseCommaSeparated(params.get("permission")));
        }

        plugin.getUserManager().addUser(user);
        plugin.getReconLogger().logCommandExecution(sender.getName(), "create own profile");
        sender.sendMessage(ChatColor.GREEN + "Your connection profile created successfully.");
    }

    // ============================
    // /recon edit
    // ============================
    private void handleEdit(CommandSender sender, String[] args) {
        Map<String, String> params = parseParams(args, 1);

        boolean isOther = params.containsKey("user");

        if (isOther) {
            handleEditOther(sender, params);
        } else {
            handleEditOwn(sender, params);
        }
    }

    /**
     * 他ユーザーのプロファイルを編集
     */
    private void handleEditOther(CommandSender sender, Map<String, String> params) {
        String userName = params.get("user");

        ReconUser user = plugin.getUserManager().getUser(userName);
        if (user == null) {
            sender.sendMessage(ChatColor.RED + "User '" + userName + "' not found.");
            return;
        }

        boolean changed = false;

        // パスワード
        if (params.containsKey("password")) {
            if (!sender.hasPermission("recon.edit.other.password")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to edit other user's password.");
                return;
            }
            user.setPassword(params.get("password"));
            changed = true;
        }

        // IPホワイトリスト（+/-で追加・削除）
        if (params.containsKey("ip")) {
            if (!sender.hasPermission("recon.edit.other.ip-whitelist")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to edit other user's IP whitelist.");
                return;
            }
            applyListModifications(user.getIpWhitelist(), params.get("ip"));
            changed = true;
        }

        // OP権限
        if (params.containsKey("op")) {
            if (!sender.hasPermission("recon.edit.other.op")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to edit other user's OP flag.");
                return;
            }
            user.setOp(Boolean.parseBoolean(params.get("op")));
            changed = true;
        }

        // プレイヤー
        if (params.containsKey("player")) {
            if (!sender.hasPermission("recon.edit.other.player")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to edit other user's player.");
                return;
            }
            user.setPlayer(params.get("player"));
            changed = true;
        }

        // パーミッション（+/-で追加・削除）
        if (params.containsKey("permission")) {
            if (!sender.hasPermission("recon.edit.other.group")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to edit other user's permissions.");
                return;
            }
            applyListModifications(user.getPermissions(), params.get("permission"));
            changed = true;
        }

        if (changed) {
            plugin.getUserManager().addUser(user);
            plugin.getReconLogger().logCommandExecution(sender.getName(),
                    "edit other user: " + userName);
            sender.sendMessage(ChatColor.GREEN + "User profile '" + userName + "' updated successfully.");
        } else {
            sender.sendMessage(ChatColor.YELLOW + "No changes specified.");
        }
    }

    /**
     * 自身のプロファイルを編集
     */
    private void handleEditOwn(CommandSender sender, Map<String, String> params) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be executed by a player. Use user: parameter for console.");
            return;
        }

        Player player = (Player) sender;
        String playerName = player.getName();

        ReconUser user = plugin.getUserManager().getUser(playerName);
        if (user == null) {
            // プレイヤー名でユーザーが見つからない場合、player フィールドで検索
            user = plugin.getUserManager().findByPlayer(playerName);
            if (user == null) {
                sender.sendMessage(ChatColor.RED + "You don't have a profile. Use /recon create first.");
                return;
            }
        }

        boolean changed = false;

        // パスワード
        if (params.containsKey("password")) {
            if (!sender.hasPermission("recon.edit.own.password")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to edit your password.");
                return;
            }
            user.setPassword(params.get("password"));
            changed = true;
        }

        // IPホワイトリスト（+/-で追加・削除）
        if (params.containsKey("ip")) {
            if (!sender.hasPermission("recon.edit.own.ip-whitelist")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to edit your IP whitelist.");
                return;
            }
            applyListModifications(user.getIpWhitelist(), params.get("ip"));
            changed = true;
        }

        // OP権限
        if (params.containsKey("op")) {
            if (!sender.hasPermission("recon.edit.own.op")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to edit your OP flag.");
                return;
            }
            user.setOp(Boolean.parseBoolean(params.get("op")));
            changed = true;
        }

        // パーミッション（+/-で追加・削除）
        if (params.containsKey("permission")) {
            if (!sender.hasPermission("recon.edit.own.group")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to edit your permissions.");
                return;
            }
            applyListModifications(user.getPermissions(), params.get("permission"));
            changed = true;
        }

        if (changed) {
            plugin.getUserManager().addUser(user);
            plugin.getReconLogger().logCommandExecution(sender.getName(), "edit own profile");
            sender.sendMessage(ChatColor.GREEN + "Your profile updated successfully.");
        } else {
            sender.sendMessage(ChatColor.YELLOW + "No changes specified.");
        }
    }

    // ============================
    // /recon info
    // ============================
    private void handleInfo(CommandSender sender, String[] args) {
        Map<String, String> params = parseParams(args, 1);

        if (params.containsKey("user")) {
            // 他ユーザーの情報表示
            if (!sender.hasPermission("recon.info.other")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to view other user's profile.");
                return;
            }

            String userName = params.get("user");
            ReconUser user = plugin.getUserManager().getUser(userName);
            if (user == null) {
                sender.sendMessage(ChatColor.RED + "User '" + userName + "' not found.");
                return;
            }
            displayUserInfo(sender, user);
        } else {
            // 自身の情報表示
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Specify a user: /recon info u:<username>");
                return;
            }

            if (!sender.hasPermission("recon.info.own")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to view your profile.");
                return;
            }

            Player player = (Player) sender;
            ReconUser user = plugin.getUserManager().getUser(player.getName());
            if (user == null) {
                user = plugin.getUserManager().findByPlayer(player.getName());
            }
            if (user == null) {
                sender.sendMessage(ChatColor.RED + "You don't have a profile. Use /recon create first.");
                return;
            }
            displayUserInfo(sender, user);
        }
    }

    /**
     * ユーザー情報を表示する
     */
    private void displayUserInfo(CommandSender sender, ReconUser user) {
        sender.sendMessage(ChatColor.GOLD + "==============================");
        sender.sendMessage(ChatColor.AQUA + "  User: " + ChatColor.WHITE + user.getUser());
        sender.sendMessage(ChatColor.AQUA + "  Password: " + ChatColor.GRAY + "********");
        sender.sendMessage(ChatColor.AQUA + "  Player: " + ChatColor.WHITE +
                (user.getPlayer() != null ? user.getPlayer() : "(console)"));
        sender.sendMessage(ChatColor.AQUA + "  OP: " + ChatColor.WHITE + user.isOp());
        sender.sendMessage(ChatColor.AQUA + "  IP Whitelist: " + ChatColor.WHITE +
                (user.getIpWhitelist().isEmpty() ? "(none)" : String.join(", ", user.getIpWhitelist())));
        sender.sendMessage(ChatColor.AQUA + "  Permissions: " + ChatColor.WHITE +
                (user.getPermissions().isEmpty() ? "(none)" : String.join(", ", user.getPermissions())));
        sender.sendMessage(ChatColor.GOLD + "==============================");
    }

    // ============================
    // /recon test
    // ============================
    private void handleTest(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "==============================");
        sender.sendMessage(ChatColor.AQUA + "       Welcome to Recon        ");
        sender.sendMessage(ChatColor.GREEN + "    Successful connection    ");
        sender.sendMessage(ChatColor.YELLOW + "==============================");
    }

    // ============================
    // /recon reload
    // ============================
    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("recon.reload")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to reload configuration.");
            return;
        }

        try {
            plugin.getConfigManager().loadConfig();
            plugin.getUserManager().loadUsers();
            plugin.getQueueManager().loadQueues();

            plugin.getReconLogger().logCommandExecution(sender.getName(), "reload configuration");
            sender.sendMessage(ChatColor.GREEN + "Configuration reloaded successfully.");
            sender.sendMessage(ChatColor.GRAY + "Reloaded: config.yml, users.yml, queues.yml");
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "Failed to reload configuration: " + e.getMessage());
            plugin.getLogger().severe("Error reloading configuration: " + e.getMessage());
        }
    }

    // ============================
    // /recon remove
    // ============================
    private void handleRemove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("recon.remove")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to remove user profiles.");
            return;
        }

        Map<String, String> params = parseParams(args, 1);
        String userName = params.get("user");

        if (userName == null || userName.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Username is required. Usage: /recon remove u:<username>");
            return;
        }

        if (!plugin.getUserManager().userExists(userName)) {
            sender.sendMessage(ChatColor.RED + "User '" + userName + "' not found.");
            return;
        }

        plugin.getUserManager().removeUser(userName);
        plugin.getReconLogger().logCommandExecution(sender.getName(),
                "remove user: " + userName);
        sender.sendMessage(ChatColor.GREEN + "User profile '" + userName + "' removed successfully.");
    }

    // ============================
    // ユーティリティ
    // ============================

    /**
     * 使い方を表示
     */
    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Recon Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/recon create" + ChatColor.WHITE + " - Create a connection profile");
        sender.sendMessage(ChatColor.YELLOW + "/recon edit" + ChatColor.WHITE + " - Edit a connection profile");
        sender.sendMessage(ChatColor.YELLOW + "/recon info" + ChatColor.WHITE + " - View a connection profile");
        sender.sendMessage(ChatColor.YELLOW + "/recon test" + ChatColor.WHITE + " - Test connection");
        sender.sendMessage(ChatColor.YELLOW + "/recon reload" + ChatColor.WHITE + " - Reload configuration");
        sender.sendMessage(ChatColor.YELLOW + "/recon remove" + ChatColor.WHITE + " - Remove a connection profile");
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GRAY + "Parameters: u(ser): pw(password): i(p): o(p): pl(ayer): pe(rmission):");
    }

    /**
     * コマンド引数からパラメータをパースする
     * key:value 形式を解析し、省略形を正式名に変換する
     *
     * @param args       コマンド引数配列
     * @param startIndex パース開始インデックス
     * @return パラメータMap
     */
    private Map<String, String> parseParams(String[] args, int startIndex) {
        Map<String, String> params = new LinkedHashMap<>();

        for (int i = startIndex; i < args.length; i++) {
            String arg = args[i];
            int colonIndex = arg.indexOf(':');

            if (colonIndex > 0 && colonIndex < arg.length() - 1) {
                String key = arg.substring(0, colonIndex).toLowerCase();
                String value = arg.substring(colonIndex + 1);

                // 省略形を正式名に変換
                if (SHORT_FORMS.containsKey(key)) {
                    key = SHORT_FORMS.get(key);
                }

                params.put(key, value);
            }
        }

        return params;
    }

    /**
     * カンマ区切りの値をリストにパースする
     */
    private List<String> parseCommaSeparated(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isEmpty()) return result;

        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                // +プレフィックスがついている場合は除去
                if (trimmed.startsWith("+")) {
                    trimmed = trimmed.substring(1);
                }
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    /**
     * +/-プレフィックスに基づいてリストを変更する
     * +item または item: リストに追加
     * -item: リストから削除
     *
     * @param list  編集対象のリスト
     * @param value カンマ区切りの変更値
     */
    private void applyListModifications(List<String> list, String value) {
        if (value == null || value.isEmpty()) return;

        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.startsWith("-")) {
                // 削除
                String toRemove = trimmed.substring(1).trim();
                list.remove(toRemove);
            } else {
                // 追加（+プレフィックスがあれば除去）
                String toAdd = trimmed.startsWith("+") ? trimmed.substring(1).trim() : trimmed;
                if (!toAdd.isEmpty() && !list.contains(toAdd)) {
                    list.add(toAdd);
                }
            }
        }
    }
}
