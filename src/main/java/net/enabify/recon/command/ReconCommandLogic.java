package net.enabify.recon.command;

import net.enabify.recon.model.ReconUser;
import net.enabify.recon.platform.PlatformCommandSender;
import net.enabify.recon.platform.ReconPlatform;

import java.util.*;

/**
 * /recon コマンドのプラットフォーム非依存ロジック
 * Bukkit/BungeeCord/Velocityの各コマンドハンドラーから共通で使用される
 */
public class ReconCommandLogic {

    private final ReconPlatform platform;

    // パラメータの省略形→正式名マッピング
    private static final Map<String, String> SHORT_FORMS = new HashMap<>();

    static {
        SHORT_FORMS.put("u", "user");
        SHORT_FORMS.put("pw", "password");
        SHORT_FORMS.put("i", "ip");
        SHORT_FORMS.put("o", "op");
        SHORT_FORMS.put("q", "queue");
        SHORT_FORMS.put("pl", "player");
        SHORT_FORMS.put("pe", "permission");
    }

    private static final List<String> SUB_COMMANDS = Arrays.asList("create", "edit", "info", "test", "reload", "remove");
    private static final List<String> CREATE_EDIT_PARAMS = Arrays.asList(
            "user:", "u:", "password:", "pw:", "ip:", "i:", "op:", "o:", "player:", "pl:", "permission:", "pe:", "queue:", "q:");
    private static final List<String> INFO_PARAMS = Arrays.asList("user:", "u:");
    private static final List<String> REMOVE_PARAMS = Arrays.asList("user:", "u:");

    public ReconCommandLogic(ReconPlatform platform) {
        this.platform = platform;
    }

    /**
     * コマンドを実行する
     *
     * @param sender コマンド送信者
     * @param args   コマンド引数
     * @return 常に true
     */
    public boolean onCommand(PlatformCommandSender sender, String[] args) {
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

    /**
     * タブ補完候補を返す
     *
     * @param args コマンド引数
     * @return 補完候補リスト
     */
    public List<String> tabComplete(String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            for (String sub : SUB_COMMANDS) {
                if (sub.startsWith(partial)) {
                    completions.add(sub);
                }
            }
        } else if (args.length >= 2) {
            String subCommand = args[0].toLowerCase();
            String current = args[args.length - 1].toLowerCase();

            List<String> allParams;
            switch (subCommand) {
                case "create":
                case "edit":
                    allParams = CREATE_EDIT_PARAMS;
                    break;
                case "info":
                    allParams = INFO_PARAMS;
                    break;
                case "remove":
                    allParams = REMOVE_PARAMS;
                    break;
                default:
                    allParams = Collections.emptyList();
                    break;
            }

            completions.addAll(getParamCompletions(current, allParams, args));
        }

        return completions;
    }

    // ============================
    // /recon create
    // ============================
    private void handleCreate(PlatformCommandSender sender, String[] args) {
        Map<String, String> params = parseParams(args, 1);

        boolean isOther = params.containsKey("user");

        if (isOther) {
            handleCreateOther(sender, params);
        } else {
            handleCreateOwn(sender, params);
        }
    }

    private void handleCreateOther(PlatformCommandSender sender, Map<String, String> params) {
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

        if (platform.getUserManager().userExists(userName)) {
            sender.sendMessage(tr("error.user_exists", Collections.singletonMap("username", userName)));
            return;
        }

        ReconUser user = new ReconUser(userName, password);

        if (params.containsKey("ip")) {
            if (!sender.hasPermission("recon.create.other.ip-whitelist")) {
                sender.sendMessage(tr("error.no_permission.ip_whitelist"));
                return;
            }
            user.setIpWhitelist(parseCommaSeparated(params.get("ip")));
        }

        if (params.containsKey("op")) {
            if (!sender.hasPermission("recon.create.other.op")) {
                sender.sendMessage(tr("error.no_permission.op_flag"));
                return;
            }
            user.setOp(Boolean.parseBoolean(params.get("op")));
        }

        if (params.containsKey("queue")) {
            if (!sender.hasPermission("recon.create.other.queue")) {
                sender.sendMessage(tr("error.no_permission.queue_flag"));
                return;
            }
            user.setQueue(Boolean.parseBoolean(params.get("queue")));
        }

        if (params.containsKey("player")) {
            if (!sender.hasPermission("recon.create.other.player")) {
                sender.sendMessage(tr("error.no_permission.player"));
                return;
            }
            user.setPlayer(params.get("player"));
        }

        if (params.containsKey("permission")) {
            if (!sender.hasPermission("recon.create.other.group")) {
                sender.sendMessage(tr("error.no_permission.permissions"));
                return;
            }
            user.setPermissions(parseCommaSeparated(params.get("permission")));
        }

        platform.getUserManager().addUser(user);
        platform.getReconLogger().logCommandExecution(sender.getName(),
                "create other user: " + userName);
        sender.sendMessage(tr("success.user_profile_created", Collections.singletonMap("username", userName)));
    }

