package net.enabify.recon.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Recon接続ユーザーモデル
 * users.ymlに保存されるユーザー情報を表現する
 */
public class ReconUser {

    private String user;
    private String password;
    private List<String> ipWhitelist;
    private boolean op;
    private String player;
    private List<String> permissions;

    public ReconUser(String user, String password) {
        this.user = user;
        this.password = password;
        this.ipWhitelist = new ArrayList<>();
        this.op = false;
        this.player = null;
        this.permissions = new ArrayList<>();
    }

    // --- Getters / Setters ---

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public List<String> getIpWhitelist() {
        return ipWhitelist;
    }

    public void setIpWhitelist(List<String> ipWhitelist) {
        this.ipWhitelist = ipWhitelist != null ? ipWhitelist : new ArrayList<>();
    }

    public boolean isOp() {
        return op;
    }

    public void setOp(boolean op) {
        this.op = op;
    }

    public String getPlayer() {
        return player;
    }

    public void setPlayer(String player) {
        this.player = player;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<String> permissions) {
        this.permissions = permissions != null ? permissions : new ArrayList<>();
    }
}
