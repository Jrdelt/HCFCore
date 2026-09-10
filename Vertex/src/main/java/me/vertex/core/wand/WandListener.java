package me.vertex.core.wand;

import me.vertex.core.collector.ChunkCollectorManager;
import me.vertex.core.collector.ChunkCollectorData;
import me.vertex.core.backpack.BackpackFilterManager;
import me.vertex.core.faction.RallyManager;
import me.vertex.core.economy.EconomyHook;
import me.vertex.core.faction.FactionBankManager;
import me.vertex.core.faction.FactionUpgradeManager;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import me.vertex.core.shop.ShopManager;
import me.vertex.core.util.Numbers;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Left-clicking a Chest or Chunk Collector with a wand. Right-click remains
 * available for the Chunk Collector's normal management GUI.
 *
 * <p>Both wands are all-or-nothing: nothing is removed, no use is spent, and
 * no money or TNT moves unless the whole operation can complete. A container
 * being processed is locked for the duration, so two players cannot empty the
 * same chest twice, and a wand cannot be double-fired by a click repeat.
 */
public final class WandListener implements Listener {

    private final Plugin plugin;
    private final WandManager wands;
    private final ShopManager shop;
    private final ChunkCollectorManager collectors;
    private final FactionBankManager bank;
    private final FactionUpgradeManager upgrades;
    private final Messages messages;
    private final BackpackFilterManager filters;
    private final RallyManager rolePermissions;

    /** Containers mid-transaction, so the same one is never processed twice at once. */
    private final Set<Location> busy = ConcurrentHashMap.newKeySet();

    public WandListener(Plugin plugin, WandManager wands, ShopManager shop, ChunkCollectorManager collectors,
            FactionBankManager bank, FactionUpgradeManager upgrades, Messages messages,
            BackpackFilterManager filters, RallyManager rolePermissions) {
        this.plugin = plugin;
        this.wands = wands;
        this.shop = shop;
        this.collectors = collectors;
        this.bank = bank;
        this.upgrades = upgrades;
        this.messages = messages;
        this.filters = filters;
        this.rolePermissions = rolePermissions;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !wands.isEnabled()) {
            return;
        }
        ItemStack held = event.getItem();
        WandTier tier = wands.tierOf(held);
        if (tier == null) {
            return;
        }

        Player player = event.getPlayer();
        event.setCancelled(true);

