package me.vertex.core.enchant.menu;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.enchant.AutoIncineration;
import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.IncinerationService;
import me.vertex.core.enchant.IncineratorEligibility;
import me.vertex.core.enchant.RunePreferenceManager;
import me.vertex.core.enchant.RuneTier;
import me.vertex.core.lang.Messages;
import me.vertex.core.storage.InventoryAccess;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Click handling across the Incinerator, its confirmations, and the Incineration Filter Catalog/Level Filter pages. */
public final class IncineratorMenuListener implements Listener {

    private final EnchantManager manager;
    private final RunePreferenceManager preferences;
    private final IncinerationService incineration;
    private final AutoIncineration autoIncineration;
    private final Messages messages;

    public IncineratorMenuListener(EnchantManager manager, RunePreferenceManager preferences,
            IncinerationService incineration, AutoIncineration autoIncineration, Messages messages) {
        this.manager = manager;
        this.preferences = preferences;
        this.incineration = incineration;
        this.autoIncineration = autoIncineration;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (isOwnedHolder(event.getInventory().getHolder())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!InventoryAccess.ready(manager.plugin(), player)) {
            event.setCancelled(true);
            return;
        }
        var top = event.getView().getTopInventory();
        var holder = top.getHolder();
        if (!isOwnedHolder(holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != top) {
            return;
        }
        if (holder instanceof IncineratorMenu.Holder incineratorHolder) {
            handleIncinerator(event, player, incineratorHolder);
        } else if (holder instanceof IncineratorConfirmMenu.Holder confirmHolder) {
            handleConfirm(event, player, confirmHolder);
        } else if (holder instanceof IncinerationFilterMenu.Holder filterHolder) {
            handleFilterCatalog(event, player, filterHolder);
        } else if (holder instanceof LevelFilterMenu.Holder levelHolder) {
            handleLevelFilter(event, player, levelHolder);
        }
    }

    private void handleIncinerator(InventoryClickEvent event, Player player, IncineratorMenu.Holder holder) {
        int slot = event.getSlot();
        if (slot == IncineratorMenu.INCINERATE_ALL_SLOT) {
            List<IncineratorEligibility.Entry> eligible = IncineratorEligibility.unprotectedStandaloneRunes(player, manager, preferences);
            if (eligible.isEmpty()) {
                player.sendMessage(messages.get(player, "rune.incinerate-nothing-eligible"));
                return;
            }
            List<Integer> slots = eligible.stream().map(IncineratorEligibility.Entry::slotIndex).toList();
            IncineratorConfirmMenu.openBatch(player, messages, slots, estimateRange(eligible));
            return;
        }
        if (slot == IncineratorMenu.FILTERS_SLOT) {
            IncinerationFilterMenu.open(player, manager, messages, null);
            return;
        }
        Integer inventorySlot = holder.inventorySlotAt(slot);
        if (inventorySlot == null) {
            return;
        }
        ItemStack[] contents = player.getInventory().getStorageContents();
        if (inventorySlot >= contents.length || contents[inventorySlot] == null || contents[inventorySlot].getType().isAir()) {
            player.sendMessage(messages.get(player, "rune.incinerate-no-longer-available"));
            return;
        }
        IncineratorConfirmMenu.openSingle(player, messages, inventorySlot, contents[inventorySlot]);
    }

    private void handleConfirm(InventoryClickEvent event, Player player, IncineratorConfirmMenu.Holder holder) {
        if (event.getSlot() == IncineratorConfirmMenu.CANCEL_SLOT) {
            player.closeInventory();
            return;
        }
        if (event.getSlot() != IncineratorConfirmMenu.CONFIRM_SLOT) {
            return;
        }
        if (holder.mode() == IncineratorConfirmMenu.Mode.AUTO_ENABLE) {
            autoIncineration.setEnabled(player.getUniqueId(), true);
            IncinerationService.BatchOutcome swept = incineration.incinerateBatch(player,
                    IncineratorEligibility.unprotectedStandaloneRunes(player, manager, preferences).stream()
                            .map(IncineratorEligibility.Entry::slotIndex).toList());
            player.sendMessage(messages.get(player, "rune.auto-enabled"));
            if (swept.successCount() > 0) {
                player.sendMessage(messages.get(player, "rune.incinerate-all-success",
                        "count", String.valueOf(swept.successCount()), "rewards", formatRewards(swept.rewardsByCurrency())));
            }
            player.closeInventory();
            return;
        }
        if (holder.mode() == IncineratorConfirmMenu.Mode.SINGLE) {
            IncinerationService.Outcome outcome = incineration.incinerateOne(player, holder.inventorySlots().get(0));
            if (outcome.success()) {
                player.sendMessage(messages.get(player, "rune.incinerate-success", "reward", formatReward(outcome.currency(), outcome.rewardAmount())));
            } else {
                player.sendMessage(messages.get(player, "rune.incinerate-no-longer-available"));
            }
        } else {
            IncinerationService.BatchOutcome outcome = incineration.incinerateBatch(player, holder.inventorySlots());
            if (outcome.successCount() == 0) {
                player.sendMessage(messages.get(player, "rune.incinerate-nothing-eligible"));
            } else {
                player.sendMessage(messages.get(player, "rune.incinerate-all-success",
                        "count", String.valueOf(outcome.successCount()), "rewards", formatRewards(outcome.rewardsByCurrency())));
            }
        }
        player.closeInventory();
    }

    private void handleFilterCatalog(InventoryClickEvent event, Player player, IncinerationFilterMenu.Holder holder) {
        if (event.getSlot() == IncinerationFilterMenu.BACK_SLOT) {
            IncineratorMenu.open(player, manager, preferences, messages);
            return;
        }
        RuneTier tabTier = IncinerationFilterMenu.tierAt(event.getSlot());
        if (tabTier != null) {
            IncinerationFilterMenu.open(player, manager, messages, tabTier);
            return;
        }
        int index = holder.indexOfSlot(event.getSlot());
        String enchantId = holder.enchantIdAt(index);
        if (enchantId != null) {
            LevelFilterMenu.open(player, manager, preferences, messages, holder.selected(), enchantId);
        }
    }

    private void handleLevelFilter(InventoryClickEvent event, Player player, LevelFilterMenu.Holder holder) {
        if (event.getSlot() == LevelFilterMenu.BACK_SLOT) {
            IncinerationFilterMenu.open(player, manager, messages, holder.backTier());
            return;
        }
        Integer level = LevelFilterMenu.levelAt(event.getSlot());
        if (level == null) {
            return;
        }
        String settingKey = "protected:" + level;
        if (event.isRightClick()) {
            preferences.set(player.getUniqueId(), holder.enchantId(), settingKey, "true");
        } else if (event.isLeftClick()) {
            preferences.reset(player.getUniqueId(), holder.enchantId(), settingKey);
        } else {
            return;
        }
        LevelFilterMenu.open(player, manager, preferences, messages, holder.backTier(), holder.enchantId());
    }

    private String estimateRange(List<IncineratorEligibility.Entry> eligible) {
        Map<EnchantManager.Currency, Double> totalBaseByCurrency = new EnumMap<>(EnchantManager.Currency.class);
        for (IncineratorEligibility.Entry entry : eligible) {
            RuneTier tier = originTierOf(entry.item());
            if (tier == null) {
                continue;
            }
            double base = manager.runeShopPrice(tier) * entry.item().getAmount();
            totalBaseByCurrency.merge(manager.runeShopCurrency(tier), base, Double::sum);
        }
        double min = manager.plugin().getConfig().getDouble("incineration.return-percentage.min", 10D);
        double max = manager.plugin().getConfig().getDouble("incineration.return-percentage.max", 30D);
        List<String> parts = new java.util.ArrayList<>();
        for (Map.Entry<EnchantManager.Currency, Double> entry : totalBaseByCurrency.entrySet()) {
            double low = entry.getValue() * Math.min(min, max) / 100D;
            double high = entry.getValue() * Math.max(min, max) / 100D;
            parts.add(formatReward(entry.getKey(), low) + " - " + formatReward(entry.getKey(), high));
        }
        return String.join(", ", parts);
    }

    private RuneTier originTierOf(ItemStack item) {
        EnchantManager.EnchantItemInfo info = manager.enchantItemInfo(item);
        return info != null ? info.originTier() : manager.tierOf(item);
    }

    private String formatRewards(Map<EnchantManager.Currency, Double> rewardsByCurrency) {
        List<String> parts = new java.util.ArrayList<>();
        for (Map.Entry<EnchantManager.Currency, Double> entry : rewardsByCurrency.entrySet()) {
            parts.add(formatReward(entry.getKey(), entry.getValue()));
        }
        return String.join(" and ", parts);
    }

    private String formatReward(EnchantManager.Currency currency, double amount) {
        return currency == EnchantManager.Currency.XP_LEVELS
                ? ((long) Math.ceil(amount)) + " XP"
                : EconomyHook.format(amount);
    }

    private static boolean isOwnedHolder(Object holder) {
        return holder instanceof IncineratorMenu.Holder || holder instanceof IncineratorConfirmMenu.Holder
                || holder instanceof IncinerationFilterMenu.Holder || holder instanceof LevelFilterMenu.Holder;
    }
}
