package me.vertex.core.enchant;

import me.vertex.core.economy.EconomyHook;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * The single place Incineration's destructive/reward logic lives -- every
 * GUI and every auto-incineration trigger calls into this instead of
 * duplicating eligibility, reward math, or journal bookkeeping.
 *
 * <p><b>Journal durability</b>: this codebase's existing convention (see
 * {@code BindManager}, {@code RuneCooldownStore}, {@code
 * AnnouncementPreferenceManager}) is to mutate in-memory/gameplay state
 * immediately, then persist asynchronously -- not true write-ahead logging.
 * This class follows that same convention: the item is removed and the
 * reward credited synchronously (instant for the player), and the journal
 * row is written asynchronously around that. {@link #reconcileOnStartup}
 * still repairs anything left incomplete by an actual crash; it just isn't
 * guaranteed durable against a crash in the same tick as the mutation.
 */
public final class IncinerationService implements Listener {

    private record PendingXp(String transactionId, int amount) { }

    private final Plugin plugin;
    private final EnchantManager manager;
    private final IncinerationStorage storage;
    private final RunePreferenceManager preferences;
    private final Map<UUID, List<PendingXp>> pendingOfflineXp = new ConcurrentHashMap<>();

    public IncinerationService(Plugin plugin, EnchantManager manager, IncinerationStorage storage, RunePreferenceManager preferences) {
        this.plugin = plugin;
        this.manager = manager;
        this.storage = storage;
        this.preferences = preferences;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public record Outcome(boolean success, String runeName, int level, EnchantManager.Currency currency, double rewardAmount) {
        static Outcome failure() {
            return new Outcome(false, null, 0, null, 0D);
        }
    }

    public record BatchOutcome(int successCount, Map<EnchantManager.Currency, Double> rewardsByCurrency) {
    }

    /** Re-validates against the LIVE inventory and CURRENT filters -- never trusts a GUI snapshot. */
    public Outcome incinerateOne(Player player, int slotIndex) {
        return incinerateOne(player, slotIndex, null);
    }

    private Outcome incinerateOne(Player player, int slotIndex, String batchId) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        if (slotIndex < 0 || slotIndex >= contents.length) {
            return Outcome.failure();
        }
        ItemStack item = contents[slotIndex];
        if (item == null || item.getType().isAir() || !manager.isEnchantItem(item)) {
            return Outcome.failure();
        }
        if (IncineratorEligibility.isProtected(item, manager, preferences, player.getUniqueId())) {
            return Outcome.failure();
        }
        return execute(player, slotIndex, item, batchId);
    }

    /**
     * The identification-flow / external-pickup Auto-Incineration path
     * (see {@code RuneListener#identify} and {@code AutoIncinerationListener}):
     * the item never actually enters the player's inventory at all, so
     * there is no slot to clear -- everything else (reward calc, journal,
     * credit) is identical to {@link #incinerateOne}.
     */
    public Outcome incinerateRolledDirect(Player player, ItemStack item) {
        return execute(player, -1, item, null);
    }

    /** Each slot is validated and processed independently -- one invalid/protected slot never aborts the rest. */
    public BatchOutcome incinerateBatch(Player player, List<Integer> slotIndexes) {
        String batchId = java.util.UUID.randomUUID().toString();
        int successCount = 0;
        Map<EnchantManager.Currency, Double> totals = new EnumMap<>(EnchantManager.Currency.class);
        for (int slotIndex : slotIndexes) {
            Outcome outcome = incinerateOne(player, slotIndex, batchId);
            if (outcome.success()) {
                successCount++;
                totals.merge(outcome.currency(), outcome.rewardAmount(), Double::sum);
            }
        }
        return new BatchOutcome(successCount, totals);
    }

    private Outcome execute(Player player, int slotIndex, ItemStack item, String batchId) {
        EnchantManager.EnchantItemInfo info = manager.enchantItemInfo(item);
        if (info == null) {
            // Every caller already gates on isEnchantItem -- an unidentified
            // Rune box never reaches here; the Incinerator never destroys
            // unopened value. See IncineratorEligibility's class doc.
            return Outcome.failure();
        }
        RuneTier originTier = info.originTier();
        String runeId = info.enchantId();
        int level = info.level();
        EnchantDefinition definition = manager.definition(runeId);
        String runeName = definition == null ? runeId : definition.displayName();

        double baseValue = manager.runeShopPrice(originTier);
        EnchantManager.Currency currency = manager.runeShopCurrency(originTier);
        double min = clampPercent(plugin.getConfig().getDouble("incineration.return-percentage.min", 10D));
        double max = clampPercent(plugin.getConfig().getDouble("incineration.return-percentage.max", 30D));
        double rolled = Math.min(min, max) + ThreadLocalRandom.current().nextDouble() * Math.abs(max - min);
        int amount = item.getAmount();
        double rawReward = baseValue * amount * (rolled / 100D);
        double maxReward = baseValue * amount;
        double reward = Math.max(0D, Math.min(rawReward, maxReward));
        if (currency == EnchantManager.Currency.XP_LEVELS) {
            reward = Math.ceil(reward);
        }

        String transactionId = UUID.randomUUID().toString();
        UUID uuid = player.getUniqueId();

        // Remove first, then credit -- both synchronous and instant for the
        // player, matching this codebase's existing async-persistence
        // convention (see the class doc). slotIndex is -1 for the direct
        // (never-inserted) path, where there is nothing to clear.
        if (slotIndex >= 0) {
            player.getInventory().setItem(slotIndex, null);
        }
        persistAsync(new IncinerationStorage.Transaction(transactionId, batchId, uuid, runeId, level,
                originTier.name(), currency.name(), baseValue, rolled, reward, IncinerationStorage.State.ITEM_REMOVED));

        creditReward(player, currency, reward);
        persistStateAsync(transactionId, IncinerationStorage.State.COMPLETE);

        return new Outcome(true, runeName, level, currency, reward);
    }

    private void creditReward(Player player, EnchantManager.Currency currency, double reward) {
        if (currency == EnchantManager.Currency.XP_LEVELS) {
            player.giveExp((int) Math.ceil(reward));
            return;
        }
        Economy economy = EconomyHook.getEconomy();
        if (economy == null) {
            return;
        }
        EconomyResponse response = economy.depositPlayer(player, reward);
        if (response == null || !response.transactionSuccess()) {
            plugin.getLogger().warning("Incineration reward deposit failed for " + player.getUniqueId());
        }
    }

    private void persistAsync(IncinerationStorage.Transaction transaction) {
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                storage.insert(transaction);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not persist incineration transaction " + transaction.transactionId(), e);
            }
        });
    }

    private void persistStateAsync(String transactionId, IncinerationStorage.State state) {
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                storage.updateState(transactionId, state);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not update incineration transaction " + transactionId, e);
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        List<PendingXp> pending = pendingOfflineXp.remove(player.getUniqueId());
        if (pending != null && !pending.isEmpty()) {
            for (PendingXp px : pending) {
                player.giveExp(px.amount());
                persistStateAsync(px.transactionId(), IncinerationStorage.State.COMPLETE);
            }
        }
    }

    /**
     * Called once from {@code VertexPlugin}'s enable sequence. A row still
     * {@code ITEM_REMOVED} means the item was destroyed but we don't know if
     * the reward was credited -- since {@link #creditReward} is the very
     * next synchronous call after removal with no I/O in between, the
     * realistic crash window only ever leaves a row at exactly this state
     * when the credit itself never happened, so it's safe to retry. A row
     * still {@code PENDING} never reached this class's insert-after-removal
     * point, meaning nothing happened -- marked complete as a no-op.
     */
    public void reconcileOnStartup() {
        List<IncinerationStorage.Transaction> incomplete;
        try {
            incomplete = storage.loadIncomplete();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not load incomplete incineration transactions", e);
            return;
        }
        for (IncinerationStorage.Transaction transaction : incomplete) {
            if (transaction.state() == IncinerationStorage.State.ITEM_REMOVED) {
                org.bukkit.OfflinePlayer offline = org.bukkit.Bukkit.getOfflinePlayer(transaction.playerUuid());
                EnchantManager.Currency currency = EnchantManager.Currency.valueOf(transaction.currency());
                if (currency == EnchantManager.Currency.XP_LEVELS) {
                    org.bukkit.entity.Player online = offline.getPlayer();
                    if (online != null && online.isOnline()) {
                        online.giveExp((int) Math.ceil(transaction.rewardAmount()));
                        persistStateAsync(transaction.transactionId(), IncinerationStorage.State.COMPLETE);
                    } else {
                        pendingOfflineXp.computeIfAbsent(transaction.playerUuid(), k -> new CopyOnWriteArrayList<>())
                                .add(new PendingXp(transaction.transactionId(), (int) Math.ceil(transaction.rewardAmount())));
                        plugin.getLogger().info("Queued pending incineration XP (" + (int) Math.ceil(transaction.rewardAmount())
                                + " XP) for offline player " + transaction.playerUuid() + " to deliver on join.");
                        continue;
                    }
                } else if (EconomyHook.isAvailable()) {
                    Economy economy = EconomyHook.getEconomy();
                    if (economy != null) {
                        economy.depositPlayer(offline, transaction.rewardAmount());
                    }
                    persistStateAsync(transaction.transactionId(), IncinerationStorage.State.COMPLETE);
                } else {
                    persistStateAsync(transaction.transactionId(), IncinerationStorage.State.COMPLETE);
                }
            } else {
                persistStateAsync(transaction.transactionId(), IncinerationStorage.State.COMPLETE);
            }
        }
        if (!incomplete.isEmpty()) {
            plugin.getLogger().info("Reconciled " + incomplete.size() + " incomplete incineration transaction(s) from before the last restart.");
        }
    }

    private static double clampPercent(double value) {
        return Math.max(0D, Math.min(100D, value));
    }
}
