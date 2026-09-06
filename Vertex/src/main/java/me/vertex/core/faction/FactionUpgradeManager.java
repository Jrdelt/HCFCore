package me.vertex.core.faction;

import dev.kitteh.factions.Faction;
import dev.kitteh.factions.event.FactionDisbandEvent;
import dev.kitteh.factions.upgrade.Upgrades;
import me.vertex.core.economy.EconomyHook;
import me.vertex.core.factions.FactionsHook;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/**
 * Owns configurable upgrade definitions, live per-faction levels, and their
 * durable writes. Effects deliberately query this manager at use time, so a
 * /vertex reload immediately changes the configured bonus without a restart.
 */
public final class FactionUpgradeManager implements Listener {
    private static final int MAX_CONFIGURED_LEVEL = 100;
    private static final double MAX_CONFIGURED_COST = 1_000_000_000D;

    private final Plugin plugin;
    private final FactionUpgradeStorage storage;
    private final Map<Integer, EnumMap<FactionUpgrade, Integer>> levels = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<Void>> writeChains = new ConcurrentHashMap<>();
    private final java.util.Set<CompletableFuture<Void>> pendingWrites = ConcurrentHashMap.newKeySet();

    private volatile Map<FactionUpgrade, Definition> definitions = Map.of();
    private volatile boolean enabled;
    private volatile boolean leaderOnly;
    private volatile Runnable spawnerRetune = () -> { };

