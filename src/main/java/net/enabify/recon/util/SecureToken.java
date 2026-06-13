package net.enabify.recon.util;

import java.security.SecureRandom;

/**
 * 暗号学的に安全なランダムトークン生成ユーティリティ。
 * 自動登録時のパスワード生成などに使用する。
 *
 * 紛らわしい文字（0/O, 1/l/I など）を除いた英数字を用いる。
 */
public final class SecureToken {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();

    private SecureToken() {
    }

    /**
     * 指定長のランダムトークンを生成する。
     *
     * @param length 文字数
     * @return ランダムトークン
     */
    public static String generate(int length) {
        if (length <= 0) {
            length = 16;
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
