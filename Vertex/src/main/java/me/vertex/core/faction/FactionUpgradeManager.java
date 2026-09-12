package me.vertex.core.faction;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.factions.FactionData;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.factions.event.FactionLifecycleEvent;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    /** Prevents two clicks from charging for levels before the first durable write finishes. */
    private final Set<String> pendingPurchases = ConcurrentHashMap.newKeySet();

    private volatile Map<FactionUpgrade, Definition> definitions = Map.of();
    private volatile boolean enabled;
    private volatile long tntBaseCapacity;
    private volatile Runnable spawnerRetune = () -> { };
    private volatile Runnable mutationPublisher = () -> { };

    public FactionUpgradeManager(Plugin plugin, FactionUpgradeStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public void load() {
        reloadConfig();
        try {
            Map<Integer, EnumMap<FactionUpgrade, Integer>> loaded = new java.util.HashMap<>();
            for (FactionUpgradeStorage.StoredLevel stored : storage.loadAll()) {
                FactionUpgrade upgrade = FactionUpgrade.fromConfigKey(stored.upgradeKey());
                if (upgrade == null || stored.level() <= 0) {
                    continue;
                }
                loaded.computeIfAbsent(stored.factionId(), ignored -> new EnumMap<>(FactionUpgrade.class))
                        .put(upgrade, stored.level());
            }
            levels.clear();
            levels.putAll(loaded);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load faction upgrade levels from the database.", e);
        }
    }

    public void reloadConfig() {
        enabled = plugin.getConfig().getBoolean("faction-upgrades.enabled", true);
        tntBaseCapacity = Math.max(0L, plugin.getConfig().getLong("faction-upgrades.tnt-base-capacity", 1_000_000L));
        EnumMap<FactionUpgrade, Definition> loaded = new EnumMap<>(FactionUpgrade.class);
        for (FactionUpgrade upgrade : FactionUpgrade.values()) {
            String path = "faction-upgrades.upgrades." + upgrade.configKey();
            ConfigurationSection section = plugin.getConfig().getConfigurationSection(path);
            boolean upgradeEnabled = section == null || section.getBoolean("enabled", true);
            loaded.put(upgrade, new Definition(upgradeEnabled,
                    section == null && upgrade == FactionUpgrade.SHIELD_DURATION
                            ? defaultShieldLevels() : loadLevels(upgrade, section)));
        }
        definitions = Map.copyOf(loaded);
    }

    public void setMutationPublisher(Runnable publisher) {
        mutationPublisher = publisher == null ? () -> { } : publisher;
    }

    public void refreshAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                Map<Integer, EnumMap<FactionUpgrade, Integer>> loaded = new java.util.HashMap<>();
                for (FactionUpgradeStorage.StoredLevel stored : storage.loadAll()) {
                    FactionUpgrade upgrade = FactionUpgrade.fromConfigKey(stored.upgradeKey());
                    if (upgrade != null && stored.level() > 0) loaded
                            .computeIfAbsent(stored.factionId(), ignored -> new EnumMap<>(FactionUpgrade.class))
                            .put(upgrade, stored.level());
                }
                levels.clear(); levels.putAll(loaded);
            } catch (Exception error) {
                plugin.getLogger().log(Level.WARNING, "Could not refresh faction upgrades.", error);
            }
        });
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean canPurchase(Player player) {
        me.vertex.core.factions.FactionMember member = player == null ? null
                : FactionsHook.service().member(player.getUniqueId());
        return member != null && (member.role() == me.vertex.core.factions.FactionRole.LEADER
                || member.role() == me.vertex.core.factions.FactionRole.COLEADER);
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

    public double bonus(int factionId, FactionUpgrade upgrade) {
        Definition definition = definition(upgrade);
        if (!enabled || !definition.enabled()) {
            return 0D;
        }
        return bonusAtLevel(upgrade, level(factionId, upgrade));
    }

    public double bonusAtLevel(FactionUpgrade upgrade, int level) {
        return definition(upgrade).atLevel(level).bonus();
    }

    /**
     * A faction's TNT bank ceiling. Unlike the percentage upgrades, each
     * TNT_BANK level's configured "bonus" is the absolute capacity at that
     * level, so an unupgraded faction falls back to the configured base
     * rather than to zero.
     */
    public long tntCapacity(int factionId) {
        int level = level(factionId, FactionUpgrade.TNT_BANK);
        if (!enabled || !definition(FactionUpgrade.TNT_BANK).enabled() || level <= 0) {
            return tntBaseCapacity;
        }
        return Math.max(tntBaseCapacity, (long) bonusAtLevel(FactionUpgrade.TNT_BANK, level));
    }

    /** The amount charged for the next level, or -1 once maxed/disabled. */
    public double nextCost(int factionId, FactionUpgrade upgrade) {
        Definition definition = definition(upgrade);
        int current = level(factionId, upgrade);
        if (!enabled || !definition.enabled() || current >= definition.maxLevel()) {
            return -1D;
        }
        return definition.atLevel(current + 1).price();
    }

    /** Cost-free, audited /fa override with explicit level bounds. */
    public CompletableFuture<Boolean> setLevel(int factionId, FactionUpgrade upgrade, int level) {
        if (upgrade == null || level < 0 || level > definition(upgrade).maxLevel()) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(() -> {
            try { storage.save(factionId, upgrade.configKey(), level); return true; }
            catch (Exception error) {
                plugin.getLogger().log(Level.WARNING, "Could not apply faction-upgrade override.", error);
                return false;
            }
        });
        result.thenAccept(success -> {
            if (!success) return;
            synchronized (levels) {
                EnumMap<FactionUpgrade, Integer> factionLevels = levels.computeIfAbsent(factionId,
                        ignored -> new EnumMap<>(FactionUpgrade.class));
                if (level == 0) factionLevels.remove(upgrade); else factionLevels.put(upgrade, level);
            }
            mutationPublisher.run();
            if (upgrade == FactionUpgrade.SPAWNER_RATE) {
                Bukkit.getScheduler().runTask(plugin, spawnerRetune);
            }
        });
        return result;
    }

    public PurchaseResult purchase(Player player, FactionData faction, FactionUpgrade upgrade) {
        if (!enabled || !definition(upgrade).enabled()) {
            return PurchaseResult.DISABLED;
        }
        if (!canPurchase(player)) {
            return PurchaseResult.LEADER_ONLY;
        }
        int factionId = faction.id();
        String purchaseKey = factionId + ":" + upgrade.configKey();
        if (!pendingPurchases.add(purchaseKey)) {
            return PurchaseResult.PENDING;
        }
        int current = level(factionId, upgrade);
        if (current >= definition(upgrade).maxLevel()) {
            pendingPurchases.remove(purchaseKey);
            return PurchaseResult.MAXED;
        }
        double cost = nextCost(factionId, upgrade);
        Economy economy = null;
        if (cost > 0D) {
            if (!EconomyHook.isAvailable()) {
                pendingPurchases.remove(purchaseKey);
                return PurchaseResult.NO_ECONOMY;
            }
            economy = EconomyHook.getEconomy();
            EconomyResponse response = economy.withdrawPlayer(player, cost);
            if (response == null || !response.transactionSuccess()) {
                pendingPurchases.remove(purchaseKey);
                return PurchaseResult.CANNOT_AFFORD;
            }
        }

        int newLevel = current + 1;
        levels.computeIfAbsent(factionId, ignored -> new EnumMap<>(FactionUpgrade.class)).put(upgrade, newLevel);
        Economy chargedEconomy = economy;
        queueSave(player.getUniqueId(), factionId, upgrade, current, newLevel).whenComplete((ignored, error) -> {
            if (error == null) {
                // Only release the lock here on success. On failure, a
                // second purchase must not be able to start (and price
                // itself off the about-to-be-reverted level) before the
                // rollback below actually runs.
                pendingPurchases.remove(purchaseKey);
                mutationPublisher.run();
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    rollbackFailedPurchase(player, faction, factionId, upgrade, current, newLevel, cost, chargedEconomy);
                } finally {
                    pendingPurchases.remove(purchaseKey);
                }
            });
        });
        if (upgrade == FactionUpgrade.SPAWNER_RATE) {
            spawnerRetune.run();
        }
        return PurchaseResult.SUCCESS;
    }

    private void rollbackFailedPurchase(Player player, FactionData faction, int factionId, FactionUpgrade upgrade,
            int previousLevel, int attemptedLevel, double cost, Economy economy) {
        EnumMap<FactionUpgrade, Integer> factionLevels = levels.get(factionId);
        if (factionLevels != null && factionLevels.getOrDefault(upgrade, 0) == attemptedLevel) {
            factionLevels.put(upgrade, previousLevel);
        }
        if (cost > 0D && economy != null) {
            EconomyResponse refunded = economy.depositPlayer(player, cost);
            if (refunded == null || !refunded.transactionSuccess()) {
                plugin.getLogger().warning("Could not refund a faction-upgrade purchase after its database write failed.");
            }
        }
        if (upgrade == FactionUpgrade.SPAWNER_RATE) {
            spawnerRetune.run();
        }
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
    public void onFactionDisband(FactionLifecycleEvent event) {
        if (event.action() == FactionLifecycleEvent.Action.DISBAND) {
            deleteFactionLevels(event.faction().id());
        }
    }

    private void deleteFactionLevels(int factionId) {
        levels.remove(factionId);
        // Reuse the faction's write chain. A just-purchased level may still
        // be queued; deleting independently could race ahead of it and let
        // that delayed upsert resurrect rows for a disbanded faction.
        CompletableFuture<Void> write = writeChains.compute(factionId, (ignored, previous) ->
                (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous.handle((done, error) -> null))
                        .thenRunAsync(() -> {
                            try {
                                storage.deleteFaction(factionId);
                                mutationPublisher.run();
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

    private CompletableFuture<Void> queueSave(java.util.UUID purchaser, int factionId,
            FactionUpgrade upgrade, int expectedLevel, int level) {
        CompletableFuture<Void> write = writeChains.compute(factionId, (ignored, previous) ->
                (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous.handle((done, error) -> null))
                        .thenRunAsync(() -> {
                            try {
                                if (!storage.advanceAuthorized(factionId, purchaser, upgrade.configKey(),
                                        expectedLevel, level)) {
                                    throw new java.sql.SQLException("Faction upgrade level or purchaser authority changed on another shard");
                                }
                            } catch (Exception e) {
                                throw new java.util.concurrent.CompletionException(e);
                            }
                        }));
        pendingWrites.add(write);
        write.whenComplete((ignored, error) -> {
            pendingWrites.remove(write);
            writeChains.remove(factionId, write);
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Failed to save faction upgrade to the database.", error);
            }
        });
        return write;
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

    /**
     * Reads explicit level entries such as {@code levels.3.price} and
     * {@code levels.3.bonus}. Older configurations are converted once at
     * load time so upgrading never requires a multiplier-based price.
     */
    private List<Tier> loadLevels(FactionUpgrade upgrade, ConfigurationSection section) {
        double maximumBonus = upgrade == FactionUpgrade.TNT_BANK ? 100_000_000D
                : upgrade == FactionUpgrade.SHIELD_DURATION ? 604_800D : 10_000D;
        if (section != null && section.isConfigurationSection("levels")) {
            ConfigurationSection configured = section.getConfigurationSection("levels");
            List<Tier> values = new ArrayList<>();
            int configuredMaximum = upgrade == FactionUpgrade.SHIELD_DURATION ? 4 : MAX_CONFIGURED_LEVEL;
            for (int level = 1; level <= configuredMaximum; level++) {
                ConfigurationSection entry = configured.getConfigurationSection(String.valueOf(level));
                if (entry == null) {
                    if (!values.isEmpty()) {
                        plugin.getLogger().warning("Faction upgrade '" + section.getCurrentPath()
                                + "' has a missing level " + level
                                + "; higher configured levels are ignored until the gap is filled.");
                    }
                    break;
                }
                values.add(new Tier(
                        clamp(entry.getDouble("price", 0D), 0D, MAX_CONFIGURED_COST),
                        clamp(entry.getDouble("bonus", 0D), 0D, maximumBonus)));
            }
            return List.copyOf(values);
        }

        int maxLevel = Math.max(0, Math.min(MAX_CONFIGURED_LEVEL,
                section == null ? 5 : section.getInt("max-level", 5)));
        double baseCost = clamp(section == null ? 0D : section.getDouble("cost-base", 0D), 0D,
                MAX_CONFIGURED_COST);
        double multiplier = clamp(section == null ? 1D : section.getDouble("cost-multiplier", 1D), 1D, 100D);
        double bonusPerLevel = clamp(section == null ? 0D : section.getDouble("bonus-per-level", 0D), 0D, maximumBonus);
        List<Tier> values = new ArrayList<>();
        for (int level = 1; level <= maxLevel; level++) {
            double price = Math.min(MAX_CONFIGURED_COST, baseCost * Math.pow(multiplier, level - 1));
            values.add(new Tier(Double.isFinite(price) ? price : MAX_CONFIGURED_COST, bonusPerLevel * level));
        }
        return List.copyOf(values);
    }

    private static List<Tier> defaultShieldLevels() {
        return List.of(new Tier(10_000_000D, 3_600D), new Tier(30_000_000D, 7_200D),
                new Tier(75_000_000D, 10_800D), new Tier(150_000_000D, 14_400D));
    }

    public enum PurchaseResult { SUCCESS, PENDING, DISABLED, LEADER_ONLY, MAXED, NO_ECONOMY, CANNOT_AFFORD }

    public record Definition(boolean enabled, List<Tier> levels) {
        private static final Definition DISABLED = new Definition(false, List.of());

        public int maxLevel() {
            return levels.size();
        }

        public Tier atLevel(int level) {
            return level <= 0 || level > levels.size() ? Tier.ZERO : levels.get(level - 1);
        }
    }

    public record Tier(double price, double bonus) {
        private static final Tier ZERO = new Tier(0D, 0D);
    }
}
