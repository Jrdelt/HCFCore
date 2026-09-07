package me.vertex.core.capture;

import me.vertex.core.factions.FactionsHook;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/** Persistent, multiplicatively-stacked Outpost XP boosters in the winning faction's own claims. */
final class FactionXpBoosterManager implements Listener {
    private record Booster(double multiplier, long expiresAt) {
    }

    private final Plugin plugin;
    private final File file;
    private final Map<Integer, List<Booster>> boosters = new HashMap<>();

    FactionXpBoosterManager(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "outpost-boosters.yml");
    }

    void load() {
        boosters.clear();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        long now = System.currentTimeMillis();
        for (String key : config.getKeys(false)) {
            try {
                int factionId = Integer.parseInt(key);
                ConfigurationSection boosts = config.getConfigurationSection(key + ".boosts");
                if (boosts == null) {
                    // Migrate the previous one-row schema without discarding a live reward during an update.
                    addIfActive(factionId, config.getDouble(key + ".multiplier", 1D),
                            config.getLong(key + ".expires-at"), now);
                    continue;
                }
                for (String boostId : boosts.getKeys(false)) {
                    addIfActive(factionId, boosts.getDouble(boostId + ".multiplier", 1D),
                            boosts.getLong(boostId + ".expires-at"), now);
                }
            } catch (NumberFormatException ignored) {
                // A manually malformed row must not prevent valid boosters from loading.
            }
        }
        save(); // discard expired rows on the next normal file write
    }

    void grant(int factionId, double multiplier, long durationSeconds) {
        if (factionId == FactionsHook.NO_FACTION || multiplier <= 1D || durationSeconds <= 0L) {
            return;
        }
        long expiresAt = System.currentTimeMillis() + durationSeconds * 1000L;
        boosters.computeIfAbsent(factionId, ignored -> new ArrayList<>()).add(new Booster(multiplier, expiresAt));
        save();
    }

    void revoke(int factionId) {
        if (boosters.remove(factionId) != null) {
            save();
        }
    }

    void cleanupExpired() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (var iterator = boosters.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<Integer, List<Booster>> entry = iterator.next();
            changed |= entry.getValue().removeIf(boost -> boost.expiresAt() <= now);
            if (entry.getValue().isEmpty()) {
                iterator.remove();
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExperience(PlayerExpChangeEvent event) {
        if (event.getAmount() <= 0) {
            return;
        }
        Player player = event.getPlayer();
        int factionId = FactionsHook.getFactionId(player);
        List<Booster> factionBoosters = boosters.get(factionId);
        if (factionBoosters == null || FactionsHook.getClaimFactionId(player.getLocation()) != factionId) {
            return;
        }
        long now = System.currentTimeMillis();
        double multiplier = 1D;
        for (Booster booster : factionBoosters) {
            if (booster.expiresAt() > now) {
                multiplier *= booster.multiplier();
            }
        }
        if (multiplier <= 1D) {
            return;
        }
        double boosted = event.getAmount() * multiplier;
        event.setAmount((int) Math.min(Integer.MAX_VALUE, Math.round(boosted)));
    }

    void shutdown() {
        cleanupExpired();
        save();
    }

    private void save() {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<Integer, List<Booster>> entry : boosters.entrySet()) {
            int index = 1;
            for (Booster booster : entry.getValue()) {
                String path = entry.getKey() + ".boosts." + index++;
                config.set(path + ".multiplier", booster.multiplier());
                config.set(path + ".expires-at", booster.expiresAt());
            }
        }
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save Outpost XP boosters.", e);
        }
    }

    private void addIfActive(int factionId, double multiplier, long expiresAt, long now) {
        multiplier = Math.max(1D, multiplier);
        if (expiresAt > now && multiplier > 1D) {
            boosters.computeIfAbsent(factionId, ignored -> new ArrayList<>()).add(new Booster(multiplier, expiresAt));
        }
    }
}
