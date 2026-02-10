package net.enabify.recon.execution;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * プレイヤーへ送信されるチャットメッセージをNettyパイプラインでキャプチャする
 * Bukkit.dispatchCommand()の結果としてプレイヤーに送られるメッセージを取得するために使用
 *
 * CraftPlayer → EntityPlayer → PlayerConnection → NetworkManager → Channel
 * の順にリフレクションでNettyチャンネルを取得し、ChannelDuplexHandlerを注入して
 * 送信されるチャットパケットからテキストを抽出する
 *
 * 対応バージョン: 1.13以降のSpigot/Paper/Folia
 */
public class PlayerMessageInterceptor {

    // CraftChatMessage.fromComponent() メソッドのキャッシュ
    private static volatile boolean reflectionInitialized = false;
    private static Method fromComponentMethod;

    private final Player player;
    private final List<String> capturedMessages = Collections.synchronizedList(new ArrayList<String>());
    private final String handlerName;
    private Channel channel;
    private final AtomicBoolean active = new AtomicBoolean(false);

    public PlayerMessageInterceptor(Player player) {
        this.player = player;
        this.handlerName = "recon_intercept_" + System.nanoTime();
    }

    /**
     * CraftChatMessage.fromComponent() メソッドをリフレクションで取得（遅延初期化）
     */
    private static void ensureReflectionInitialized() {
        if (!reflectionInitialized) {
            synchronized (PlayerMessageInterceptor.class) {
                if (!reflectionInitialized) {
                    try {
                        String serverClassName = Bukkit.getServer().getClass().getName();
                        String craftPackage = serverClassName.substring(0, serverClassName.lastIndexOf('.'));
                        Class<?> craftChatMessageClass = Class.forName(craftPackage + ".util.CraftChatMessage");
                        for (Method m : craftChatMessageClass.getDeclaredMethods()) {
                            if (m.getName().equals("fromComponent")
                                    && m.getParameterCount() == 1
                                    && m.getReturnType() == String.class) {
                                m.setAccessible(true);
                                fromComponentMethod = m;
                                break;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    reflectionInitialized = true;
                }
            }
        }
    }

    /**
     * プレイヤーのNettyパイプラインにインターセプターを注入する
     *
     * @return 注入に成功した場合 true
     */
    public boolean inject() {
        try {
            channel = getPlayerChannel(player);
            if (channel == null) return false;

            channel.pipeline().addBefore("packet_handler", handlerName, new ChannelDuplexHandler() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    if (active.get()) {
                        try {
                            String text = extractChatMessage(msg);
                            if (text != null && !text.isEmpty()) {
                                capturedMessages.add(text);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    super.write(ctx, msg, promise);
                }
            });

            active.set(true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * インターセプターを削除する
     */
    public void remove() {
        active.set(false);
        if (channel != null) {
            try {
                if (channel.pipeline().get(handlerName) != null) {
                    channel.pipeline().remove(handlerName);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * キャプチャされたメッセージのリストを返す
     */
    public List<String> getCapturedMessages() {
        return new ArrayList<String>(capturedMessages);
    }

    /**
     * キャプチャされたメッセージを結合して返す（装飾コード付き）
     */
    public String getOutput() {
        return String.join("\n", capturedMessages);
    }

    /**
     * キャプチャされたメッセージを結合して返す（装飾コード無し）
     */
    public String getPlainOutput() {
        return String.join("\n", capturedMessages).replaceAll("\u00a7[0-9a-fk-or]", "");
    }

    // ==========================================
    // Reflection: プレイヤーのNettyチャンネル取得
    // ==========================================

    /**
     * CraftPlayer → EntityPlayer → PlayerConnection → NetworkManager → Channel
     * の順にリフレクションでNettyチャンネルを取得する
     */
    private static Channel getPlayerChannel(Player player) {
        try {
            // CraftPlayer.getHandle() → ServerPlayer/EntityPlayer
            Object entityPlayer = player.getClass().getMethod("getHandle").invoke(player);

            // EntityPlayer → PlayerConnection/ServerGamePacketListenerImpl
            Object connection = findFieldByTypePattern(entityPlayer,
                    "PlayerConnection", "ServerGamePacketListenerImpl",
                    "ServerCommonPacketListenerImpl");
            if (connection == null) return null;

            // PlayerConnection → NetworkManager/Connection
            Object networkManager = findNetworkManager(connection);
            if (networkManager == null) return null;

            // NetworkManager → Channel
            return findChannelField(networkManager);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * オブジェクトのフィールドから、型名に指定パターンを含むフィールドを探す
     */
    private static Object findFieldByTypePattern(Object obj, String... patterns) {
        Class<?> clazz = obj.getClass();
        while (clazz != null && !clazz.equals(Object.class)) {
            for (Field f : clazz.getDeclaredFields()) {
                String typeName = f.getType().getName();
                for (String pattern : patterns) {
                    if (typeName.contains(pattern)) {
                        try {
                            f.setAccessible(true);
                            Object val = f.get(obj);
                            if (val != null) return val;
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /**
     * PlayerConnectionからNetworkManagerを探す
     * NetworkManagerはChannelフィールドを持つオブジェクト
     */
    private static Object findNetworkManager(Object connection) {
        Class<?> clazz = connection.getClass();
        while (clazz != null && !clazz.equals(Object.class)) {
            for (Field f : clazz.getDeclaredFields()) {
                try {
                    String typeName = f.getType().getName();
                    if (typeName.contains("NetworkManager") || typeName.contains("Connection")) {
                        f.setAccessible(true);
                        Object val = f.get(connection);
                        if (val != null && findChannelField(val) != null) {
                            return val;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /**
     * オブジェクトからio.netty.channel.Channelフィールドを探す
     */
    private static Channel findChannelField(Object obj) {
        Class<?> clazz = obj.getClass();
        while (clazz != null && !clazz.equals(Object.class)) {
            for (Field f : clazz.getDeclaredFields()) {
                if (Channel.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        return (Channel) f.get(obj);
                    } catch (Exception ignored) {
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    // ==========================================
    // パケットからチャットメッセージのテキスト抽出
    // ==========================================

    /**
     * 送信パケットからチャットメッセージのテキストを抽出する
     * バージョンに依存しないようリフレクションで各種パターンに対応
     *
     * 対応パケット:
     * - PacketPlayOutChat (1.13-1.18)
     * - ClientboundSystemChatPacket (1.19+)
     * - ClientboundPlayerChatPacket (1.19+)
     * - ClientboundDisguisedChatPacket (1.19.3+)
     */
    private static String extractChatMessage(Object packet) {
        String className = packet.getClass().getSimpleName();

        // チャット関連パケットのみ処理
        if (!className.contains("Chat") && !className.contains("Disguised")) {
            return null;
        }

        ensureReflectionInitialized();

        for (Field f : packet.getClass().getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object value = f.get(packet);
                if (value == null) continue;

                // プリミティブ型・列挙型・UUIDはスキップ
                if (value instanceof Boolean || value instanceof Number
                        || value instanceof Enum || value instanceof java.util.UUID) {
                    continue;
                }

                // Optional のアンラップ
                if (value instanceof Optional) {
                    Optional<?> opt = (Optional<?>) value;
                    if (!opt.isPresent()) continue;
                    value = opt.get();
                }

                // 文字列フィールド（JSON形式のチャットコンポーネント等）
                if (value instanceof String) {
                    String str = (String) value;
                    if (str.length() > 1) {
                        return str;
                    }
                    continue;
                }

                // コンポーネントオブジェクト → テキストに変換
                String text = componentToText(value);
                if (text != null && !text.isEmpty()) {
                    return text;
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    /**
     * IChatBaseComponent / Component オブジェクトからプレーンテキストを取得する
     * 複数のストラテジーを試行して最適な変換方法を使用する
     */
    private static String componentToText(Object component) {
        // Strategy 1: CraftChatMessage.fromComponent(component)
        // CraftBukkit/Spigot全バージョンで最も確実な変換方法
        if (fromComponentMethod != null) {
            try {
                Object result = fromComponentMethod.invoke(null, component);
                if (result instanceof String) {
                    // 装飾コード付きのまま返す
                    return (String) result;
                }
            } catch (Exception ignored) {
            }
        }

        // Strategy 2: getString() メソッド (1.16+)
        try {
            Method m = component.getClass().getMethod("getString");
            Object result = m.invoke(component);
            if (result instanceof String) {
                return (String) result;
            }
        } catch (Exception ignored) {
        }

        // Strategy 3: 代替メソッド名 (obfuscated versions)
        String[] altMethods = {"getText", "getContents", "text", "a"};
        for (String name : altMethods) {
            try {
                Method m = component.getClass().getDeclaredMethod(name);
                m.setAccessible(true);
                Object result = m.invoke(component);
                if (result instanceof String && !((String) result).isEmpty()) {
                    return (String) result;
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    /**
     * Minecraftカラーコード（§x）を除去する
     */
    private static String cleanColorCodes(String message) {
        if (message == null) return null;
        return message.replaceAll("\u00a7[0-9a-fk-or]", "");
    }
}
