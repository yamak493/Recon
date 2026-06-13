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
        long now = System.currentTimeMillis();
        Long existing = usedNonces.putIfAbsent(nonce, now);
        if (existing == null) {
            // 未使用のnonce
            return true;
        }
        // 既に記録があるが、期限切れであれば再利用を許可しタイムスタンプを更新する。
        // 毎回の全件スキャン（O(n)）を避け、エントリ単位で期限を判定する。
        // 期限切れ要素の最終的な削除は定期実行の cleanup() に委譲する。
        if (now - existing > NONCE_EXPIRY_MS) {
            return usedNonces.replace(nonce, existing, now);
        }
        return false;
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
