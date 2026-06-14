package net.enabify.recon.client;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/**
 * Recon - REST API Client for Minecraft
 *
 * A Java client library for communicating with the Recon plugin's REST API.
 *
 * Supports two protocols:
 *   - v2 (default, recommended): AES-256-GCM (authenticated) + PBKDF2-HMAC-SHA256.
 *     Detects tampering and resists offline password brute-force, providing meaningful
 *     security even WITHOUT TLS.
 *   - v1 (legacy): AES-256-CBC + single SHA-256 key derivation (no authentication).
 *
 * @author Enabify
 * @license MIT (Mobile application distribution prohibited)
 */
public class Recon {

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final int timeout;
    private final boolean useSSL;
    private final int protocol;
    private final int iterations;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Create a new Recon client instance (protocol v2, 100000 iterations).
     */
    public Recon(String host, int port, String user, String password, int timeout) {
        this(host, port, user, password, timeout, false, 2, 100000);
    }

    public Recon(String host, int port, String user, String password, int timeout, boolean useSSL) {
        this(host, port, user, password, timeout, useSSL, 2, 100000);
    }

    /**
     * @param protocol   Protocol version: 2 (recommended) or 1 (legacy)
     * @param iterations PBKDF2 iterations for v2 (must meet the server floor)
     */
    public Recon(String host, int port, String user, String password, int timeout,
                 boolean useSSL, int protocol, int iterations) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.timeout = timeout;
        this.useSSL = useSSL;
        this.protocol = protocol;
        this.iterations = iterations;
    }

    /**
     * Send a command to the Minecraft server.
     *
     * @param command The command to execute (without leading /)
     * @param queue   Whether to queue the command if the player is offline
     * @return ReconResponse containing the result
     */
    public ReconResponse sendCommand(String command, boolean queue) {
        try {
            String nonce = generateNonce();
            long timestamp = System.currentTimeMillis() / 1000L;
            boolean useV2 = protocol == 2;

            // Derive key and encrypt command (AAD binds metadata in v2)
            String aad = buildAad(user, nonce, timestamp);
            String encrypted;
            if (useV2) {
                byte[] key = deriveKeyV2(password, nonce, timestamp, iterations);
                encrypted = encryptGcm("RCON_" + command, key, aad);
            } else {
                byte[] key = deriveKey(password, nonce, timestamp);
                encrypted = encrypt("RCON_" + command, key);
            }

            // Build JSON payload
            String iterationsField = useV2 ? String.format(",\"iterations\":%d", iterations) : "";
            String payload = String.format(
                    "{\"user\":\"%s\",\"protocol\":%d%s,\"nonce\":\"%s\",\"timestamp\":%d,\"queue\":%s,\"command\":\"%s\"}",
                    escapeJson(user), protocol, iterationsField, escapeJson(nonce), timestamp, queue, escapeJson(encrypted));

            // Send HTTP POST
            String urlStr = String.format("%s://%s:%d/", useSSL ? "https" : "http", host, port);
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            int httpCode = conn.getResponseCode();
            String responseBody;

            try (InputStream is = (httpCode >= 400) ? conn.getErrorStream() : conn.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                responseBody = sb.toString();
            }

            conn.disconnect();

            // Parse JSON response (simple parser)
            boolean success = responseBody.contains("\"success\":true");
            String error = extractJsonValue(responseBody, "error");

            if (success) {
                String serverNonce = extractJsonValue(responseBody, "nonce");
                long serverTimestamp = extractJsonLong(responseBody, "timestamp");
                long respProtocolL = extractJsonLong(responseBody, "protocol");
                int respProtocol = respProtocolL == 0 ? protocol : (int) respProtocolL;
                boolean respV2 = respProtocol == 2;
                long respIterL = extractJsonLong(responseBody, "iterations");
                int respIterations = respIterL == 0 ? iterations : (int) respIterL;
                String respAad = buildAad(user, serverNonce, serverTimestamp);

                String encryptedResponse = extractJsonValue(responseBody, "response");
                String encryptedPlainResponse = extractJsonValue(responseBody, "plainResponse");

                byte[] responseKey = respV2
                        ? deriveKeyV2(password, serverNonce, serverTimestamp, respIterations)
                        : deriveKey(password, serverNonce, serverTimestamp);

                String decrypted = respV2
                        ? decryptGcm(encryptedResponse, responseKey, respAad)
                        : decrypt(encryptedResponse, responseKey);
                String decryptedPlain;
                if (encryptedPlainResponse != null) {
                    decryptedPlain = respV2
                            ? decryptGcm(encryptedPlainResponse, responseKey, respAad)
                            : decrypt(encryptedPlainResponse, responseKey);
                } else {
                    decryptedPlain = decrypted;
                }

                return new ReconResponse(true, decrypted, decryptedPlain, null);
            }

            return new ReconResponse(false, null, null, error != null ? error : "Request failed (HTTP " + httpCode + ")");

        } catch (Exception e) {
            return new ReconResponse(false, null, null, "Connection error: " + e.getMessage());
        }
    }

    private static String buildAad(String user, String nonce, long timestamp) {
        return user + "|" + nonce + "|" + timestamp;
    }

    // --- v2: PBKDF2 + AES-256-GCM ---

    private static byte[] deriveKeyV2(String password, String nonce, long timestamp, int iterations) throws Exception {
        byte[] salt = (nonce + "_" + timestamp).getBytes(StandardCharsets.UTF_8);
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static String encryptGcm(String plaintext, byte[] key, String aad) throws Exception {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        if (aad != null) {
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        }
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    private static String decryptGcm(String ciphertext, byte[] key, String aad) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(ciphertext);
        byte[] iv = Arrays.copyOfRange(decoded, 0, 12);
        byte[] encrypted = Arrays.copyOfRange(decoded, 12, decoded.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        if (aad != null) {
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        }
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    // --- v1 (legacy): SHA-256 + AES-256-CBC ---

    private static byte[] deriveKey(String password, String nonce, long timestamp) throws Exception {
        String combined = password + "_" + nonce + "_" + timestamp;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(combined.getBytes(StandardCharsets.UTF_8));
    }

    private static String encrypt(String plaintext, byte[] key) throws Exception {
        byte[] iv = new byte[16];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    private static String decrypt(String ciphertext, byte[] key) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(ciphertext);
        byte[] iv = new byte[16];
        System.arraycopy(decoded, 0, iv, 0, 16);
        byte[] encrypted = new byte[decoded.length - 16];
        System.arraycopy(decoded, 16, encrypted, 0, encrypted.length);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private static String generateNonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // --- Simple JSON helpers ---

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return null;
        return json.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static long extractJsonLong(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start == -1) return 0;
        start += search.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Response object returned by sendCommand.
     */
    public static class ReconResponse {
        private final boolean success;
        private final String response;
        private final String plainResponse;
        private final String error;

        public ReconResponse(boolean success, String response, String plainResponse, String error) {
            this.success = success;
            this.response = response;
            this.plainResponse = plainResponse;
            this.error = error;
        }

        public boolean isSuccess() { return success; }
        public String getResponse() { return response; }
        public String getPlainResponse() { return plainResponse; }
        public String getError() { return error; }

        @Override
        public String toString() {
            if (success) return "Success: " + response;
            return "Error: " + error;
        }
    }
}
