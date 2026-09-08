package me.vertex.core.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Reads {@code number-formatting.yml} into {@link Numbers}. Every value falls
 * back to its default with a console warning rather than throwing -- a typo
 * in this file must not take the economy down with it.
 */
public final class NumberFormatConfig {

    private NumberFormatConfig() {
    }

    public static void load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "number-formatting.yml");
        if (!file.exists()) {
            plugin.saveResource("number-formatting.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        NumberSettings defaults = NumberSettings.DEFAULTS;

        Numbers.configure(new NumberSettings(
                readSuffixes(plugin, config, defaults.suffixes()),
                config.getString("currency-symbol", defaults.currencySymbol()),
                Math.max(0, Math.min(8, config.getInt("max-decimals", defaults.maxDecimals()))),
                readRounding(plugin, config.getString("rounding-mode"), defaults.rounding()),
                config.getBoolean("accept-commas", defaults.acceptCommas()),
                readPositive(plugin, config, "abbreviation-threshold", defaults.abbreviationThreshold()),
                readPositive(plugin, config, "max-value", defaults.maxValue())));
    }

    private static List<NumberSettings.Suffix> readSuffixes(Plugin plugin, YamlConfiguration config,
            List<NumberSettings.Suffix> fallback) {
        List<?> raw = config.getList("suffixes");
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        List<NumberSettings.Suffix> suffixes = new ArrayList<>();
        for (Object entry : raw) {
            String label = null;
            Object multiplier = null;
            if (entry instanceof ConfigurationSection section) {
                label = section.getString("label");
                multiplier = section.get("multiplier");
            } else if (entry instanceof Map<?, ?> map) {
                label = map.get("label") == null ? null : String.valueOf(map.get("label"));
                multiplier = map.get("multiplier");
            }
            if (label == null || label.isBlank() || !(multiplier instanceof Number number)) {
                plugin.getLogger().warning("number-formatting.yml: skipping malformed suffix entry " + entry);
                continue;
            }
            BigDecimal value = new BigDecimal(number.toString());
            if (value.signum() <= 0) {
                plugin.getLogger().warning("number-formatting.yml: suffix '" + label + "' needs a positive multiplier.");
                continue;
            }
            suffixes.add(new NumberSettings.Suffix(label.trim().toUpperCase(Locale.ROOT), value));
        }
        if (suffixes.isEmpty()) {
            plugin.getLogger().warning("number-formatting.yml: no usable suffixes, falling back to defaults.");
            return fallback;
        }
        suffixes.sort((a, b) -> a.multiplier().compareTo(b.multiplier()));
        return List.copyOf(suffixes);
    }

    private static RoundingMode readRounding(Plugin plugin, String name, RoundingMode fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return RoundingMode.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING,
                    "number-formatting.yml: unknown rounding-mode '" + name + "', using " + fallback + ".");
            return fallback;
        }
    }

    private static BigDecimal readPositive(Plugin plugin, YamlConfiguration config, String key, BigDecimal fallback) {
        Object raw = config.get(key);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number number)) {
            plugin.getLogger().warning("number-formatting.yml: " + key + " must be a number, using " + fallback + ".");
            return fallback;
        }
        BigDecimal value = new BigDecimal(number.toString());
        if (value.signum() <= 0) {
            plugin.getLogger().warning("number-formatting.yml: " + key + " must be positive, using " + fallback + ".");
            return fallback;
        }
        return value;
    }
}
