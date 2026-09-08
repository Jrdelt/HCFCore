package me.vertex.core.wand;

import me.vertex.core.collector.ChunkCollectorManager;
import me.vertex.core.economy.EconomyHook;
import me.vertex.core.faction.FactionBankManager;
import me.vertex.core.faction.FactionUpgradeManager;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import me.vertex.core.shop.ShopManager;
import me.vertex.core.util.Numbers;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Right-clicking a Chest or Chunk Collector with a wand.
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

    /** Containers mid-transaction, so the same one is never processed twice at once. */
    private final Set<Location> busy = ConcurrentHashMap.newKeySet();

    public WandListener(Plugin plugin, WandManager wands, ShopManager shop, ChunkCollectorManager collectors,
            FactionBankManager bank, FactionUpgradeManager upgrades, Messages messages) {
        this.plugin = plugin;
        this.wands = wands;
        this.shop = shop;
        this.collectors = collectors;
        this.bank = bank;
        this.upgrades = upgrades;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
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
        if (!busy.add(block.getLocation())) {
            player.sendMessage(messages.get(player, "wand.container-busy"));
            return;
        }
        try {
            if (tier.type() == WandType.SELL) {
                runSell(player, held, tier, container);
            } else {
                runTnt(player, held, tier, container, block.getLocation());
            }
        } finally {
            busy.remove(block.getLocation());
        }
    }

    private void runSell(Player player, ItemStack held, WandTier tier, WandContainer container) {
        if (!EconomyHook.isAvailable()) {
            player.sendMessage(messages.get(player, "wand.no-economy"));
            return;
        }
        Map<Material, Integer> contents = container.contents(wands::isSellable);

        double payout = 0D;
        int sold = 0;
        for (Map.Entry<Material, Integer> entry : contents.entrySet()) {
            if (!shop.isTradeable(entry.getKey())) {
                continue;
            }
            // Priced through the shop one unit at a time, so emptying a full
            // container walks the price down exactly as selling the same
            // amount by hand would -- a container is not a way around dynamic
            // pricing.
            container.remove(entry.getKey(), entry.getValue(), wands::isSellable);
            payout += shop.sellFromContainer(player, entry.getKey(), entry.getValue());
            sold += entry.getValue();
        }

        if (sold <= 0) {
            player.sendMessage(messages.get(player, "wand.nothing-to-sell"));
            return;
        }
        container.commit();
        EconomyHook.getEconomy().depositPlayer(player, payout);
        spendUse(player, held, tier);
        player.sendMessage(messages.get(player, "wand.sold", "amount", Numbers.money(payout)));
        plugin.getLogger().info("Sell Wand: " + player.getName() + " sold " + sold + " item(s) for "
                + Numbers.moneyFull(payout) + " using tier " + tier.id() + ".");
    }

    private void runTnt(Player player, ItemStack held, WandTier tier, WandContainer container, Location location) {
        int factionId = FactionsHook.getFactionId(player);
        if (factionId == FactionsHook.NO_FACTION) {
            player.sendMessage(messages.get(player, "wand.no-faction"));
            return;
        }
        long capacity = upgrades.tntCapacity(factionId);
        long room = capacity - bank.tnt(factionId);
        if (room <= 0) {
            // A full bank costs the player nothing: no use spent, no gunpowder
            // taken, no partial conversion.
            player.sendMessage(messages.get(player, "wand.tnt-bank-full",
                    "max", Numbers.formatFull(capacity)));
            return;
        }

        Map<Material, Integer> contents = container.contents(wands::isPlainStack);
        int gunpowder = contents.getOrDefault(Material.GUNPOWDER, 0);
        int sandPer = wands.sandPerTnt();
        int sand = sandPer > 0 ? contents.getOrDefault(Material.SAND, 0) : Integer.MAX_VALUE;

        int possible = gunpowder / wands.gunpowderPerTnt();
        if (sandPer > 0) {
            possible = Math.min(possible, sand / sandPer);
        }
        int converted = (int) Math.min(possible, room);
        if (converted <= 0) {
            player.sendMessage(messages.get(player, "wand.no-gunpowder"));
            return;
        }

        container.remove(Material.GUNPOWDER, converted * wands.gunpowderPerTnt(), wands::isPlainStack);
        if (sandPer > 0) {
            container.remove(Material.SAND, converted * sandPer, wands::isPlainStack);
        }
        container.commit();

        int amount = converted;
        bank.depositTnt(factionId, amount, capacity).whenComplete((stored, error) ->
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null || !Boolean.TRUE.equals(stored)) {
                        // The bank write failed after the gunpowder was taken,
                        // so hand the TNT back as items rather than losing it.
                        giveOrDrop(player, new ItemStack(Material.TNT, amount));
                        player.sendMessage(messages.get(player, "wand.tnt-bank-failed"));
                        return;
                    }
                    player.sendMessage(messages.get(player, "wand.tnt-converted",
                            "amount", Numbers.formatFull(amount)));
                    plugin.getLogger().info("TNT Wand: " + player.getName() + " banked " + amount
                            + " TNT for faction " + factionId + " using tier " + tier.id() + ".");
                }));
        spendUse(player, held, tier);
    }

    private void giveOrDrop(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private void spendUse(Player player, ItemStack held, WandTier tier) {
        if (wands.consumeUse(held, tier)) {
            held.setAmount(0);
            player.sendMessage(messages.get(player, "wand.used-up"));
        }
    }
}
