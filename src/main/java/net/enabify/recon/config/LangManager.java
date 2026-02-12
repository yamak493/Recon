package net.enabify.recon.config;

import net.enabify.recon.Recon;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class LangManager {

    private static final String DEFAULT_LANGUAGE = "en";
    private static final Set<String> SUPPORTED_LANGUAGES = new HashSet<>(Arrays.asList(
            "en", "hi", "zh", "es", "ar", "fr", "ru", "pt", "id", "de", "ja"));

    private final Recon plugin;
    private YamlConfiguration langConfig;
    private YamlConfiguration fallbackConfig;
    private String language;

    public LangManager(Recon plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        ensureLangFiles();
        String configured = plugin.getConfigManager().getLanguage();
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
        return ChatColor.translateAlternateColorCodes('&', value);
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
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }
        for (String code : SUPPORTED_LANGUAGES) {
            File file = new File(langFolder, code + ".yml");
            if (!file.exists()) {
                plugin.saveResource("lang/" + code + ".yml", false);
            }
        }
    }

    private YamlConfiguration loadConfig(String code) {
        File file = new File(new File(plugin.getDataFolder(), "lang"), code + ".yml");
        return YamlConfiguration.loadConfiguration(file);
    }
}
