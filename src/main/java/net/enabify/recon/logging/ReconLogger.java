package net.enabify.recon.logging;

import net.enabify.recon.Recon;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * コマンド実行ログをファイルに記録するロガー
 * logs/ フォルダ内に日付ごとのログファイルを作成する
 */
public class ReconLogger {

    private final File logFolder;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public ReconLogger(Recon plugin) {
        this.logFolder = new File(plugin.getDataFolder(), "logs");
        if (!logFolder.exists()) {
            logFolder.mkdirs();
        }
    }

    /**
     * 汎用ログ記録
     *
     * @param message ログメッセージ
     */
    public void log(String message) {
        String date = dateFormat.format(new Date());
        String time = timeFormat.format(new Date());
        File logFile = new File(logFolder, date + ".log");

        try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, true))) {
            writer.println("[" + time + "] " + message);
        } catch (IOException e) {
            // ロギング失敗はコンソールに警告出力のみ
        }
    }

    /**
     * APIリクエストをログ記録
     */
    public void logApiRequest(String ip, String user, String command, boolean success) {
        log(String.format("[API] IP=%s User=%s Command=%s Success=%s", ip, user, command, success));
    }

    /**
     * コマンド実行をログ記録
     */
    public void logCommandExecution(String executor, String command) {
        log(String.format("[CMD] Executor=%s Command=%s", executor, command));
    }

    /**
     * キュー実行をログ記録
     */
    public void logQueueExecution(String playerName, String command, String user) {
        log(String.format("[QUEUE] Player=%s Command=%s User=%s", playerName, command, user));
    }
}
