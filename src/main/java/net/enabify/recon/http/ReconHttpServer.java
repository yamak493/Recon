package net.enabify.recon.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.enabify.recon.crypto.AESCrypto;
import net.enabify.recon.execution.ExecutionResult;
import net.enabify.recon.model.ReconUser;
import net.enabify.recon.platform.ReconPlatform;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * REST APIを提供するHTTPサーバー
 * JDK内蔵のcom.sun.net.httpserver.HttpServerを使用してルート直下を受け付ける
 */
public class ReconHttpServer {

    /** リクエストボディの最大サイズ（バイト）。巨大POSTによるメモリ枯渇DoSを防止する */
    private static final int MAX_BODY_BYTES = 256 * 1024;

    /** 転送ループ防止用ヘッダー。このヘッダーが付いたリクエストは再転送しない */
    private static final String FORWARDED_HEADER = "X-Recon-Forwarded";

    private final ReconPlatform plugin;
    private final HttpClient forwardingHttpClient;
    private HttpServer server;
    private ExecutorService httpExecutor;

    public ReconHttpServer(ReconPlatform plugin) {
        this.plugin = plugin;
        this.forwardingHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * HTTPサーバーを起動する
     */
    public void start() throws IOException {
        int port = plugin.getConfigManager().getPort();
        String bindAddress = plugin.getConfigManager().getBindAddress();

        InetSocketAddress address;
        if (bindAddress == null || bindAddress.trim().isEmpty()) {
            address = new InetSocketAddress(port);
        } else {
            address = new InetSocketAddress(bindAddress.trim(), port);
        }

        server = HttpServer.create(address, 0);
        server.createContext("/", new ApiHandler());

        // 境界付きスレッドプールを使用してリクエストを並行処理する。
        // setExecutor(null) の既定動作はディスパッチスレッド上での直列実行となり、
        // 1リクエストの最大10秒ブロックが他の全リクエストを停止させてしまうため避ける。
        this.httpExecutor = createHttpExecutor();
        server.setExecutor(httpExecutor);
        server.start();
        plugin.getPluginLogger().info("Recon HTTP server started on "
                + (bindAddress == null || bindAddress.trim().isEmpty() ? "*" : bindAddress.trim())
                + ":" + port);
    }

    /**
     * リクエスト処理用の境界付きスレッドプールを生成する
     */
    private ExecutorService createHttpExecutor() {
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        int maxThreads = Math.min(64, cores * 4);
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "Recon-HTTP-" + counter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
        return Executors.newFixedThreadPool(maxThreads, factory);
    }

    /**
     * HTTPサーバーを停止する
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            plugin.getPluginLogger().info("Recon HTTP server stopped.");
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
        }
        // 転送用HTTPクライアントのスレッドを解放する
        try {
            forwardingHttpClient.close();
        } catch (Throwable ignored) {
            // close() 非対応ランタイムやクローズ失敗は致命的ではない
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

                // リクエストボディを読み取り（サイズ上限あり）
                String body;
                try {
                    body = readRequestBody(exchange);
                } catch (RequestTooLargeException tooLarge) {
                    sendErrorResponse(exchange, 413, plugin.getLangManager().get("http.body_too_large"));
                    return;
                }

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

                // 構造的に正当なリクエストのみを転送先へ非同期一斉転送（レスポンスは待たない）。
                // 転送によるループを防ぐため、転送由来のリクエストは再転送しない。
                boolean alreadyForwarded = exchange.getRequestHeaders().containsKey(FORWARDED_HEADER);
                if (!alreadyForwarded) {
                    forwardRequestAsync(body);
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
                ExecutionResult result;
                try {
                    result = plugin.getCommandExecutionService().executeCommand(reconUser, command, queueEnabled);
                } catch (Exception e) {
                    plugin.getPluginLogger().severe("Error executing command: " + e.getMessage());
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
                plugin.getPluginLogger().severe("Unexpected error in HTTP handler: " + e.getMessage());
                try {
                    sendErrorResponse(exchange, 500, plugin.getLangManager().get("http.unexpected_error"));
                } catch (IOException ignored) {
                    // レスポンス送信が既に失敗している場合
                }
            }
        }
    }

    /**
     * 設定された転送先へリクエストを非同期一斉転送する
     * 転送先のレスポンスは待たず、失敗時はログのみ出力する
     */
    private void forwardRequestAsync(String body) {
        List<String> targets = plugin.getConfigManager().getRequestForwardingTargets();
        if (targets == null || targets.isEmpty()) {
            return;
        }

        for (String target : targets) {
            URI uri;
            try {
                uri = buildForwardingUri(target);
            } catch (Exception e) {
                plugin.getPluginLogger().warning("Invalid request-forwarding target: " + target);
                continue;
            }

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header(FORWARDED_HEADER, "true")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            forwardingHttpClient
                    .sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        plugin.getPluginLogger().warning("Request forwarding failed for target " + target + ": " + ex.getMessage());
                        return null;
                    });
        }
    }

    /**
     * request-forwarding設定から転送先URIを組み立てる
     */
    private URI buildForwardingUri(String target) {
        String trimmed = target.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            if (trimmed.endsWith("/")) {
                return URI.create(trimmed);
            }
            return URI.create(trimmed + "/");
        }
        return URI.create("http://" + trimmed + "/");
    }

    /**
     * リクエストボディを文字列として読み取る（最大サイズを超えた場合は例外を投げる）
     */
    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody();
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int read;
            int total = 0;
            while ((read = is.read(chunk)) != -1) {
                total += read;
                if (total > MAX_BODY_BYTES) {
                    throw new RequestTooLargeException();
                }
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * リクエストボディがサイズ上限を超えたことを示す内部例外
     */
    private static class RequestTooLargeException extends IOException {
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
