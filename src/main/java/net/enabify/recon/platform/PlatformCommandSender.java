package net.enabify.recon.platform;

/**
 * プラットフォーム非依存のコマンド送信者インターフェース
 * Bukkit CommandSender / BungeeCord CommandSender / Velocity CommandSource を抽象化する
 */
public interface PlatformCommandSender {

    /**
     * メッセージを送信する
     *
     * @param message 送信するメッセージ
     */
    void sendMessage(String message);

    /**
     * パーミッションを所持しているか確認する
     *
     * @param permission パーミッション文字列
     * @return 所持している場合 true
     */
    boolean hasPermission(String permission);

    /**
     * 送信者の名前を取得する
     *
     * @return 送信者名
     */
    String getName();

    /**
     * プレイヤーかどうかを判定する
     *
     * @return プレイヤーの場合 true
     */
    boolean isPlayer();

    /**
     * プレイヤー名を取得する
     * isPlayer() == true の場合のみ意味がある
     *
     * @return プレイヤー名、プレイヤーでない場合は null
     */
    String getPlayerName();
}
