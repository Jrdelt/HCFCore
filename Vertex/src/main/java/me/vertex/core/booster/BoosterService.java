package me.vertex.core.booster;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The single place Vertex answers "what bonus does this player have right
 * now, and why".
 *
 * <p>It deliberately owns no bonus of its own. Every figure is read back
 * from the system that already produces it, so turning a value on here can
 * never change gameplay and the tracker can never quote a number the server
 * does not actually apply. Adding a booster kind means adding a
 * {@link BoosterSource}, not editing this class.
 */
public final class BoosterService {

    private final Plugin plugin;
    private final List<BoosterSource> sources = new CopyOnWriteArrayList<>();
    private volatile Map<BoosterCategory, Rules> rules = defaultRules();

    /** @param cap percentage ceiling for the category, or {@link BoosterStacking#UNCAPPED} */
    public record Rules(BoosterStacking.Mode mode, double cap) {
    }

    public BoosterService(Plugin plugin) {
        this.plugin = plugin;
    }

    public void register(BoosterSource source) {
        sources.add(source);
    }

    public void reloadConfig() {
        File file = new File(plugin.getDataFolder(), "boosters.yml");
        if (!file.exists()) {
            plugin.saveResource("boosters.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        EnumMap<BoosterCategory, Rules> loaded = new EnumMap<>(BoosterCategory.class);
        for (BoosterCategory category : BoosterCategory.values()) {
            ConfigurationSection section = config.getConfigurationSection("categories." + category.configKey());
            Rules fallback = defaultRules().get(category);
            if (section == null) {
                loaded.put(category, fallback);
                continue;
            }
            loaded.put(category, new Rules(
                    readMode(section.getString("stacking"), fallback.mode(), category),
                    section.getDouble("max-percent", fallback.cap())));
        }
        rules = Map.copyOf(loaded);
    }

    public Rules rules(BoosterCategory category) {
        return rules.getOrDefault(category, new Rules(BoosterStacking.Mode.ADDITIVE, BoosterStacking.UNCAPPED));
    }

    /**
     * Every source's offer toward {@code category}, inactive ones included so
     * callers can show why something is not applying.
     */
    public List<BoosterContribution> contributions(Player player, BoosterCategory category) {
        List<BoosterContribution> all = new ArrayList<>();
        for (BoosterSource source : sources) {
            all.addAll(source.contribute(player, category));
        }
        return List.copyOf(all);
    }

    /** The combined bonus, plus the raw total and whether a cap bit into it. */
    public BoosterStacking.Result result(Player player, BoosterCategory category) {
        List<Double> active = contributions(player, category).stream()
                .filter(BoosterContribution::active)
                .map(BoosterContribution::percent)
                .toList();
        Rules categoryRules = rules(category);
        return BoosterStacking.combine(active, categoryRules.mode(), categoryRules.cap());
    }

    /** The percentage the server should actually apply, after stacking and any cap. */
    public double effectivePercent(Player player, BoosterCategory category) {
        return result(player, category).effective();
    }

    /** {@code 1 + effective/100}, for callers that scale an amount directly. */
    public double multiplier(Player player, BoosterCategory category) {
        return result(player, category).multiplier();
    }

    private BoosterStacking.Mode readMode(String raw, BoosterStacking.Mode fallback, BoosterCategory category) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return BoosterStacking.Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("boosters.yml: unknown stacking '" + raw + "' for "
                    + category.configKey() + ", using " + fallback + ".");
            return fallback;
        }
    }

    private static Map<BoosterCategory, Rules> defaultRules() {
        EnumMap<BoosterCategory, Rules> defaults = new EnumMap<>(BoosterCategory.class);
        for (BoosterCategory category : BoosterCategory.values()) {
            // Sell is the one category shipped with a ceiling: sell bonuses
            // multiply an already-dynamic price, so an uncapped stack is the
            // fastest way to wreck the economy.
            double cap = category == BoosterCategory.SELL ? 500D : BoosterStacking.UNCAPPED;
            defaults.put(category, new Rules(BoosterStacking.Mode.ADDITIVE, cap));
        }
        return defaults;
    }
}
