package me.vertex.core.lang;

import me.vertex.core.user.User;
import me.vertex.core.user.UserManager;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
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
    /** Bundled defaults fill keys added by a plugin update without overwriting server customizations. */
    private final Map<String, YamlConfiguration> bundledLocales = new HashMap<>();
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
            } else {
                refreshOutdatedLocale(langFolder, locale);
            }
        }

        locales.clear();
        bundledLocales.clear();
        for (String locale : BUNDLED_LOCALES) {
            try (InputStream resource = plugin.getResource("lang/" + locale + ".yml")) {
                if (resource != null) {
                    bundledLocales.put(locale, YamlConfiguration.loadConfiguration(
                            new InputStreamReader(resource, StandardCharsets.UTF_8)));
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Could not load bundled language fallback for " + locale + ".");
            }
        }
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
     * Replaces an on-disk locale whose {@code lang-version} is older than the
     * bundled one, keeping the admin's copy as a timestamped backup first.
     *
     * <p>Without this, a corrected default can never reach a server that
     * already has the file: {@code saveResource(.., false)} won't overwrite
     * it, and an existing on-disk key always wins over the bundled fallback.
     * A message fixed in a release would silently keep rendering its old
     * broken text forever. Refreshing wholesale (rather than merging
     * key-by-key) keeps this predictable, and nothing is lost because the
     * previous file is backed up next to it.
     */
    private void refreshOutdatedLocale(File langFolder, String locale) {
        File file = new File(langFolder, locale + ".yml");
        int bundledVersion;
        try (InputStream resource = plugin.getResource("lang/" + locale + ".yml")) {
            if (resource == null) {
                return;
            }
            bundledVersion = YamlConfiguration
                    .loadConfiguration(new InputStreamReader(resource, StandardCharsets.UTF_8))
                    .getInt("lang-version", 0);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read bundled lang/" + locale + ".yml to check its version.");
            return;
        }

        int diskVersion = YamlConfiguration.loadConfiguration(file).getInt("lang-version", 0);
        if (diskVersion >= bundledVersion) {
            return;
        }

        File backupFolder = new File(langFolder, "backup");
        if (!backupFolder.exists() && !backupFolder.mkdirs()) {
            plugin.getLogger().warning("Could not create lang/backup, leaving " + locale + ".yml at version "
                    + diskVersion + ". Its messages may be out of date.");
            return;
        }
        File backup = new File(backupFolder, locale + "-v" + diskVersion + "-"
                + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + ".yml");
        try {
            Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not back up lang/" + locale + ".yml, leaving it untouched.");
            return;
        }
        plugin.saveResource("lang/" + locale + ".yml", false);
        plugin.getLogger().info("Updated lang/" + locale + ".yml from version " + diskVersion + " to "
                + bundledVersion + ". Your previous file is at lang/backup/" + backup.getName()
                + " -- re-apply any customizations from it.");
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

    /**
     * Resolves a YAML list using the same per-player locale and fallback
     * rules as {@link #get(CommandSender, String, String...)}, making an
     * entire inventory lore block configurable rather than only individual
     * lines of it.
     */
    public List<Component> getList(CommandSender sender, String key, String... placeholders) {
        return resolveList(sender, key).stream()
                .map(line -> MessageFormatter.deserialize(applyPlaceholders(line, placeholders)))
                .toList();
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
        return applyPlaceholders(resolveTemplate(sender, key), placeholders);
    }

    private String applyPlaceholders(String template, String... placeholders) {
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            // Values (unlike the admin-authored template) may come from
            // untrusted sources such as a player's name, so any MiniMessage
            // tags (or legacy &-codes -- escapeTags() alone doesn't stop
            // those) inside them must render as literal text, not formatting.
            String safeValue = MessageFormatter.escapeForSubstitution(placeholders[i + 1]);
            String key = placeholders[i];
            template = template.replace("{" + key + "}", safeValue)
                    // Early Blueprint language files used angle-bracket
                    // placeholders. Continue accepting them so an existing
                    // customized locale never exposes raw <template> text.
                    .replace("<" + key + ">", safeValue);
        }
        return template;
    }

    private String resolveTemplate(CommandSender sender, String key) {
        String locale = localeFor(sender);

        String value = getFromLocale(locale, key);
        if (value == null && !locale.equals(defaultLocale)) {
            value = getFromLocale(defaultLocale, key);
        }
        return value != null ? value : "&cMissing translation: " + key;
    }

    private List<String> resolveList(CommandSender sender, String key) {
        String locale = localeFor(sender);
        List<String> value = getListFromLocale(locale, key);
        if (value == null && !locale.equals(defaultLocale)) {
            value = getListFromLocale(defaultLocale, key);
        }
        return value != null ? value : List.of("&cMissing translation: " + key);
    }

    private String localeFor(CommandSender sender) {
        String locale = defaultLocale;
        if (sender instanceof Player player) {
            User user = userManager.get(player.getUniqueId());
            String preferred = user == null ? null : user.getLocale();
            if (preferred != null && isAvailable(preferred)) {
                locale = preferred.toLowerCase(Locale.ROOT);
            }
        }
        return locale;
    }

    private String getFromLocale(String locale, String key) {
        YamlConfiguration config = locales.get(locale);
        String configured = config == null ? null : config.getString(key);
        if (configured != null) {
            return configured;
        }
        YamlConfiguration bundled = bundledLocales.get(locale);
        return bundled == null ? null : bundled.getString(key);
    }

    private List<String> getListFromLocale(String locale, String key) {
        YamlConfiguration config = locales.get(locale);
        if (config != null && config.isList(key)) {
            return config.getStringList(key);
        }
        YamlConfiguration bundled = bundledLocales.get(locale);
        return bundled != null && bundled.isList(key) ? bundled.getStringList(key) : null;
    }
}
