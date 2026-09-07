package me.vertex.core.backpack;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.Messages;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

/** Handles the Backpack's controls; its stored items are never displayed in the GUI. */
public final class BackpackMenuListener implements Listener {
    private final BackpackManager manager;
    private final Messages messages;

    public BackpackMenuListener(BackpackManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof BackpackMenu.Holder
                && event.getRawSlots().stream().anyMatch(slot -> slot < event.getInventory().getSize())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof BackpackMenu.Holder holder)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        boolean clickedMenu = event.getClickedInventory() != null
                && event.getClickedInventory().getHolder() instanceof BackpackMenu.Holder;
        if (!clickedMenu) {
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() == BackpackMenu.EMPTY_SLOT) {
            empty(player, holder);
        } else if (event.getRawSlot() == BackpackMenu.UPGRADE_SLOT) {
            upgrade(player, holder);
        }
    }

    private void empty(Player player, BackpackMenu.Holder holder) {
        BackpackData data = manager.readData(holder.backpackItem());
        if (data == null) {
            return;
        }
        for (ItemStack item : data.contents()) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            // Each entry can hold far more than a vanilla max stack (that's
            // the whole point -- capacity is items, not slots), so it has
            // to be split back into real stacks before handing it to a
            // player inventory or dropping it.
            for (ItemStack stack : BackpackManager.splitIntoRealStacks(item, item.getAmount())) {
                player.getInventory().addItem(stack).values()
                        .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            }
        }
        manager.writeData(holder.backpackItem(), data.withContents(new ItemStack[0]));
        syncToOffhand(player, holder.backpackItem());
    }

    private void upgrade(Player player, BackpackMenu.Holder holder) {
        BackpackData data = manager.readData(holder.backpackItem());
        BackpackTier tier = holder.tier();
        if (data == null || tier == null) {
            return;
        }
        double cost = manager.upgradeCost(tier, data.level());
        if (cost < 0) {
            player.sendMessage(messages.get(player, "backpack.upgrade-maxed", "level", String.valueOf(data.level())));
            return;
        }
        if (!EconomyHook.isAvailable()) {
            player.sendMessage(messages.get(player, "spawner.no-economy"));
            return;
        }
        Economy economy = EconomyHook.getEconomy();
        EconomyResponse response = economy.withdrawPlayer(player, cost);
        if (!response.transactionSuccess()) {
            player.sendMessage(messages.get(player, "backpack.cannot-afford", "amount", EconomyHook.format(cost)));
            return;
        }
        int newLevel = data.level() + 1;
        // Storage isn't slot-based, so there's nothing to resize -- an
        // upgrade only raises the item capacity ceiling; contents carry
        // over untouched.
        BackpackData upgraded = data.withLevel(newLevel);
        manager.writeData(holder.backpackItem(), upgraded);
        syncToOffhand(player, holder.backpackItem());
        player.sendMessage(messages.get(player, "backpack.upgraded", "level", String.valueOf(newLevel)));
        BackpackMenu.open(player, manager, messages, tier, upgraded, holder.backpackItem());
    }

    private void syncToOffhand(Player player, ItemStack backpack) {
        if (manager.isSameInstance(player.getInventory().getItemInOffHand(), backpack)) {
            // Assigning a clone makes Paper send an actual inventory update,
            // including the freshly rebuilt contents line in the lore.
            player.getInventory().setItemInOffHand(backpack.clone());
            // Forces the client to actually re-render the tooltip -- an
            // in-place ItemMeta/lore change doesn't reliably trigger a
            // resend on its own.
            player.updateInventory();
        }
    }
}
