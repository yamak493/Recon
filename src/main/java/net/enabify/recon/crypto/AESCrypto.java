package net.enabify.recon.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES暗号化・復号ユーティリティ
 *
 * プロトコル v1 (レガシー):
 *   AES-256-CBC + SHA-256 単一ハッシュ鍵導出（認証なし）。
 *   既存クライアント・モバイルアプリとの互換のために残置。
 *
 * プロトコル v2 (推奨):
 *   AES-256-GCM（認証付き暗号 / AEAD）+ PBKDF2-HMAC-SHA256 による鍵導出。
 *   - GCM によりメッセージの改ざんを検知（CBC のパディングオラクルを排除）。
 *   - PBKDF2 の高反復により、平文 HTTP を盗聴されてもパスワードのオフライン
 *     総当たりを困難にする（TLS 証明書なしでも実用的な安全性を確保）。
 *   - AAD にメタデータ（user/nonce/timestamp）を束縛し、MITM による
 *     フィールド改ざんを検知する。
 */
public class AESCrypto {

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int IV_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    // --- v2 (GCM / PBKDF2) パラメータ ---
    private static final String GCM_ALGORITHM = "AES/GCM/NoPadding";
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int GCM_IV_LENGTH = 12;     // GCM 推奨 IV 長
    private static final int GCM_TAG_BITS = 128;     // 認証タグ長
    private static final int KEY_BITS = 256;         // AES-256
    private static final int MIN_GCM_CIPHERTEXT = GCM_IV_LENGTH + (GCM_TAG_BITS / 8);

    /**
     * AESキーを導出する（v1: レガシー）
     * 「パスワード_ランダム文字列_タイムスタンプ」をSHA-256でハッシュし32バイトキーを生成
     *
     * @param password  パスワード
     * @param nonce     ランダム文字列
     * @param timestamp Unixタイムスタンプ（秒）
     * @return AES-256用の32バイトキー
     */
    public static byte[] deriveKey(String password, String nonce, long timestamp) throws Exception {
        String combined = password + "_" + nonce + "_" + timestamp;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(combined.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * AESキーを導出する（v2: PBKDF2-HMAC-SHA256）
     * salt にはリクエスト毎に一意な nonce + timestamp を用いる。
     *
     * @param password   パスワード
     * @param nonce      ランダム文字列（salt 兼用）
     * @param timestamp  Unixタイムスタンプ（秒、salt 兼用）
     * @param iterations PBKDF2 反復回数
     * @return AES-256用の32バイトキー
     */
    public static byte[] deriveKeyPbkdf2(String password, String nonce, long timestamp, int iterations)
            throws Exception {
        if (iterations < 1) {
            iterations = 1;
        }
        byte[] salt = (nonce + "_" + timestamp).getBytes(StandardCharsets.UTF_8);
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * 平文を暗号化する（v1: AES-256-CBC）
     *
     * @param plaintext 平文
     * @param key       32バイトのAESキー
     * @return Base64エンコードされた暗号文（IV + 暗号文）
     */
    public static String encrypt(String plaintext, byte[] key) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        RANDOM.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        // IV + 暗号文を結合
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    /**
     * 暗号文を復号する（v1: AES-256-CBC）
     *
     * @param ciphertext Base64エンコードされた暗号文（IV + 暗号文）
     * @param key        32バイトのAESキー
     * @return 復号された平文
     */
    public static String decrypt(String ciphertext, byte[] key) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(ciphertext);

        if (decoded.length < IV_LENGTH) {
            throw new IllegalArgumentException("Ciphertext too short");
        }

        // IVを抽出（先頭16バイト）
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(decoded, 0, iv, 0, IV_LENGTH);

        // 暗号文を抽出（残り）
        byte[] encrypted = new byte[decoded.length - IV_LENGTH];
        System.arraycopy(decoded, IV_LENGTH, encrypted, 0, encrypted.length);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] decrypted = cipher.doFinal(encrypted);

        return new String(decrypted, StandardCharsets.UTF_8);
    }

    /**
     * 平文を暗号化する（v2: AES-256-GCM, AEAD）
     *
     * @param plaintext 平文
     * @param key       32バイトのAESキー
     * @param aad       追加認証データ（暗号化されないが改ざん検知の対象。null可）
     * @return Base64エンコードされた暗号文（IV(12) + 暗号文 + 認証タグ(16)）
     */
    public static String encryptGcm(String plaintext, byte[] key, String aad) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        RANDOM.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(GCM_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        if (aad != null) {
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        }
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    /**
     * 暗号文を復号する（v2: AES-256-GCM, AEAD）
     * 認証タグまたはAADが一致しない場合は例外を投げる（改ざん検知）。
     *
     * @param ciphertext Base64エンコードされた暗号文（IV(12) + 暗号文 + 認証タグ(16)）
     * @param key        32バイトのAESキー
     * @param aad        追加認証データ（暗号化時と同一である必要がある。null可）
     * @return 復号された平文
     */
    public static String decryptGcm(String ciphertext, byte[] key, String aad) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(ciphertext);

        if (decoded.length < MIN_GCM_CIPHERTEXT) {
            throw new IllegalArgumentException("Ciphertext too short");
        }

        byte[] iv = Arrays.copyOfRange(decoded, 0, GCM_IV_LENGTH);
        byte[] encrypted = Arrays.copyOfRange(decoded, GCM_IV_LENGTH, decoded.length);

        Cipher cipher = Cipher.getInstance(GCM_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        if (aad != null) {
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        }
        byte[] decrypted = cipher.doFinal(encrypted);

        return new String(decrypted, StandardCharsets.UTF_8);
    }
}
