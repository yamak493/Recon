package net.enabify.recon.command;

import net.enabify.recon.Recon;
import net.enabify.recon.model.ReconUser;
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
            sender.sendMessage(tr("error.no_permission.create_other_user"));
            return;
        }

        String userName = params.get("user");
        String password = params.get("password");

        if (password == null || password.isEmpty()) {
            sender.sendMessage(tr("error.password_required_create_other"));
            return;
        }

        if (plugin.getUserManager().userExists(userName)) {
            sender.sendMessage(tr("error.user_exists", Collections.singletonMap("username", userName)));
            return;
        }

        ReconUser user = new ReconUser(userName, password);

        // IP ホワイトリスト
        if (params.containsKey("ip")) {
            if (!sender.hasPermission("recon.create.other.ip-whitelist")) {
                sender.sendMessage(tr("error.no_permission.ip_whitelist"));
                return;
            }
            user.setIpWhitelist(parseCommaSeparated(params.get("ip")));
        }

        // OP権限
        if (params.containsKey("op")) {
            if (!sender.hasPermission("recon.create.other.op")) {
                sender.sendMessage(tr("error.no_permission.op_flag"));
                return;
            }
            user.setOp(Boolean.parseBoolean(params.get("op")));
        }

        // プレイヤー
        if (params.containsKey("player")) {
            if (!sender.hasPermission("recon.create.other.player")) {
                sender.sendMessage(tr("error.no_permission.player"));
                return;
            }
            user.setPlayer(params.get("player"));
        }

        // パーミッション
        if (params.containsKey("permission")) {
            if (!sender.hasPermission("recon.create.other.group")) {
                sender.sendMessage(tr("error.no_permission.permissions"));
                return;
            }
            user.setPermissions(parseCommaSeparated(params.get("permission")));
        }

        plugin.getUserManager().addUser(user);
        plugin.getReconLogger().logCommandExecution(sender.getName(),
                "create other user: " + userName);
        sender.sendMessage(tr("success.user_profile_created", Collections.singletonMap("username", userName)));
    }

    /**
     * 自身のプロファイルを作成
     */
    private void handleCreateOwn(CommandSender sender, Map<String, String> params) {
        // プレイヤーチェック（コンソールからは自分自身を作れない）
        if (!(sender instanceof Player)) {
            sender.sendMessage(tr("error.player_only_use_user_param"));
            return;
        }

        // 自己登録が許可されているかチェック
        if (!plugin.getConfigManager().isAllowSelfRegistration()) {
            if (!sender.hasPermission("recon.create.own")) {
                sender.sendMessage(tr("error.self_registration_disabled"));
                return;
            }
        }

        if (!sender.hasPermission("recon.create.own")) {
            sender.sendMessage(tr("error.no_permission.create_own"));
            return;
        }

        String password = params.get("password");
        if (password == null || password.isEmpty()) {
            sender.sendMessage(tr("error.password_required_create_own"));
            return;
        }

        Player player = (Player) sender;
        String playerName = player.getName();

        // 既に自身のプロファイルがあるか確認
        if (plugin.getUserManager().userExists(playerName)) {
            sender.sendMessage(tr("error.profile_exists"));
            return;
        }

        ReconUser user = new ReconUser(playerName, password);
        user.setPlayer(playerName); // 自身をplayerとして設定

        // IP ホワイトリスト
        if (params.containsKey("ip")) {
            if (!sender.hasPermission("recon.create.own.ip-whitelist")) {
                sender.sendMessage(tr("error.no_permission.ip_whitelist"));
                return;
            }
            user.setIpWhitelist(parseCommaSeparated(params.get("ip")));
        }

        // OP権限
        if (params.containsKey("op")) {
            if (!sender.hasPermission("recon.create.own.op")) {
                sender.sendMessage(tr("error.no_permission.op_flag"));
                return;
            }
            user.setOp(Boolean.parseBoolean(params.get("op")));
        }

        // パーミッション
        if (params.containsKey("permission")) {
            if (!sender.hasPermission("recon.create.own.group")) {
                sender.sendMessage(tr("error.no_permission.permissions"));
                return;
            }
            user.setPermissions(parseCommaSeparated(params.get("permission")));
        }

        plugin.getUserManager().addUser(user);
        plugin.getReconLogger().logCommandExecution(sender.getName(), "create own profile");
        sender.sendMessage(tr("success.profile_created"));
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
            sender.sendMessage(tr("error.user_not_found", Collections.singletonMap("username", userName)));
            return;
        }

        boolean changed = false;

        // パスワード
        if (params.containsKey("password")) {
            if (!sender.hasPermission("recon.edit.other.password")) {
                sender.sendMessage(tr("error.no_permission.edit_other_password"));
                return;
            }
            user.setPassword(params.get("password"));
            changed = true;
        }

        // IPホワイトリスト（+/-で追加・削除）
        if (params.containsKey("ip")) {
            if (!sender.hasPermission("recon.edit.other.ip-whitelist")) {
                sender.sendMessage(tr("error.no_permission.edit_other_ip_whitelist"));
                return;
            }
            applyListModifications(user.getIpWhitelist(), params.get("ip"));
            changed = true;
        }

        // OP権限
        if (params.containsKey("op")) {
            if (!sender.hasPermission("recon.edit.other.op")) {
                sender.sendMessage(tr("error.no_permission.edit_other_op_flag"));
                return;
            }
            user.setOp(Boolean.parseBoolean(params.get("op")));
            changed = true;
        }

        // プレイヤー
        if (params.containsKey("player")) {
            if (!sender.hasPermission("recon.edit.other.player")) {
                sender.sendMessage(tr("error.no_permission.edit_other_player"));
                return;
            }
            user.setPlayer(params.get("player"));
            changed = true;
        }

        // パーミッション（+/-で追加・削除）
        if (params.containsKey("permission")) {
            if (!sender.hasPermission("recon.edit.other.group")) {
                sender.sendMessage(tr("error.no_permission.edit_other_permissions"));
                return;
            }
            applyListModifications(user.getPermissions(), params.get("permission"));
            changed = true;
        }

        if (changed) {
            plugin.getUserManager().addUser(user);
            plugin.getReconLogger().logCommandExecution(sender.getName(),
                    "edit other user: " + userName);
            sender.sendMessage(tr("success.user_profile_updated", Collections.singletonMap("username", userName)));
        } else {
            sender.sendMessage(tr("info.no_changes"));
        }
    }

    /**
     * 自身のプロファイルを編集
     */
    private void handleEditOwn(CommandSender sender, Map<String, String> params) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(tr("error.player_only_use_user_param"));
            return;
        }

        Player player = (Player) sender;
        String playerName = player.getName();

        ReconUser user = plugin.getUserManager().getUser(playerName);
        if (user == null) {
            // プレイヤー名でユーザーが見つからない場合、player フィールドで検索
            user = plugin.getUserManager().findByPlayer(playerName);
            if (user == null) {
                sender.sendMessage(tr("error.no_profile"));
                return;
            }
        }

        boolean changed = false;

        // パスワード
        if (params.containsKey("password")) {
            if (!sender.hasPermission("recon.edit.own.password")) {
                sender.sendMessage(tr("error.no_permission.edit_own_password"));
                return;
            }
            user.setPassword(params.get("password"));
            changed = true;
        }

        // IPホワイトリスト（+/-で追加・削除）
        if (params.containsKey("ip")) {
            if (!sender.hasPermission("recon.edit.own.ip-whitelist")) {
                sender.sendMessage(tr("error.no_permission.edit_own_ip_whitelist"));
                return;
            }
            applyListModifications(user.getIpWhitelist(), params.get("ip"));
            changed = true;
        }

        // OP権限
        if (params.containsKey("op")) {
            if (!sender.hasPermission("recon.edit.own.op")) {
                sender.sendMessage(tr("error.no_permission.edit_own_op_flag"));
                return;
            }
            user.setOp(Boolean.parseBoolean(params.get("op")));
            changed = true;
        }

        // パーミッション（+/-で追加・削除）
        if (params.containsKey("permission")) {
            if (!sender.hasPermission("recon.edit.own.group")) {
                sender.sendMessage(tr("error.no_permission.edit_own_permissions"));
                return;
            }
            applyListModifications(user.getPermissions(), params.get("permission"));
            changed = true;
        }

        if (changed) {
            plugin.getUserManager().addUser(user);
            plugin.getReconLogger().logCommandExecution(sender.getName(), "edit own profile");
            sender.sendMessage(tr("success.profile_updated"));
        } else {
            sender.sendMessage(tr("info.no_changes"));
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
                sender.sendMessage(tr("error.no_permission.info_other"));
                return;
            }

            String userName = params.get("user");
            ReconUser user = plugin.getUserManager().getUser(userName);
            if (user == null) {
                sender.sendMessage(tr("error.user_not_found", Collections.singletonMap("username", userName)));
                return;
            }
            displayUserInfo(sender, user);
        } else {
            // 自身の情報表示
            if (!(sender instanceof Player)) {
                sender.sendMessage(tr("error.specify_user"));
                return;
            }

            if (!sender.hasPermission("recon.info.own")) {
                sender.sendMessage(tr("error.no_permission.info_own"));
                return;
            }

            Player player = (Player) sender;
            ReconUser user = plugin.getUserManager().getUser(player.getName());
            if (user == null) {
                user = plugin.getUserManager().findByPlayer(player.getName());
            }
            if (user == null) {
                sender.sendMessage(tr("error.no_profile"));
                return;
            }
            displayUserInfo(sender, user);
        }
    }

    /**
     * ユーザー情報を表示する
     */
    private void displayUserInfo(CommandSender sender, ReconUser user) {
        String playerValue = user.getPlayer() != null ? user.getPlayer() : tr("info.player_console");
        String ipValue = user.getIpWhitelist().isEmpty()
            ? tr("info.none")
            : String.join(", ", user.getIpWhitelist());
        String permValue = user.getPermissions().isEmpty()
            ? tr("info.none")
            : String.join(", ", user.getPermissions());
        String opValue = user.isOp() ? tr("bool.true") : tr("bool.false");

        sender.sendMessage(tr("info.header"));
        sender.sendMessage(tr("info.user", Collections.singletonMap("username", user.getUser())));
        sender.sendMessage(tr("info.password"));
        sender.sendMessage(tr("info.player", Collections.singletonMap("player", playerValue)));
        sender.sendMessage(tr("info.op", Collections.singletonMap("op", opValue)));
        sender.sendMessage(tr("info.ip_whitelist", Collections.singletonMap("ips", ipValue)));
        sender.sendMessage(tr("info.permissions", Collections.singletonMap("permissions", permValue)));
        sender.sendMessage(tr("info.footer"));
    }

    // ============================
    // /recon test
    // ============================
    private void handleTest(CommandSender sender) {
        sender.sendMessage(tr("test.header"));
        sender.sendMessage(tr("test.welcome"));
        sender.sendMessage(tr("test.success"));
        sender.sendMessage(tr("test.footer"));
    }

    // ============================
    // /recon reload
    // ============================
    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("recon.reload")) {
            sender.sendMessage(tr("error.no_permission.reload"));
            return;
        }

        try {
            plugin.getConfigManager().loadConfig();
            plugin.getUserManager().loadUsers();
            plugin.getQueueManager().loadQueues();
            plugin.getLangManager().reload();

            plugin.getReconLogger().logCommandExecution(sender.getName(), "reload configuration");
            sender.sendMessage(tr("success.reload"));
            sender.sendMessage(tr("success.reload_files"));
        } catch (Exception e) {
            sender.sendMessage(tr("error.reload_failed",
                    Collections.singletonMap("error", e.getMessage())));
            plugin.getLogger().severe("Error reloading configuration: " + e.getMessage());
        }
    }

    // ============================
    // /recon remove
    // ============================
    private void handleRemove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("recon.remove")) {
            sender.sendMessage(tr("error.no_permission.remove"));
            return;
        }

        Map<String, String> params = parseParams(args, 1);
        String userName = params.get("user");

        if (userName == null || userName.isEmpty()) {
            sender.sendMessage(tr("error.username_required"));
            return;
        }

        if (!plugin.getUserManager().userExists(userName)) {
            sender.sendMessage(tr("error.user_not_found", Collections.singletonMap("username", userName)));
            return;
        }

        plugin.getUserManager().removeUser(userName);
        plugin.getReconLogger().logCommandExecution(sender.getName(),
                "remove user: " + userName);
        sender.sendMessage(tr("success.user_removed", Collections.singletonMap("username", userName)));
    }

    // ============================
    // ユーティリティ
    // ============================

    /**
     * 使い方を表示
     */
    private void sendUsage(CommandSender sender) {
        sender.sendMessage(tr("usage.header"));
        sender.sendMessage(tr("usage.create"));
        sender.sendMessage(tr("usage.edit"));
        sender.sendMessage(tr("usage.info"));
        sender.sendMessage(tr("usage.test"));
        sender.sendMessage(tr("usage.reload"));
        sender.sendMessage(tr("usage.remove"));
        sender.sendMessage("");
        sender.sendMessage(tr("usage.params"));
    }

    private String tr(String key) {
        return plugin.getLangManager().get(key);
    }

    private String tr(String key, Map<String, String> placeholders) {
        return plugin.getLangManager().format(key, placeholders);
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
