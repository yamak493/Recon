package net.enabify.recon.util;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nonceリプレイ攻撃防止用トラッカー
 * 直近1分以内に使用されたnonceを記録し、同一nonceの再利用を阻止する
 */
public class NonceTracker {

    private final ConcurrentHashMap<String, Long> usedNonces = new ConcurrentHashMap<>();
    private static final long NONCE_EXPIRY_MS = 60_000L; // 1分

    /**
     * nonceが使用可能かチェックし、未使用なら記録する
     *
     * @param nonce チェック対象のnonce
     * @return true: 使用可能（未使用）, false: 使用不可（既に使用済み）
     */
    public boolean useNonce(String nonce) {
        cleanup();
        Long existing = usedNonces.putIfAbsent(nonce, System.currentTimeMillis());
        return existing == null;
    }

    /**
     * 期限切れのnonceを削除
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = usedNonces.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (now - entry.getValue() > NONCE_EXPIRY_MS) {
                it.remove();
            }
        }
    }
}
