package net.enabify.recon.http;

import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * IPアドレス単位のレート制限
 * 設定された期間内のリクエスト数を制限する
 */
public class RateLimiter {

    private final int maxRequests;
    private static final long WINDOW_MS = 60_000L; // 1分間
    private final ConcurrentHashMap<String, Queue<Long>> requestLog = new ConcurrentHashMap<>();

    public RateLimiter(int maxRequestsPerMinute) {
        this.maxRequests = maxRequestsPerMinute;
    }

    /**
     * リクエストが許可されるかチェックし、許可された場合はカウントする
     *
     * @param ip IPアドレス
     * @return true: 許可, false: レート制限超過
     */
    public boolean allowRequest(String ip) {
        long now = System.currentTimeMillis();
        Queue<Long> timestamps = requestLog.computeIfAbsent(ip, k -> new ConcurrentLinkedQueue<>());

        // ウィンドウ外の古いエントリを削除
        while (!timestamps.isEmpty() && now - timestamps.peek() > WINDOW_MS) {
            timestamps.poll();
        }

        if (timestamps.size() >= maxRequests) {
            return false;
        }

        timestamps.offer(now);
        return true;
    }

    /**
     * 全エントリのクリーンアップ（定期実行用）
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Queue<Long>>> it = requestLog.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Queue<Long>> entry = it.next();
            Queue<Long> timestamps = entry.getValue();
            while (!timestamps.isEmpty() && now - timestamps.peek() > WINDOW_MS) {
                timestamps.poll();
            }
            if (timestamps.isEmpty()) {
                it.remove();
            }
        }
    }
}
