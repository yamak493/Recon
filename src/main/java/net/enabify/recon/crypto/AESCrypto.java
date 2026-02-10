package net.enabify.recon.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES暗号化・復号ユーティリティ
 * AES-256-CBC方式を使用し、キーはSHA-256で導出
 * IVはランダム生成し、暗号文の先頭に付加してBase64エンコードする
 */
public class AESCrypto {

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int IV_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * AESキーを導出する
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
     * 平文を暗号化する
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
     * 暗号文を復号する
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
}