    private void handleCreateOwn(PlatformCommandSender sender, Map<String, String> params) {
        if (!sender.isPlayer()) {
            sender.sendMessage(tr("error.player_only_use_user_param"));
            return;
        }

        if (!platform.getConfigManager().isAllowSelfRegistration()) {
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

        String playerName = sender.getPlayerName();

        if (platform.getUserManager().userExists(playerName)) {
            sender.sendMessage(tr("error.profile_exists"));
            return;
        }

        ReconUser user = new ReconUser(playerName, password);
        user.setPlayer(playerName);

        if (params.containsKey("ip")) {
            if (!sender.hasPermission("recon.create.own.ip-whitelist")) {
                sender.sendMessage(tr("error.no_permission.ip_whitelist"));
                return;
            }
            user.setIpWhitelist(parseCommaSeparated(params.get("ip")));
        }

        if (params.containsKey("op")) {
            if (!sender.hasPermission("recon.create.own.op")) {
                sender.sendMessage(tr("error.no_permission.op_flag"));
                return;
            }
            user.setOp(Boolean.parseBoolean(params.get("op")));
        }

        if (params.containsKey("queue")) {
            if (!sender.hasPermission("recon.create.own.queue")) {
                sender.sendMessage(tr("error.no_permission.queue_flag"));
                return;
            }
            user.setQueue(Boolean.parseBoolean(params.get("queue")));
        }

        if (params.containsKey("permission")) {
            if (!sender.hasPermission("recon.create.own.group")) {
                sender.sendMessage(tr("error.no_permission.permissions"));
                return;
            }
            user.setPermissions(parseCommaSeparated(params.get("permission")));
        }

        platform.getUserManager().addUser(user);
        platform.getReconLogger().logCommandExecution(sender.getName(), "create own profile");
        sender.sendMessage(tr("success.profile_created"));
    }

    // ============================
    // /recon edit
    // ============================
    private void handleEdit(PlatformCommandSender sender, String[] args) {
        Map<String, String> params = parseParams(args, 1);

        boolean isOther = params.containsKey("user");

        if (isOther) {
            handleEditOther(sender, params);
        } else {
            handleEditOwn(sender, params);
        }
    }

    private void handleEditOther(PlatformCommandSender sender, Map<String, String> params) {
        String userName = params.get("user");

        ReconUser user = platform.getUserManager().getUser(userName);
        if (user == null) {
            sender.sendMessage(tr("error.user_not_found", Collections.singletonMap("username", userName)));
            return;
        }

        boolean changed = false;

        if (params.containsKey("password")) {
            if (!sender.hasPermission("recon.edit.other.password")) {
                sender.sendMessage(tr("error.no_permission.edit_other_password"));
                return;
            }
            user.setPassword(params.get("password"));
            changed = true;
        }

        if (params.containsKey("ip")) {
            if (!sender.hasPermission("recon.edit.other.ip-whitelist")) {
                sender.sendMessage(tr("error.no_permission.edit_other_ip_whitelist"));
                return;
            }
            applyListModifications(user.getIpWhitelist(), params.get("ip"));
            changed = true;
        }

        if (params.containsKey("op")) {
            if (!sender.hasPermission("recon.edit.other.op")) {
                sender.sendMessage(tr("error.no_permission.edit_other_op_flag"));
                return;
            }
            user.setOp(Boolean.parseBoolean(params.get("op")));
            changed = true;
        }

        if (params.containsKey("queue")) {
            if (!sender.hasPermission("recon.edit.other.queue")) {
                sender.sendMessage(tr("error.no_permission.edit_other_queue_flag"));
                return;
            }
            user.setQueue(Boolean.parseBoolean(params.get("queue")));
            changed = true;
        }

        if (params.containsKey("player")) {
            if (!sender.hasPermission("recon.edit.other.player")) {
                sender.sendMessage(tr("error.no_permission.edit_other_player"));
                return;
            }
            user.setPlayer(params.get("player"));
            changed = true;
        }

        if (params.containsKey("permission")) {
            if (!sender.hasPermission("recon.edit.other.group")) {
                sender.sendMessage(tr("error.no_permission.edit_other_permissions"));
                return;
            }
            applyListModifications(user.getPermissions(), params.get("permission"));
            changed = true;
        }

        if (changed) {
            platform.getUserManager().addUser(user);
            platform.getReconLogger().logCommandExecution(sender.getName(),
                    "edit other user: " + userName);
            sender.sendMessage(tr("success.user_profile_updated", Collections.singletonMap("username", userName)));
        } else {
            sender.sendMessage(tr("info.no_changes"));
        }
    }

    private void handleEditOwn(PlatformCommandSender sender, Map<String, String> params) {
        if (!sender.isPlayer()) {
            sender.sendMessage(tr("error.player_only_use_user_param"));
            return;
        }

        String playerName = sender.getPlayerName();

        ReconUser user = platform.getUserManager().getUser(playerName);
        if (user == null) {
            user = platform.getUserManager().findByPlayer(playerName);
            if (user == null) {
                sender.sendMessage(tr("error.no_profile"));
                return;
            }
        }

        boolean changed = false;

        if (params.containsKey("password")) {
            if (!sender.hasPermission("recon.edit.own.password")) {
                sender.sendMessage(tr("error.no_permission.edit_own_password"));
                return;
            }
            user.setPassword(params.get("password"));
            changed = true;
        }

        if (params.containsKey("ip")) {
            if (!sender.hasPermission("recon.edit.own.ip-whitelist")) {
                sender.sendMessage(tr("error.no_permission.edit_own_ip_whitelist"));
                return;
            }
            applyListModifications(user.getIpWhitelist(), params.get("ip"));
            changed = true;
        }

        if (params.containsKey("op")) {
            if (!sender.hasPermission("recon.edit.own.op")) {
                sender.sendMessage(tr("error.no_permission.edit_own_op_flag"));
                return;
            }
            user.setOp(Boolean.parseBoolean(params.get("op")));
            changed = true;
        }

        if (params.containsKey("queue")) {
            if (!sender.hasPermission("recon.edit.own.queue")) {
                sender.sendMessage(tr("error.no_permission.edit_own_queue_flag"));
                return;
            }
            user.setQueue(Boolean.parseBoolean(params.get("queue")));
            changed = true;
        }

        if (params.containsKey("permission")) {
            if (!sender.hasPermission("recon.edit.own.group")) {
                sender.sendMessage(tr("error.no_permission.edit_own_permissions"));
                return;
            }
            applyListModifications(user.getPermissions(), params.get("permission"));
            changed = true;
        }

        if (changed) {
            platform.getUserManager().addUser(user);
            platform.getReconLogger().logCommandExecution(sender.getName(), "edit own profile");
            sender.sendMessage(tr("success.profile_updated"));
        } else {
            sender.sendMessage(tr("info.no_changes"));
        }
    }

    // ============================
    // /recon info
    // ============================
    private void handleInfo(PlatformCommandSender sender, String[] args) {
        Map<String, String> params = parseParams(args, 1);

        if (params.containsKey("user")) {
            if (!sender.hasPermission("recon.info.other")) {
                sender.sendMessage(tr("error.no_permission.info_other"));
                return;
            }

            String userName = params.get("user");
            ReconUser user = platform.getUserManager().getUser(userName);
            if (user == null) {
                sender.sendMessage(tr("error.user_not_found", Collections.singletonMap("username", userName)));
                return;
            }
            displayUserInfo(sender, user);
        } else {
            if (!sender.isPlayer()) {
                sender.sendMessage(tr("error.specify_user"));
                return;
            }

            if (!sender.hasPermission("recon.info.own")) {
                sender.sendMessage(tr("error.no_permission.info_own"));
                return;
            }

            String playerName = sender.getPlayerName();
            ReconUser user = platform.getUserManager().getUser(playerName);
            if (user == null) {
                user = platform.getUserManager().findByPlayer(playerName);
            }
            if (user == null) {
                sender.sendMessage(tr("error.no_profile"));
                return;
            }
            displayUserInfo(sender, user);
        }
    }

    private void displayUserInfo(PlatformCommandSender sender, ReconUser user) {
        String playerValue = user.getPlayer() != null ? user.getPlayer() : tr("info.player_console");
        String ipValue = user.getIpWhitelist().isEmpty()
            ? tr("info.none")
            : String.join(", ", user.getIpWhitelist());
        String permValue = user.getPermissions().isEmpty()
            ? tr("info.none")
            : String.join(", ", user.getPermissions());
        String opValue = user.isOp() ? tr("bool.true") : tr("bool.false");
        String queueValue = user.isQueue() ? tr("bool.true") : tr("bool.false");

        sender.sendMessage(tr("info.header"));
        sender.sendMessage(tr("info.user", Collections.singletonMap("username", user.getUser())));
        sender.sendMessage(tr("info.password"));
        sender.sendMessage(tr("info.player", Collections.singletonMap("player", playerValue)));
        sender.sendMessage(tr("info.op", Collections.singletonMap("op", opValue)));
        sender.sendMessage(tr("info.queue", Collections.singletonMap("queue", queueValue)));
        sender.sendMessage(tr("info.ip_whitelist", Collections.singletonMap("ips", ipValue)));
        sender.sendMessage(tr("info.permissions", Collections.singletonMap("permissions", permValue)));
        sender.sendMessage(tr("info.footer"));
    }

    // ============================
    // /recon test
    // ============================
    private void handleTest(PlatformCommandSender sender) {
        sender.sendMessage(tr("test.header"));
        sender.sendMessage(tr("test.welcome"));
        sender.sendMessage(tr("test.success"));
        sender.sendMessage(tr("test.footer"));
    }

    // ============================
    // /recon reload
    // ============================
    private void handleReload(PlatformCommandSender sender) {
        if (!sender.hasPermission("recon.reload")) {
            sender.sendMessage(tr("error.no_permission.reload"));
            return;
        }

        try {
            platform.getConfigManager().loadConfig();
            platform.getUserManager().reloadStorageBackend();
            platform.getQueueManager().loadQueues();
            platform.getLangManager().reload();

            platform.getReconLogger().logCommandExecution(sender.getName(), "reload configuration");
            sender.sendMessage(tr("success.reload"));
            sender.sendMessage(tr("success.reload_files"));
        } catch (Exception e) {
            sender.sendMessage(tr("error.reload_failed",
                    Collections.singletonMap("error", e.getMessage())));
            platform.getPluginLogger().severe("Error reloading configuration: " + e.getMessage());
        }
    }

    // ============================
    // /recon remove
    // ============================
    private void handleRemove(PlatformCommandSender sender, String[] args) {
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

        if (!platform.getUserManager().userExists(userName)) {
            sender.sendMessage(tr("error.user_not_found", Collections.singletonMap("username", userName)));
            return;
        }

        platform.getUserManager().removeUser(userName);
        platform.getReconLogger().logCommandExecution(sender.getName(),
                "remove user: " + userName);
        sender.sendMessage(tr("success.user_removed", Collections.singletonMap("username", userName)));
    }

    // ============================
    // ユーティリティ
    // ============================

    private void sendUsage(PlatformCommandSender sender) {
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
        return platform.getLangManager().get(key);
    }

    private String tr(String key, Map<String, String> placeholders) {
        return platform.getLangManager().format(key, placeholders);
    }

    /**
     * コマンド引数からパラメータをパースする
     */
    public static Map<String, String> parseParams(String[] args, int startIndex) {
        Map<String, String> params = new LinkedHashMap<>();

        for (int i = startIndex; i < args.length; i++) {
            String arg = args[i];
            int colonIndex = arg.indexOf(':');

            if (colonIndex > 0 && colonIndex < arg.length() - 1) {
                String key = arg.substring(0, colonIndex).toLowerCase();
                String value = arg.substring(colonIndex + 1);

                if (SHORT_FORMS.containsKey(key)) {
                    key = SHORT_FORMS.get(key);
                }

                params.put(key, value);
            }
        }

        return params;
    }

    private List<String> parseCommaSeparated(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isEmpty()) return result;

        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
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

    private void applyListModifications(List<String> list, String value) {
        if (value == null || value.isEmpty()) return;

        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.startsWith("-")) {
                String toRemove = trimmed.substring(1).trim();
                list.remove(toRemove);
            } else {
                String toAdd = trimmed.startsWith("+") ? trimmed.substring(1).trim() : trimmed;
                if (!toAdd.isEmpty() && !list.contains(toAdd)) {
                    list.add(toAdd);
                }
            }
        }
    }

    /**
     * パラメータ補完候補のフィルタリング
     */
    private List<String> getParamCompletions(String current, List<String> allParams, String[] args) {
        List<String> usedKeys = new ArrayList<>();
        for (int i = 1; i < args.length - 1; i++) {
            int colonIdx = args[i].indexOf(':');
            if (colonIdx > 0) {
                usedKeys.add(args[i].substring(0, colonIdx + 1).toLowerCase());
            }
        }

        List<String> completions = new ArrayList<>();
        for (String p : allParams) {
            if (p.startsWith(current) && !isAlreadyUsed(p, usedKeys)) {
                completions.add(p);
            }
        }
        return completions;
    }

    private boolean isAlreadyUsed(String param, List<String> usedKeys) {
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
