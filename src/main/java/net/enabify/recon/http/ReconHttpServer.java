package net.enabify.recon.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.enabify.recon.Recon;
import net.enabify.recon.crypto.AESCrypto;
import net.enabify.recon.execution.CommandRunner;
import net.enabify.recon.model.ReconUser;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * REST APIを提供するHTTPサーバー
 * JDK内蔵のcom.sun.net.httpserver.HttpServerを使用してルート直下を受け付ける
 */
public class ReconHttpServer {

    private final Recon plugin;
    private HttpServer server;

    public ReconHttpServer(Recon plugin) {
        this.plugin = plugin;
    }

    /**
     * HTTPサーバーを起動する
     */
    public void start() throws IOException {
        int port = plugin.getConfigManager().getPort();
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new ApiHandler());
        server.setExecutor(null); // デフォルトのexecutorを使用
        server.start();
        plugin.getLogger().info("Recon HTTP server started on port " + port);
    }

    /**
     * HTTPサーバーを停止する
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            plugin.getLogger().info("Recon HTTP server stopped.");
        }
    }

    /**
     * APIリクエストハンドラー
     * 全てのHTTPリクエストを処理する
     */
    private class ApiHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();

            try {
                // POSTメソッドのみ受付
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendErrorResponse(exchange, 404, plugin.getLangManager().get("http.only_post"));
                    return;
                }

                // パスチェック（ルート直下のみ）
                String path = exchange.getRequestURI().getPath();
                if (!"/".equals(path)) {
                    sendErrorResponse(exchange, 404, plugin.getLangManager().get("http.only_root"));
                    return;
                }

                // レート制限チェック
                if (!plugin.getRateLimiter().allowRequest(clientIp)) {
                    sendErrorResponse(exchange, 429, plugin.getLangManager().get("http.rate_limited"));
                    return;
                }

                // リクエストボディを読み取り
                String body = readRequestBody(exchange);

                // JSONパース
                JsonObject requestJson;
                try {
                    requestJson = new JsonParser().parse(body).getAsJsonObject();
                } catch (Exception e) {
                    sendErrorResponse(exchange, 400, plugin.getLangManager().get("http.invalid_json"));
                    return;
                }

                // 必須フィールドのチェック
                if (!requestJson.has("user") || !requestJson.has("nonce") ||
                        !requestJson.has("timestamp") || !requestJson.has("command")) {
                    sendErrorResponse(exchange, 400,
                        plugin.getLangManager().get("http.missing_required_fields"));
                    return;
                }

                String userName = requestJson.get("user").getAsString();
                String nonce = requestJson.get("nonce").getAsString();
                long timestamp = requestJson.get("timestamp").getAsLong();
                boolean queueRequested = requestJson.has("queue") && requestJson.get("queue").getAsBoolean();
                String encryptedCommand = requestJson.get("command").getAsString();

                // ユーザー認証
                ReconUser reconUser = plugin.getUserManager().getUser(userName);
                if (reconUser == null) {
                    plugin.getReconLogger().logApiRequest(clientIp, userName, "(unknown)", false);
                    sendErrorResponse(exchange, 401, plugin.getLangManager().get("http.auth_user_not_found"));
                    return;
                }

                boolean queueEnabled = queueRequested &&
                        (plugin.getConfigManager().isAllowQueueForAllUsers() || reconUser.isQueue());

                // IPホワイトリストチェック（グローバル）
                List<String> globalWhitelist = plugin.getConfigManager().getGlobalIpWhitelist();
                if (globalWhitelist != null && !globalWhitelist.isEmpty()) {
                    if (!globalWhitelist.contains(clientIp)) {
                        plugin.getReconLogger().logApiRequest(clientIp, userName, "(blocked)", false);
                        sendErrorResponse(exchange, 403,
                                plugin.getLangManager().get("http.ip_not_whitelisted_global"));
                        return;
                    }
                }

                // IPホワイトリストチェック（ユーザー別）
                List<String> userWhitelist = reconUser.getIpWhitelist();
                if (userWhitelist != null && !userWhitelist.isEmpty()) {
                    if (!userWhitelist.contains(clientIp)) {
                        plugin.getReconLogger().logApiRequest(clientIp, userName, "(blocked)", false);
                        sendErrorResponse(exchange, 403,
                                plugin.getLangManager().get("http.ip_not_whitelisted_user"));
                        return;
                    }
                }

                // タイムスタンプ検証（直近1分以内）
                long now = System.currentTimeMillis() / 1000L;
                if (Math.abs(now - timestamp) > 60) {
                    plugin.getReconLogger().logApiRequest(clientIp, userName, "(invalid timestamp)", false);
                    sendErrorResponse(exchange, 401,
                            plugin.getLangManager().get("http.timestamp_out_of_range"));
                    return;
                }

                // nonce検証（同一nonceの再利用防止）
                if (!plugin.getNonceTracker().useNonce(nonce)) {
                    plugin.getReconLogger().logApiRequest(clientIp, userName, "(duplicate nonce)", false);
                    sendErrorResponse(exchange, 401, plugin.getLangManager().get("http.nonce_used"));
                    return;
                }

                // コマンドの復号
                String decryptedCommand;
                try {
                    byte[] key = AESCrypto.deriveKey(reconUser.getPassword(), nonce, timestamp);
                    decryptedCommand = AESCrypto.decrypt(encryptedCommand, key);
                } catch (Exception e) {
                    plugin.getReconLogger().logApiRequest(clientIp, userName, "(decrypt failed)", false);
                    sendErrorResponse(exchange, 401,
                            plugin.getLangManager().get("http.decrypt_failed"));
                    return;
                }

                // RCON_ プレフィックスのチェック
                if (!decryptedCommand.startsWith("RCON_")) {
                    plugin.getReconLogger().logApiRequest(clientIp, userName, "(invalid prefix)", false);
                    sendErrorResponse(exchange, 401,
                            plugin.getLangManager().get("http.invalid_command_format"));
                    return;
                }

                // RCON_ プレフィックスを除去
                String command = decryptedCommand.substring(5);

                // ロギング
                plugin.getReconLogger().logApiRequest(clientIp, userName, command, true);

                // コマンド実行
                CommandRunner.ExecutionResult result;
                try {
                    result = plugin.getCommandRunner().executeCommand(reconUser, command, queueEnabled);
                } catch (Exception e) {
                    plugin.getLogger().severe("Error executing command: " + e.getMessage());
                    sendErrorResponse(exchange, 500,
                            plugin.getLangManager().get("http.execute_error"));
                    return;
                }

                // レスポンスの暗号化
                String serverNonce = UUID.randomUUID().toString().replace("-", "");
                long serverTimestamp = System.currentTimeMillis() / 1000L;
                String responseText = result.response != null ? result.response : "";
                String plainResponseText = result.plainResponse != null ? result.plainResponse : "";

                String encryptedResponse;
                String encryptedPlainResponse;
                try {
                    byte[] responseKey = AESCrypto.deriveKey(
                            reconUser.getPassword(), serverNonce, serverTimestamp);
                    encryptedResponse = AESCrypto.encrypt(responseText, responseKey);
                    encryptedPlainResponse = AESCrypto.encrypt(plainResponseText, responseKey);
                } catch (Exception e) {
                    sendErrorResponse(exchange, 500, plugin.getLangManager().get("http.encrypt_failed"));
                    return;
                }

                // レスポンスJSON構築
                JsonObject responseJson = new JsonObject();
                responseJson.addProperty("user", userName);
                responseJson.addProperty("nonce", serverNonce);
                responseJson.addProperty("timestamp", serverTimestamp);
                responseJson.addProperty("success", result.success);
                responseJson.addProperty("response", encryptedResponse);
                responseJson.addProperty("plainResponse", encryptedPlainResponse);
                if (!result.success && result.error != null) {
                    responseJson.addProperty("error", result.error);
                }

                sendResponse(exchange, 200, responseJson.toString());

            } catch (Exception e) {
                plugin.getLogger().severe("Unexpected error in HTTP handler: " + e.getMessage());
                try {
                    sendErrorResponse(exchange, 500, plugin.getLangManager().get("http.unexpected_error"));
                } catch (IOException ignored) {
                    // レスポンス送信が既に失敗している場合
                }
            }
        }
    }

    /**
     * リクエストボディを文字列として読み取る
     */
    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    /**
     * エラーレスポンスを送信する
     */
    private void sendErrorResponse(HttpExchange exchange, int statusCode, String error) throws IOException {
        JsonObject responseJson = new JsonObject();
        responseJson.addProperty("success", false);
        responseJson.addProperty("error", error);
        sendResponse(exchange, statusCode, responseJson.toString());
    }

    /**
     * HTTPレスポンスを送信する
     */
    private void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] responseBytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
}
