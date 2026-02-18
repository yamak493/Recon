package net.enabify.recon.proxy.velocity;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * SLF4J Logger から java.util.logging.Logger へのブリッジアダプター
 * Velocityは SLF4J を使用するが、共通コードは JUL (java.util.logging) を使用するため、
 * このアダプターで変換する
 */
public class VelocityLoggerAdapter extends Logger {

    private final org.slf4j.Logger slf4jLogger;

    public VelocityLoggerAdapter(org.slf4j.Logger slf4jLogger) {
        super("Recon", null);
        this.slf4jLogger = slf4jLogger;
        setLevel(Level.ALL);

        // デフォルトのハンドラーを削除しSLF4Jに委譲
        setUseParentHandlers(false);
        addHandler(new Slf4jHandler());
    }

    /**
     * JULログレコードをSLF4Jにルーティングするハンドラー
     */
    private class Slf4jHandler extends Handler {

        @Override
        public void publish(LogRecord record) {
            if (record == null) return;

            String message = record.getMessage();
            Level level = record.getLevel();
            Throwable thrown = record.getThrown();

            if (level.intValue() >= Level.SEVERE.intValue()) {
                if (thrown != null) {
                    slf4jLogger.error(message, thrown);
                } else {
                    slf4jLogger.error(message);
                }
            } else if (level.intValue() >= Level.WARNING.intValue()) {
                if (thrown != null) {
                    slf4jLogger.warn(message, thrown);
                } else {
                    slf4jLogger.warn(message);
                }
            } else if (level.intValue() >= Level.INFO.intValue()) {
                if (thrown != null) {
                    slf4jLogger.info(message, thrown);
                } else {
                    slf4jLogger.info(message);
                }
            } else {
                if (thrown != null) {
                    slf4jLogger.debug(message, thrown);
                } else {
                    slf4jLogger.debug(message);
                }
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() throws SecurityException {
        }
    }
}
