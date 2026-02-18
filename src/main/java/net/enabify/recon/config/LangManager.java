package net.enabify.recon.config;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public class LangManager {

    private static final String DEFAULT_LANGUAGE = "en";
    private static final Set<String> SUPPORTED_LANGUAGES = new HashSet<>(Arrays.asList(
            "en", "hi", "zh", "es", "ar", "fr", "ru", "pt", "id", "de", "ja"));

    private final File dataFolder;
    private final ConfigManager configManager;
    private final Logger logger;
    private SimpleYamlConfig langConfig;
    private SimpleYamlConfig fallbackConfig;
    private String language;

    public LangManager(File dataFolder, ConfigManager configManager, Logger logger) {
        this.dataFolder = dataFolder;
        this.configManager = configManager;
        this.logger = logger;
        reload();
    }

    public void reload() {
        ensureLangFiles();
        String configured = configManager.getLanguage();
        if (configured == null || configured.trim().isEmpty()) {
            configured = DEFAULT_LANGUAGE;
        }
        configured = configured.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_LANGUAGES.contains(configured)) {
            configured = DEFAULT_LANGUAGE;
        }
        this.language = configured;
        this.langConfig = loadConfig(language);
        this.fallbackConfig = loadConfig(DEFAULT_LANGUAGE);
    }

    public String getLanguage() {
        return language;
    }

    public String get(String key) {
        String value = langConfig.getString(key);
        if (value == null) {
            value = fallbackConfig.getString(key);
        }
        if (value == null) {
            return key;
        }
        return translateColorCodes(value);
    }

    /**
     * &カラーコードを§に変換する（プラットフォーム非依存）
     */
    private static String translateColorCodes(String text) {
        if (text == null) return null;
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == '&' && "0123456789AaBbCcDdEeFfKkLlMmNnOoRr".indexOf(chars[i + 1]) > -1) {
                chars[i] = '\u00a7';
                chars[i + 1] = Character.toLowerCase(chars[i + 1]);
            }
        }
        return new String(chars);
    }

    public String format(String key, Map<String, String> placeholders) {
        String message = get(key);
        if (placeholders == null || placeholders.isEmpty()) {
            return message;
        }
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            message = message.replace(placeholder, value);
        }
        return message;
    }

    private void ensureLangFiles() {
        File langFolder = new File(dataFolder, "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }
        for (String code : SUPPORTED_LANGUAGES) {
            File file = new File(langFolder, code + ".yml");
            if (!file.exists()) {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("lang/" + code + ".yml")) {
                    if (in != null) {
                        Files.copy(in, file.toPath());
                    }
                } catch (Exception e) {
                    logger.warning("Failed to save language file: " + code + ".yml");
                }
            }
        }
    }

    private SimpleYamlConfig loadConfig(String code) {
        File file = new File(new File(dataFolder, "lang"), code + ".yml");
        return SimpleYamlConfig.load(file);
    }
}
