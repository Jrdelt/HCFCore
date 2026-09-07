package me.vertex.core.backpack;

import me.vertex.core.lang.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Routes configured mining drops and player-killed mob drops into an equipped Backpack. */
public final class BackpackAutoStoreListener implements Listener {
    private final BackpackManager manager;
    private final BackpackFilterManager filters;
    private final Messages messages;

    public BackpackAutoStoreListener(BackpackManager manager, BackpackFilterManager filters, Messages messages) {
        this.manager = manager;
        this.filters = filters;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMine(BlockBreakEvent event) {
        if (!event.isDropItems() || !manager.autoStoresMining(event.getBlock().getType())) {
            return;
        }
        Player player = event.getPlayer();
        BackpackManager.EquippedBackpack equipped = manager.equippedBackpack(player);
        if (equipped == null) {
            return;
        }
        List<ItemStack> drops = new ArrayList<>(event.getBlock().getDrops(player.getInventory().getItemInMainHand(), player));
        event.setDropItems(false);
        for (ItemStack leftover : route(player, equipped, drops)) {
            player.getWorld().dropItemNaturally(event.getBlock().getLocation(), leftover);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player || !manager.autoStoresMobDrops()) {
            return;
        }
        Player player = event.getEntity().getKiller();
        if (player == null) {
            return;
        }
        BackpackManager.EquippedBackpack equipped = manager.equippedBackpack(player);
        if (equipped == null) {
            return;
        }
        List<ItemStack> drops = new ArrayList<>(event.getDrops());
        event.getDrops().clear();
        event.getDrops().addAll(route(player, equipped, drops));
    }

    private List<ItemStack> route(Player player, BackpackManager.EquippedBackpack equipped, List<ItemStack> drops) {
        List<ItemStack> accepted = new ArrayList<>();
        for (ItemStack drop : drops) {
            if (drop != null && !drop.isEmpty() && !filters.isFiltered(player.getUniqueId(), drop.getType())) {
                accepted.add(drop);
            }
        }
        long storedBefore = BackpackManager.storedItemCount(equipped.data().contents());
        List<ItemStack> leftovers = manager.storeAutoCollected(equipped, accepted);
        // ItemMeta changes are normally visible through Bukkit's inventory
        // reference, but setting it explicitly also covers implementations
        // that hand us a defensive ItemStack copy. updateInventory() forces
        // the client to actually re-render the tooltip -- an in-place
        // ItemMeta/lore change doesn't reliably trigger a resend on its own.
        // Set a distinct stack so the newly rebuilt contents lore is always
        // sent to the client, even on implementations that cache the old
        // ItemStack instance.
        player.getInventory().setItemInOffHand(equipped.item().clone());
        player.updateInventory();
        sendStorageActionBar(player, equipped, storedBefore);
        return leftovers;
    }

    /** Shows an accurate total only when this collection event added an item. */
    private void sendStorageActionBar(Player player, BackpackManager.EquippedBackpack equipped, long storedBefore) {
        BackpackData updated = manager.readData(equipped.item());
        if (updated == null) {
            return;
        }
        long stored = BackpackManager.storedItemCount(updated.contents());
        if (stored <= storedBefore) {
            return;
        }
        player.sendActionBar(messages.get(player, "backpack.store-action-bar",
                "backpack_name", manager.displayName(equipped.tier()),
                "tier", manager.tierLabel(equipped.tier()),
                "backpack_level", String.valueOf(updated.level()),
                "used_storage", String.format("%,d", stored),
                "storage", String.format("%,d", manager.itemCapacityForLevel(updated.level()))));
    }

    /** Handles drops produced directly by Vertex's mob-stack peeling path. */
    public List<ItemStack> routePlayerMobDrops(Player player, List<ItemStack> drops) {
        if (!manager.autoStoresMobDrops()) {
            return drops;
        }
        BackpackManager.EquippedBackpack equipped = manager.equippedBackpack(player);
        return equipped == null ? drops : route(player, equipped, drops);
    }
}
