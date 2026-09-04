package me.hcfcore.core.lang;

import me.hcfcore.core.user.User;
import me.hcfcore.core.user.UserManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every player-facing message lives in a lang/*.yml file, keyed by locale
 * code (e.g. "en_us"), so different players can see the plugin in
 * different languages. A player picks their own via /language; anyone who
 * hasn't gets the server's configured default. Missing keys fall back to
 * the default locale's file, then to a hardcoded diagnostic string -- that
 * one string is the deliberate exception to "no hardcoded messages", since
 * it's describing a broken translation, not plugin content.
 */
public final class Messages {

    private static final String[] BUNDLED_LOCALES = {"en_us", "es_us", "pt_br", "de_de"};

    private final Plugin plugin;
    private final UserManager userManager;
    private final Map<String, YamlConfiguration> locales = new HashMap<>();
    private String defaultLocale = "en_us";
    private Set<String> cachedAvailableLocales = new TreeSet<>();

    public Messages(Plugin plugin, UserManager userManager) {
        this.plugin = plugin;
        this.userManager = userManager;
    }

    public void load() {
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        for (String locale : BUNDLED_LOCALES) {
            if (!new File(langFolder, locale + ".yml").exists()) {
                try {
                    plugin.saveResource("lang/" + locale + ".yml", false);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Bundled locale resource lang/" + locale + ".yml is missing, skipping.");
                }
            }
        }

        locales.clear();
        File[] files = langFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                String locale = name.substring(0, name.length() - ".yml".length()).toLowerCase(Locale.ROOT);
                locales.put(locale, YamlConfiguration.loadConfiguration(file));
            }
        }

        defaultLocale = plugin.getConfig().getString("language.default", "en_us").toLowerCase(Locale.ROOT);
        cachedAvailableLocales = new TreeSet<>(locales.keySet());
    }

    /**
     * Every locale actually present in lang/, not a hardcoded list -- an
     * admin can add a fifth by dropping in another translated file, and
     * it becomes selectable with no code change.
     */
    public Set<String> getAvailableLocales() {
        return cachedAvailableLocales;
    }

    public boolean isAvailable(String locale) {
        return locales.containsKey(locale.toLowerCase(Locale.ROOT));
    }

    public String getDefaultLocale() {
        return defaultLocale;
    }

    /**
     * Resolves `key` for whichever locale `sender` should see (their own
     * preference if they're a player and it's still a loaded locale, else
     * the server default), substitutes `{name}` placeholders from the
     * flat "name", value, "name2", value2... pairs, and deserializes the
     * result as a legacy-color-coded Component.
     */
    public Component get(CommandSender sender, String key, String... placeholders) {
        return MessageFormatter.deserialize(getRaw(sender, key, placeholders));
    }

    /** @deprecated identical to {@link #get}; kept only because many call sites still use it. */
    @Deprecated
    public Component getChat(CommandSender sender, String key, String... placeholders) {
        return get(sender, key, placeholders);
    }

    /**
     * Same resolution as get(), but returns the raw color-coded string
     * instead of a deserialized Component -- for callers (like a
     * scoreboard line template) that need to substitute it into a larger
     * string rather than send it directly.
     */
    public String getRaw(CommandSender sender, String key, String... placeholders) {
        String template = resolveTemplate(sender, key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            // Values (unlike the admin-authored template) may come from
            // untrusted sources such as a player's name, so any MiniMessage
            // tags inside them must render as literal text, not formatting.
            String safeValue = MiniMessage.miniMessage().escapeTags(placeholders[i + 1]);
            template = template.replace("{" + placeholders[i] + "}", safeValue);
        }
        return template;
    }

    private String resolveTemplate(CommandSender sender, String key) {
        String locale = defaultLocale;
        if (sender instanceof Player player) {
            User user = userManager.get(player.getUniqueId());
            String preferred = user == null ? null : user.getLocale();
            if (preferred != null && isAvailable(preferred)) {
                locale = preferred.toLowerCase(Locale.ROOT);
            }
        }

        String value = getFromLocale(locale, key);
        if (value == null && !locale.equals(defaultLocale)) {
            value = getFromLocale(defaultLocale, key);
        }
        return value != null ? value : "&cMissing translation: " + key;
    }

    private String getFromLocale(String locale, String key) {
        YamlConfiguration config = locales.get(locale);
        return config == null ? null : config.getString(key);
    }
}
