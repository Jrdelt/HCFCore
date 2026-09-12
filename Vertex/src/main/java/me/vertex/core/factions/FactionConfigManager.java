package me.vertex.core.factions;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Loads the single faction-owned configuration file and imports legacy files once. */
public final class FactionConfigManager {
    private static final List<String> ROOTS = List.of(
            "factions", "faction-upgrades", "faction-vault",
            "grace", "shield", "base-claim", "raid-claim");

    private FactionConfigManager() {
    }

    public static File file(Plugin plugin) {
        return new File(plugin.getDataFolder(), "factions.yml");
    }

    public static void loadAndApply(JavaPlugin plugin) {
        File destination = file(plugin);
        boolean created = !destination.exists();
        if (created) {
            plugin.saveResource("factions.yml", false);
        }

        YamlConfiguration factions = YamlConfiguration.loadConfiguration(destination);
        boolean changed = false;
        changed |= importRoots(factions, new File(plugin.getDataFolder(), "claims.yml"),
                List.of("base-claim", "raid-claim"), created);
        changed |= importRoots(factions, new File(plugin.getDataFolder(), "shield.yml"),
                List.of("grace", "shield"), created);
        for (String root : List.of("factions", "faction-upgrades", "faction-vault")) {
            if ((created || !factions.contains(root)) && plugin.getConfig().contains(root)) {
                copyRoot(factions, plugin.getConfig(), root);
                changed = true;
            }
        }
        changed |= migrateShieldDurationDefaults(factions);
        List<String> aliases = factions.getStringList("factions.command-aliases");
        List<String> supportedAliases = aliases.stream().filter(alias -> !alias.trim().equalsIgnoreCase("t")).toList();
        if (!supportedAliases.equals(aliases)) {
            factions.set("factions.command-aliases", supportedAliases);
            changed = true;
        }

        if (changed) {
            try {
                factions.save(destination);
            } catch (IOException error) {
                plugin.getLogger().warning("Could not save migrated factions.yml: " + error.getMessage());
            }
        }

        for (String root : ROOTS) {
            if (factions.contains(root)) {
                copyRoot(plugin.getConfig(), factions, root);
            }
        }
    }

    private static boolean importRoots(YamlConfiguration destination, File legacyFile,
            List<String> roots, boolean force) {
        if (!legacyFile.isFile()) {
            return false;
        }
        YamlConfiguration legacy = YamlConfiguration.loadConfiguration(legacyFile);
        boolean changed = false;
        for (String root : roots) {
            if ((force || !destination.contains(root)) && legacy.contains(root)) {
                copyRoot(destination, legacy, root);
                changed = true;
            }
        }
        return changed;
    }

    private static void copyRoot(ConfigurationSection destination, ConfigurationSection source, String root) {
        if (!source.contains(root)) {
            return;
        }
        destination.set(root, null);
        ConfigurationSection section = source.getConfigurationSection(root);
        if (section == null) {
            destination.set(root, source.get(root));
            return;
        }
        copySection(destination, section, root);
    }

    private static void copySection(ConfigurationSection destination, ConfigurationSection source, String path) {
        for (String key : source.getKeys(false)) {
            String childPath = path + "." + key;
            ConfigurationSection nested = source.getConfigurationSection(key);
            if (nested == null) {
                destination.set(childPath, source.get(key));
            } else {
                copySection(destination, nested, childPath);
            }
        }
    }

    /**
     * Upgrade only Vertex's old shipped Shield defaults. Deliberately leave
     * operator-custom values alone: the new 8–12 hour range remains fully
     * configurable, while existing servers on the previous defaults receive
     * the requested behavior without manual YAML surgery.
     */
    private static boolean migrateShieldDurationDefaults(YamlConfiguration factions) {
        long base = factions.getLong("shield.base-duration-seconds", Long.MIN_VALUE);
        long maximum = factions.getLong("shield.maximum-duration-seconds", Long.MIN_VALUE);
        boolean changed = false;
        if (base == 21_600L && maximum == 86_400L) {
            factions.set("shield.base-duration-seconds", 28_800L);
            factions.set("shield.maximum-duration-seconds", 43_200L);
            changed = true;
        }
        ConfigurationSection levels = factions.getConfigurationSection(
                "faction-upgrades.upgrades.shield-duration.levels");
        if (levels != null && levels.contains("5")) {
            double oldPrice = levels.getDouble("5.price", Double.NaN);
            double oldBonus = levels.getDouble("5.bonus", Double.NaN);
            // Remove only the old generated fifth tier. A customized extra
            // tier is retained in YAML for auditability but ignored by the
            // enforced four-level runtime definition.
            if (oldPrice == 300_000_000D && oldBonus == 21_600D) {
                levels.set("5", null);
                changed = true;
            }
        }
        return changed;
    }
}