    public FactionUpgradeManager(Plugin plugin, FactionUpgradeStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public void load() {
        reloadConfig();
        try {
            for (FactionUpgradeStorage.StoredLevel stored : storage.loadAll()) {
                FactionUpgrade upgrade = FactionUpgrade.fromConfigKey(stored.upgradeKey());
                if (upgrade == null || stored.level() <= 0) {
                    continue;
                }
                levels.computeIfAbsent(stored.factionId(), ignored -> new EnumMap<>(FactionUpgrade.class))
                        .put(upgrade, stored.level());
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load faction upgrade levels from the database.", e);
        }
    }

    public void reloadConfig() {
        enabled = plugin.getConfig().getBoolean("faction-upgrades.enabled", true);
        leaderOnly = plugin.getConfig().getBoolean("faction-upgrades.leader-only", true);
        EnumMap<FactionUpgrade, Definition> loaded = new EnumMap<>(FactionUpgrade.class);
        for (FactionUpgrade upgrade : FactionUpgrade.values()) {
            String path = "faction-upgrades.upgrades." + upgrade.configKey();
            ConfigurationSection section = plugin.getConfig().getConfigurationSection(path);
            boolean upgradeEnabled = section == null || section.getBoolean("enabled", true);
            int maxLevel = Math.max(0, Math.min(MAX_CONFIGURED_LEVEL,
                    section == null ? 5 : section.getInt("max-level", 5)));
            double baseCost = clamp(section == null ? 0D : section.getDouble("cost-base", 0D), 0D,
                    MAX_CONFIGURED_COST);
            double multiplier = clamp(section == null ? 1D : section.getDouble("cost-multiplier", 1D), 1D, 100D);
            double bonus = clamp(section == null ? 0D : section.getDouble("bonus-per-level", 0D), 0D, 10_000D);
            loaded.put(upgrade, new Definition(upgradeEnabled, maxLevel, baseCost, multiplier, bonus));
        }
        definitions = Map.copyOf(loaded);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean leaderOnly() {
        return leaderOnly;
    }

    public Definition definition(FactionUpgrade upgrade) {
        return definitions.getOrDefault(upgrade, Definition.DISABLED);
    }

    /** Stored level, clamped to current configuration so lowering a max is safe. */
    public int level(int factionId, FactionUpgrade upgrade) {
        int stored = levels.getOrDefault(factionId, new EnumMap<>(FactionUpgrade.class))
                .getOrDefault(upgrade, 0);
        return Math.min(Math.max(0, stored), definition(upgrade).maxLevel());
    }

    public int level(Player player, FactionUpgrade upgrade) {
        return level(FactionsHook.getFactionId(player), upgrade);
    }

    /**
     * Servers may already have FactionsUUID warp levels from its own UI.
     * Adopt (never lower) that level before rendering Vertex's menu so a
     * first click cannot accidentally overwrite existing faction warps.
     */
    public void adoptNativeWarpLevel(Faction faction) {
        int nativeLevel = Math.max(0, faction.upgradeLevel(Upgrades.WARPS));
        EnumMap<FactionUpgrade, Integer> factionLevels =
                levels.computeIfAbsent(faction.id(), ignored -> new EnumMap<>(FactionUpgrade.class));
        int stored = factionLevels.getOrDefault(FactionUpgrade.WARPS, 0);
        if (nativeLevel > stored) {
            factionLevels.put(FactionUpgrade.WARPS, nativeLevel);
            queueSave(faction.id(), FactionUpgrade.WARPS, nativeLevel);
        }
    }

    public double bonus(int factionId, FactionUpgrade upgrade) {
        Definition definition = definition(upgrade);
        if (!enabled || !definition.enabled()) {
            return 0D;
        }
        return level(factionId, upgrade) * definition.bonusPerLevel();
    }

    public double bonusAtLevel(FactionUpgrade upgrade, int level) {
        return Math.max(0, level) * definition(upgrade).bonusPerLevel();
    }

    /** The amount charged for the next level, or -1 once maxed/disabled. */
    public double nextCost(int factionId, FactionUpgrade upgrade) {
        Definition definition = definition(upgrade);
        int current = level(factionId, upgrade);
        if (!enabled || !definition.enabled() || current >= definition.maxLevel()) {
            return -1D;
        }
        double cost = definition.costBase() * Math.pow(definition.costMultiplier(), current);
        return Double.isFinite(cost) ? Math.min(MAX_CONFIGURED_COST, cost) : MAX_CONFIGURED_COST;
    }

    public PurchaseResult purchase(Player player, Faction faction, FactionUpgrade upgrade) {
        if (!enabled || !definition(upgrade).enabled()) {
            return PurchaseResult.DISABLED;
        }
        if (leaderOnly && !FactionsHook.isLeader(player)) {
            return PurchaseResult.LEADER_ONLY;
        }
        int factionId = faction.id();
        int current = level(factionId, upgrade);
        if (current >= definition(upgrade).maxLevel()) {
            return PurchaseResult.MAXED;
        }
        double cost = nextCost(factionId, upgrade);
        if (cost > 0D) {
            if (!EconomyHook.isAvailable()) {
                return PurchaseResult.NO_ECONOMY;
            }
            Economy economy = EconomyHook.getEconomy();
            EconomyResponse response = economy.withdrawPlayer(player, cost);
            if (response == null || !response.transactionSuccess()) {
                return PurchaseResult.CANNOT_AFFORD;
            }
        }

        int newLevel = current + 1;
        levels.computeIfAbsent(factionId, ignored -> new EnumMap<>(FactionUpgrade.class)).put(upgrade, newLevel);
        if (upgrade == FactionUpgrade.WARPS) {
            // FactionsUUID owns its /f warp commands and persists this
            // native level itself; Vertex only supplies the purchase GUI.
            faction.upgradeLevel(Upgrades.WARPS, newLevel);
        }
        queueSave(factionId, upgrade, newLevel);
        if (upgrade == FactionUpgrade.SPAWNER_RATE) {
            spawnerRetune.run();
        }
        return PurchaseResult.SUCCESS;
    }

    /** True only while a member is standing in land their faction owns. */
    public boolean inOwnClaim(Player player, Location location) {
        return location != null && FactionsHook.getFactionId(player) != FactionsHook.NO_FACTION
                && FactionsHook.getFactionId(player) == FactionsHook.getClaimFactionId(location);
    }

    /** Multiplicative spawner rate at its physical location. */
    public double spawnerMultiplier(Location location) {
        int factionId = FactionsHook.getClaimFactionId(location);
        if (factionId == FactionsHook.NO_FACTION) {
            return 1D;
        }
        return 1D + bonus(factionId, FactionUpgrade.SPAWNER_RATE) / 100D;
    }

    public void setSpawnerRetune(Runnable spawnerRetune) {
        this.spawnerRetune = spawnerRetune == null ? () -> { } : spawnerRetune;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFactionDisband(FactionDisbandEvent event) {
        int factionId = event.getFaction().id();
        levels.remove(factionId);
        // Reuse the faction's write chain. A just-purchased level may still
        // be queued; deleting independently could race ahead of it and let
        // that delayed upsert resurrect rows for a disbanded faction.
        CompletableFuture<Void> write = writeChains.compute(factionId, (ignored, previous) ->
                (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous.handle((done, error) -> null))
                        .thenRunAsync(() -> {
                            try {
                                storage.deleteFaction(factionId);
                            } catch (Exception e) {
                                plugin.getLogger().log(Level.WARNING,
                                        "Failed to delete faction upgrades after disband.", e);
                            }
                        }));
        pendingWrites.add(write);
        write.whenComplete((ignored, error) -> {
            pendingWrites.remove(write);
            writeChains.remove(factionId, write);
        });
    }

    private void queueSave(int factionId, FactionUpgrade upgrade, int level) {
        CompletableFuture<Void> write = writeChains.compute(factionId, (ignored, previous) ->
                (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous.handle((done, error) -> null))
                        .thenRunAsync(() -> {
                            try {
                                storage.save(factionId, upgrade.configKey(), level);
                            } catch (Exception e) {
                                plugin.getLogger().log(Level.WARNING, "Failed to save faction upgrade to the database.", e);
                            }
                        }));
        pendingWrites.add(write);
        write.whenComplete((ignored, error) -> {
            pendingWrites.remove(write);
            writeChains.remove(factionId, write);
        });
    }

    public void awaitWrites() {
        try {
            CompletableFuture.allOf(pendingWrites.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            plugin.getLogger().warning("Timed out waiting for faction-upgrade writes during shutdown.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed while waiting for faction-upgrade writes.", e);
        }
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Double.isFinite(value) ? Math.max(minimum, Math.min(maximum, value)) : minimum;
    }

    public enum PurchaseResult { SUCCESS, DISABLED, LEADER_ONLY, MAXED, NO_ECONOMY, CANNOT_AFFORD }

    public record Definition(boolean enabled, int maxLevel, double costBase, double costMultiplier,
                             double bonusPerLevel) {
        private static final Definition DISABLED = new Definition(false, 0, 0, 1, 0);
    }
}
