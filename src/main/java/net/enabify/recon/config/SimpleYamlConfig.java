package net.enabify.recon.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * プラットフォーム非依存のYAML設定ファイルラッパー
 * Bukkit/BungeeCord/Velocityのいずれでも動作する
 * SnakeYAMLを直接使用してYAMLファイルの読み書きを行う
 */
public class SimpleYamlConfig {

    private Map<String, Object> data;
    private final Yaml yaml;

    public SimpleYamlConfig() {
        this.data = new LinkedHashMap<>();
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        this.yaml = new Yaml(options);
    }

    /**
     * ファイルからYAML設定を読み込む
     */
    @SuppressWarnings("unchecked")
    public static SimpleYamlConfig load(File file) {
        SimpleYamlConfig config = new SimpleYamlConfig();
        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file);
                 InputStreamReader reader = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
                Object loaded = config.yaml.load(reader);
                if (loaded instanceof Map) {
                    config.data = (Map<String, Object>) loaded;
                }
            } catch (Exception e) {
                // 読み込み失敗時は空の設定を返す
            }
        }
        return config;
    }

    /**
     * ファイルにYAML設定を書き出す
     */
    public void save(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            yaml.dump(data, writer);
        }
    }

    /**
     * ドット区切りのパスで値を取得
     */
    @SuppressWarnings("unchecked")
    private Object getByPath(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        // まず完全一致キーを優先して取得する
        // lang/en.yml のように "usage.header" 形式のフラットキーを扱うため
        if (data.containsKey(path)) {
            return data.get(path);
        }

        String[] parts = path.split("\\.");
        Object current = data;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * 文字列を取得（デフォルト値付き）
     */
    public String getString(String path, String def) {
        Object val = getByPath(path);
        return val != null ? val.toString() : def;
    }

    /**
     * 文字列を取得（null可能）
     */
    public String getString(String path) {
        return getString(path, null);
    }

    /**
     * 整数を取得（デフォルト値付き）
     */
    public int getInt(String path, int def) {
        Object val = getByPath(path);
        if (val instanceof Number) return ((Number) val).intValue();
        return def;
    }

    /**
     * 真偽値を取得（デフォルト値付き）
     */
    public boolean getBoolean(String path, boolean def) {
        Object val = getByPath(path);
        if (val instanceof Boolean) return (Boolean) val;
        return def;
    }

    /**
     * 文字列リストを取得
     */
    @SuppressWarnings("unchecked")
    public List<String> getStringList(String path) {
        Object val = getByPath(path);
        if (val instanceof List) {
            List<String> result = new ArrayList<>();
            for (Object item : (List<?>) val) {
                if (item != null) result.add(item.toString());
            }
            return result;
        }
        return new ArrayList<>();
    }

    /**
     * パスが存在するか確認
     */
    public boolean contains(String path) {
        return getByPath(path) != null;
    }

    /**
     * トップレベルのキー一覧を取得
     */
    public Set<String> getKeys() {
        return data.keySet();
    }

    /**
     * Mapのリストを取得
     */
    public List<Map<?, ?>> getMapList(String path) {
        Object val = getByPath(path);
        if (val instanceof List) {
            List<Map<?, ?>> result = new ArrayList<>();
            for (Object item : (List<?>) val) {
                if (item instanceof Map) {
                    result.add((Map<?, ?>) item);
                }
            }
            return result;
        }
        return new ArrayList<>();
    }

    /**
     * ドット区切りのパスに値を設定
     */
    @SuppressWarnings("unchecked")
    public void set(String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = data;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map)) {
                Map<String, Object> newMap = new LinkedHashMap<>();
                current.put(parts[i], newMap);
                next = newMap;
            }
            current = (Map<String, Object>) next;
        }
        if (value == null) {
            current.remove(parts[parts.length - 1]);
        } else {
            current.put(parts[parts.length - 1], value);
        }
    }

    /**
     * 指定セクションのMapを取得
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getSection(String path) {
        Object val = getByPath(path);
        if (val instanceof Map) {
            return (Map<String, Object>) val;
        }
        return null;
    }
}