        if (!wands.isEnabled(tier.type())) {
            player.sendMessage(messages.get(player, "wand.disabled"));
            return;
        }
        WandContainer container = WandContainer.of(block, collectors);
        if (container == null) {
            player.sendMessage(messages.get(player, "wand.not-a-container"));
            return;
        }
        if (wands.usesLeft(held) <= 0) {
            player.sendMessage(messages.get(player, "wand.no-uses"));
            return;
        }
        if (container.isCollector() && !canUseCollector(player, block)) {
            return;
        }
        if (!busy.add(block.getLocation())) {
            player.sendMessage(messages.get(player, "wand.container-busy"));
            return;
        }
        Location location = block.getLocation();
        if (tier.type() == WandType.SELL) {
            try {
                runSell(player, held, tier, container);
            } finally {
                busy.remove(location);
            }
            return;
        }
        // The TNT path finishes inside an async bank write, so it releases the
        // lock itself once that lands. Releasing here would drop the lock
        // while the transaction was still open.
        if (!runTnt(player, held, tier, container, location)) {
            busy.remove(location);
        }
    }

    private void runSell(Player player, ItemStack held, WandTier tier, WandContainer container) {
        if (!EconomyHook.isAvailable()) {
            player.sendMessage(messages.get(player, "wand.no-economy"));
            return;
        }
        java.util.function.Predicate<ItemStack> sellablePredicate = item -> wands.isSellable(item)
                && !filters.isFiltered(player.getUniqueId(), item.getType());
        Map<Material, Integer> contents = container.contents(sellablePredicate);

        // Priced first, without touching the container. Pricing still walks
        // the market down unit by unit, so a full container earns exactly
        // what selling it by hand would -- a container is not a way around
        // dynamic pricing.
        Map<Material, Integer> sellable = new LinkedHashMap<>();
        double payout = 0D;
        int sold = 0;
        for (Map.Entry<Material, Integer> entry : contents.entrySet()) {
            if (!shop.isTradeable(entry.getKey())) {
                continue;
            }
            payout += shop.quoteContainerSale(player, entry.getKey(), entry.getValue());
            sellable.put(entry.getKey(), entry.getValue());
            sold += entry.getValue();
        }

        if (sold <= 0) {
            player.sendMessage(messages.get(player, "wand.nothing-to-sell"));
            return;
        }

        // Paid and confirmed before anything is removed. The response used to
        // be ignored, which emptied the container for money that never
        // arrived; now a failed deposit costs the player nothing at all.
        EconomyResponse deposit = EconomyHook.getEconomy().depositPlayer(player, payout);
        if (deposit == null || !deposit.transactionSuccess()) {
            player.sendMessage(messages.get(player, "wand.payout-failed"));
            plugin.getLogger().warning("Sell Wand for " + player.getName() + " was aborted: the "
                    + Numbers.moneyFull(payout) + " payout failed, so the container was left untouched.");
            return;
        }

        sellable.forEach((material, amount) -> {
            container.remove(material, amount, sellablePredicate);
            shop.recordContainerSale(material, amount);
        });
        container.commit();
        spendUse(player, held, tier);
        player.sendMessage(messages.get(player, "wand.sold", "amount", Numbers.money(payout)));
        plugin.getLogger().info("Sell Wand: " + player.getName() + " sold " + sold + " item(s) for "
                + Numbers.moneyFull(payout) + " using tier " + tier.id() + ".");
    }

    /** @return true when an async bank write took ownership of the container lock. */
    private boolean runTnt(Player player, ItemStack held, WandTier tier, WandContainer container, Location location) {
        int factionId = FactionsHook.getFactionId(player);
        if (factionId == FactionsHook.NO_FACTION) {
            player.sendMessage(messages.get(player, "wand.no-faction"));
            return false;
        }
        long capacity = upgrades.tntCapacity(factionId);
        long room = capacity - bank.tnt(factionId);
        if (room <= 0) {
            // A full bank costs the player nothing: no use spent, no gunpowder
            // taken, no partial conversion.
            player.sendMessage(messages.get(player, "wand.tnt-bank-full",
                    "max", Numbers.formatFull(capacity)));
            return false;
        }

        int converted = convertibleTnt(container, room);
        if (converted <= 0) {
            player.sendMessage(messages.get(player, "wand.no-gunpowder"));
            return false;
        }

        // The bank is credited before anything is taken. The deposit is the
        // step that can fail or lose a race with another deposit, so doing it
        // first means a failure costs the player nothing at all -- rather than
        // taking their gunpowder and then discovering there was no room.
        bank.depositTnt(factionId, converted, capacity).whenComplete((stored, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (error != null || !Boolean.TRUE.equals(stored)) {
                            player.sendMessage(messages.get(player, "wand.tnt-bank-full",
                                    "max", Numbers.formatFull(capacity)));
                            return;
                        }
                        settleTnt(player, held, tier, container, factionId, converted);
                    } finally {
                        busy.remove(location);
                    }
                }));
        return true;
    }

    private void settleTnt(Player player, ItemStack held, WandTier tier, WandContainer container,
            int factionId, int converted) {
        // Re-checked against the container as it is now: the lock keeps other
        // wands out, but a player can still empty a chest by hand during the
        // bank write, and the gunpowder that was priced must still be there.
        if (convertibleTnt(container, converted) < converted) {
            bank.withdrawTnt(factionId, converted).whenComplete((reversed, error) -> {
                if (error != null || !Boolean.TRUE.equals(reversed)) {
                    plugin.getLogger().severe("TNT Wand bank compensation failed for faction " + factionId
                            + "; " + converted + " TNT requires staff reconciliation.");
                }
            });
            player.sendMessage(messages.get(player, "wand.container-changed"));
            return;
        }

        container.remove(Material.GUNPOWDER, converted * wands.gunpowderPerTnt(), wands::isPlainStack);
        if (wands.sandPerTnt() > 0) {
            container.remove(Material.SAND, converted * wands.sandPerTnt(), wands::isPlainStack);
        }
        container.commit();
        spendUse(player, held, tier);
        player.sendMessage(messages.get(player, "wand.tnt-converted",
                "amount", Numbers.formatFull(converted)));
        plugin.getLogger().info("TNT Wand: " + player.getName() + " banked " + converted
                + " TNT for faction " + factionId + " using tier " + tier.id() + ".");
    }

    /** How much TNT the container's materials could make, capped by {@code limit}. */
    private int convertibleTnt(WandContainer container, long limit) {
        Map<Material, Integer> contents = container.contents(wands::isPlainStack);
        int possible = contents.getOrDefault(Material.GUNPOWDER, 0) / wands.gunpowderPerTnt();
        int sandPer = wands.sandPerTnt();
        if (sandPer > 0) {
            possible = Math.min(possible, contents.getOrDefault(Material.SAND, 0) / sandPer);
        }
        return (int) Math.min(possible, limit);
    }

    /** A collector is faction storage, unlike a normal chest. Its own faction role rules always apply. */
    private boolean canUseCollector(Player player, Block block) {
        if (player.hasPermission("vertex.collector.bypass")) {
            return true;
        }
        ChunkCollectorData data = collectors.readData(block.getLocation());
        String claimTag = FactionsHook.getClaimFactionTag(block.getLocation());
        String playerTag = FactionsHook.getFactionTag(player);
        boolean belongsToPlayerFaction = (claimTag != null && claimTag.equalsIgnoreCase(playerTag))
                || (claimTag == null && data != null && data.ownerFactionTag() != null
                && data.ownerFactionTag().equalsIgnoreCase(playerTag));
        if (!belongsToPlayerFaction) {
            player.sendMessage(messages.get(player, "collector.cannot-access"));
            return false;
        }
        if (!rolePermissions.canUse(player, "collector-sell")) {
            player.sendMessage(messages.get(player, "collector.open-permission-denied"));
            return false;
        }
        return true;
    }

    /** Prevent a player breaking or editing a locked chest between quote and commit. */
    @EventHandler(ignoreCancelled = true)
    public void onBusyBreak(BlockBreakEvent event) {
        if (busy.contains(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBusyInventoryClick(InventoryClickEvent event) {
        Location location = event.getView().getTopInventory().getLocation();
        if (location != null && busy.contains(location)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBusyInventoryDrag(InventoryDragEvent event) {
        Location location = event.getView().getTopInventory().getLocation();
        if (location != null && busy.contains(location)) {
            event.setCancelled(true);
        }
    }


    private void spendUse(Player player, ItemStack held, WandTier tier) {
        if (wands.consumeUse(held, tier)) {
            held.setAmount(0);
            player.sendMessage(messages.get(player, "wand.used-up"));
        }
    }
}
