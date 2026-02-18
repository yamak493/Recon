package net.enabify.recon.platform;

import net.enabify.recon.execution.ExecutionResult;
import net.enabify.recon.model.ReconUser;

/**
 * コマンド実行サービスのインターフェース
 * 各プラットフォーム（Bukkit、BungeeCord、Velocity）が実装する
 */
public interface CommandExecutionService {

    /**
     * ユーザー設定に基づいてコマンドを実行する
     *
     * @param reconUser 実行元ユーザーの設定
     * @param command   実行するコマンド
     * @param queue     プレイヤーがオフライン時にキューに保存するか
     * @return 実行結果
     */
    ExecutionResult executeCommand(ReconUser reconUser, String command, boolean queue);
}
