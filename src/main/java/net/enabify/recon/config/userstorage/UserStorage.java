package net.enabify.recon.config.userstorage;

import net.enabify.recon.model.ReconUser;

import java.util.Collection;
import java.util.Map;

/**
 * Reconユーザー保存基盤の抽象インターフェース
 */
public interface UserStorage {

    /**
     * 保存基盤の初期化処理（接続確認・テーブル作成など）
     */
    void initialize() throws Exception;

    /**
     * 全ユーザーを読み込む
     */
    Map<String, ReconUser> loadAllUsers() throws Exception;

    /**
     * 全ユーザーを保存する
     */
    void saveAllUsers(Collection<ReconUser> users) throws Exception;

    /**
     * ユーザー1件を追加・更新する
     */
    void upsertUser(ReconUser user) throws Exception;

    /**
     * ユーザー1件を削除する
     */
    void deleteUser(String username) throws Exception;

    /**
     * ストレージ名（ログ表示用）
     */
    String getBackendName();

    /**
     * SQL系ストレージか
     */
    boolean isDatabaseBackend();
}
