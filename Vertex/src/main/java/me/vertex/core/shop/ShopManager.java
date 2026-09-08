package me.vertex.core.shop;

import me.vertex.core.economy.EconomyHook;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * A small, essential-blocks-only market: buying pushes a block's price up,
 * selling pushes it down, and every price drifts back toward its
 * configured base on its own (see {@link ShopPricing}). Net-volume (how
 * far a block's price has drifted) is the only state this owns, and it's
 * kept in the database so the market survives a restart exactly as
 * players left it -- not reset, not lost.
 */
public final class ShopManager {

    private final Plugin plugin;
    private final ShopStorage storage;
    private final File file;

    private volatile boolean enabled;
    private volatile double priceChangePerUnit;
    private volatile double minMultiplier;
    private volatile double maxMultiplier;
    private volatile double sellPriceRatio;
    private volatile double priceChangeThresholdUnits;
    private volatile long decayIntervalTicks;
    private volatile double decayFraction;
    private volatile int defaultBuyAmount;

    private final Map<Material, ShopEntry> entries = new LinkedHashMap<>();
    private final Map<String, ShopCategory> categories = new LinkedHashMap<>();
    private final Map<Material, Double> netVolume = new ConcurrentHashMap<>();
    private final java.util.Set<CompletableFuture<?>> pendingWrites = ConcurrentHashMap.newKeySet();

    public ShopManager(Plugin plugin, ShopStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.file = new File(plugin.getDataFolder(), "shop.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("shop.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        enabled = config.getBoolean("enabled", true);
        priceChangePerUnit = Math.max(0, config.getDouble("price-change-per-unit", 0.001));
        minMultiplier = Math.max(0.01, config.getDouble("min-price-multiplier", 0.25));
        maxMultiplier = Math.max(minMultiplier, config.getDouble("max-price-multiplier", 4.0));
        sellPriceRatio = Math.max(0, Math.min(1, config.getDouble("sell-price-ratio", 0.75)));
        priceChangeThresholdUnits = Math.max(0, config.getDouble("price-change-threshold-units", 50));
        decayIntervalTicks = Math.max(20, config.getLong("decay-interval-ticks", 6000));
        decayFraction = Math.max(0, Math.min(1, config.getDouble("decay-fraction", 0.05)));
        defaultBuyAmount = Math.max(1, config.getInt("default-buy-amount", 1));

        entries.clear();
        categories.clear();
        ConfigurationSection categorySection = config.getConfigurationSection("categories");
        if (categorySection != null) {
            for (String categoryId : categorySection.getKeys(false)) {
                if (categories.size() >= 9) {
                    plugin.getLogger().warning("Ignoring Shop category '" + categoryId
                            + "' -- the /shop picker is a single row, so only the first 9 categories fit.");
                    continue;
                }
                ConfigurationSection categoryConfig = categorySection.getConfigurationSection(categoryId);
                if (categoryConfig == null) {
                    continue;
                }
                String displayName = categoryConfig.getString("display-name", categoryId);
                Material icon = Material.matchMaterial(categoryConfig.getString("icon", "CHEST"));
                if (icon == null || icon.isAir()) {
                    icon = Material.CHEST;
                }

                List<ShopEntry> categoryEntries = new java.util.ArrayList<>();
                ConfigurationSection items = categoryConfig.getConfigurationSection("items");
                if (items != null) {
                    for (String key : items.getKeys(false)) {
                        Material material = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
                        if (material == null || material.isAir()) {
                            plugin.getLogger().warning("Ignoring invalid Shop item '" + key + "' in category '" + categoryId + "'.");
                            continue;
                        }
                        if (entries.containsKey(material)) {
                            plugin.getLogger().warning("Ignoring duplicate Shop item '" + key + "' in category '"
                                    + categoryId + "' -- it's already registered in another category.");
                            continue;
                        }
                        double basePrice = items.getDouble(key + ".base-price", 1.0);
                        boolean dynamicPricing = items.getBoolean(key + ".dynamic-pricing", true);
                        ShopEntry entry = new ShopEntry(material, Math.max(0.01, basePrice), dynamicPricing);
                        entries.put(material, entry);
                        categoryEntries.add(entry);
                    }
                }
                categories.put(categoryId, new ShopCategory(categoryId, displayName, icon, List.copyOf(categoryEntries)));
            }
        }
    }

    public void loadState() {
        try {
            for (Map.Entry<String, Double> entry : storage.loadAll().entrySet()) {
                Material material = Material.matchMaterial(entry.getKey());
                if (material != null && entries.containsKey(material)) {
                    netVolume.put(material, entry.getValue());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load Shop market state from the database.", e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long decayIntervalTicks() {
        return decayIntervalTicks;
    }

    public int defaultBuyAmount() {
        return defaultBuyAmount;
    }

    public List<ShopEntry> entries() {
        return List.copyOf(entries.values());
    }

    public List<ShopCategory> categories() {
        return List.copyOf(categories.values());
    }

    public ShopCategory category(String id) {
        return categories.get(id);
    }

    public boolean isTradeable(Material material) {
        return entries.containsKey(material);
    }

    private double volumeOf(Material material) {
        return netVolume.getOrDefault(material, 0.0);
    }

    public double buyPrice(Material material) {
        ShopEntry entry = entries.get(material);
        if (entry == null) {
            return 0;
        }
        if (!entry.dynamicPricing()) {
            return entry.basePrice();
        }
        return ShopPricing.buyPrice(entry.basePrice(), volumeOf(material), priceChangePerUnit,
                minMultiplier, maxMultiplier, priceChangeThresholdUnits);
    }

    /** Whether the current buy price sits above, below, or right at the block's configured base price. */
    public int priceDirection(Material material) {
        ShopEntry entry = entries.get(material);
        if (entry == null || !entry.dynamicPricing()) {
            return 0;
        }
        double current = buyPrice(material);
        if (current > entry.basePrice() + 0.0001) {
            return 1;
        }
        if (current < entry.basePrice() - 0.0001) {
            return -1;
        }
        return 0;
    }

    public double sellPrice(Material material) {
        return ShopPricing.sellPrice(buyPrice(material), sellPriceRatio);
    }

    /** Total cost to buy {@code amount} units right now, accounting for the price rising as the batch is bought. */
    public double totalBuyCost(Material material, int amount) {
        ShopEntry entry = entries.get(material);
        if (entry == null) {
            return 0;
        }
        if (!entry.dynamicPricing()) {
            return entry.basePrice() * amount;
        }
        double volume = volumeOf(material);
        double total = 0;
        for (int i = 0; i < amount; i++) {
            total += ShopPricing.buyPrice(entry.basePrice(), volume + i, priceChangePerUnit, minMultiplier, maxMultiplier,
                    priceChangeThresholdUnits);
        }
        return total;
    }

    /** Total payout to sell {@code amount} units right now, accounting for the price falling as the batch is sold. */
    public double totalSellPayout(Material material, int amount) {
        ShopEntry entry = entries.get(material);
        if (entry == null) {
            return 0;
        }
        if (!entry.dynamicPricing()) {
            return ShopPricing.sellPrice(entry.basePrice(), sellPriceRatio) * amount;
        }
        double volume = volumeOf(material);
        double total = 0;
        for (int i = 0; i < amount; i++) {
            double buyPriceAtStep = ShopPricing.buyPrice(entry.basePrice(), volume - i, priceChangePerUnit, minMultiplier,
                    maxMultiplier, priceChangeThresholdUnits);
            total += ShopPricing.sellPrice(buyPriceAtStep, sellPriceRatio);
        }
        return total;
    }

    public enum TradeResult {
        OK, DISABLED, UNKNOWN_BLOCK, NO_ECONOMY, CANNOT_AFFORD, NOT_ENOUGH_ITEMS
    }

    public record TradeOutcome(TradeResult result, double total) {
        static TradeOutcome failure(TradeResult result) {
            return new TradeOutcome(result, 0);
        }
    }

    public TradeOutcome buy(Player player, Material material, int amount) {
        if (!enabled) {
            return TradeOutcome.failure(TradeResult.DISABLED);
        }
        if (!entries.containsKey(material) || amount <= 0) {
            return TradeOutcome.failure(TradeResult.UNKNOWN_BLOCK);
        }
        if (!EconomyHook.isAvailable()) {
            return TradeOutcome.failure(TradeResult.NO_ECONOMY);
        }
        double cost = totalBuyCost(material, amount);
        Economy economy = EconomyHook.getEconomy();
        EconomyResponse response = economy.withdrawPlayer(player, cost);
        if (!response.transactionSuccess()) {
            return TradeOutcome.failure(TradeResult.CANNOT_AFFORD);
        }

        for (ItemStack leftover : player.getInventory().addItem(new ItemStack(material, amount)).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        adjustVolume(material, ShopPricing.afterBuy(volumeOf(material), amount));
        return new TradeOutcome(TradeResult.OK, cost);
    }

    public TradeOutcome sell(Player player, Material material, int amount) {
        if (!enabled) {
            return TradeOutcome.failure(TradeResult.DISABLED);
        }
        if (!entries.containsKey(material) || amount <= 0) {
            return TradeOutcome.failure(TradeResult.UNKNOWN_BLOCK);
        }
        if (!EconomyHook.isAvailable()) {
            return TradeOutcome.failure(TradeResult.NO_ECONOMY);
        }
        if (countInInventory(player, material) < amount) {
            return TradeOutcome.failure(TradeResult.NOT_ENOUGH_ITEMS);
        }

        double payout = totalSellPayout(material, amount);
        Map<Integer, ItemStack> leftover = player.getInventory().removeItem(new ItemStack(material, amount));
        if (!leftover.isEmpty()) {
            // Should be unreachable given the count check above, but never
            // pay out for items that didn't actually leave the inventory.
            for (ItemStack notRemoved : leftover.values()) {
                player.getInventory().addItem(notRemoved);
            }
            return TradeOutcome.failure(TradeResult.NOT_ENOUGH_ITEMS);
        }

        EconomyHook.getEconomy().depositPlayer(player, payout);
        adjustVolume(material, ShopPricing.afterSell(volumeOf(material), amount));
        return new TradeOutcome(TradeResult.OK, payout);
    }

    private static int countInInventory(Player player, Material material) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private void adjustVolume(Material material, double newVolume) {
        ShopEntry entry = entries.get(material);
        if (entry != null && !entry.dynamicPricing()) {
            // Flat-priced item -- nothing to track, and never getting here
            // keeps its price from later resuming a dynamic-looking decay.
            return;
        }
        netVolume.put(material, newVolume);
        String name = material.name();
        track(CompletableFuture.runAsync(() -> {
            try {
                storage.save(name, newVolume);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist Shop market state for " + name, e);
            }
        }));
    }

    /** Called on a repeating task every {@link #decayIntervalTicks()}; drifts every traded block back toward its base price. */
    public void decayTick() {
        for (Map.Entry<Material, Double> entry : List.copyOf(netVolume.entrySet())) {
            Material material = entry.getKey();
            double decayed = ShopPricing.decay(entry.getValue(), decayFraction);
            if (ShopPricing.isEffectivelyZero(decayed)) {
                netVolume.remove(material);
                String name = material.name();
                track(CompletableFuture.runAsync(() -> {
                    try {
                        storage.delete(name);
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING, "Failed to clear Shop market state for " + name, e);
                    }
                }));
            } else {
                adjustVolume(material, decayed);
            }
        }
    }

    private void track(CompletableFuture<?> write) {
        pendingWrites.add(write);
        write.whenComplete((ignored, error) -> pendingWrites.remove(write));
    }

    public void awaitWrites() {
        try {
            CompletableFuture.allOf(pendingWrites.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Timed out waiting for pending Shop writes.", e);
        }
    }
}
