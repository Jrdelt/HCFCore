package me.vertex.core.collector;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.faction.RallyManager;
import me.vertex.core.lang.Messages;
import me.vertex.core.staff.StaffManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Iterator;
import java.util.List;

/**
 * The full lifecycle of a Chunk Collector: tagging mob-kill drops so they
 * (and only they) are eligible for pickup, real-time collection as those
 * drops actually spawn, placement/breaking rules gated on faction claim
 * ownership (mirroring the spawner system), and blocking hoppers from
 * touching a collector or sitting near one.
 */
public final class ChunkCollectorListener implements Listener {

    private final ChunkCollectorManager manager;
    private final StaffManager staffManager;
    private final Messages messages;
    private final NamespacedKey mobDropKey;
    private final RallyManager rolePermissions;

    public ChunkCollectorListener(Plugin plugin, ChunkCollectorManager manager, StaffManager staffManager, Messages messages, RallyManager rolePermissions) {
        this.manager = manager;
        this.staffManager = staffManager;
        this.messages = messages;
        this.mobDropKey = new NamespacedKey(plugin, "mob_drop");
        this.rolePermissions = rolePermissions;
    }

    /**
     * Tags every drop from a non-player death and absorbs it immediately
     * where possible. Immediate collection prevents drops from burning in
     * lava/fire before {@link ItemSpawnEvent} can see them. Player deaths,
     * manually-dropped items, and block-break drops never reach this event.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player) {
            return;
        }
        for (Iterator<ItemStack> drops = event.getDrops().iterator(); drops.hasNext();) {
            ItemStack drop = drops.next();
            if (drop == null || drop.getType() == Material.AIR) {
                continue;
            }
            org.bukkit.inventory.meta.ItemMeta meta = drop.getItemMeta();
            if (meta == null) {
                continue;
            }
            meta.getPersistentDataContainer().set(mobDropKey, PersistentDataType.BYTE, (byte) 1);
            drop.setItemMeta(meta);
            if (!manager.isEnabled()) {
                continue;
            }
            int absorbed = absorb(drop, event.getEntity().getLocation());
            if (absorbed >= drop.getAmount()) {
                drops.remove();
            } else if (absorbed > 0) {
                drop.setAmount(drop.getAmount() - absorbed);
            }
        }
    }

    /**
     * Real-time collection: a tagged item that just spawned strictly above
     * a same-chunk collector is vacuumed up immediately instead of ever
     * touching the ground. Never triggered for EXP orbs -- those aren't
     * ItemStacks and never fire this event in the first place.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!manager.isEnabled()) {
            return;
        }
        tryCollect(event.getEntity());
    }

    /** @return true if the item was fully absorbed (and thus removed). */
    boolean tryCollect(Item item) {
        ItemStack stack = item.getItemStack();
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(mobDropKey, PersistentDataType.BYTE)) {
            return false;
        }
        int absorbed = absorb(stack, item.getLocation());
        if (absorbed <= 0) {
            return false;
        }
        int remaining = stack.getAmount() - absorbed;
        if (remaining <= 0) {
            item.remove();
            return true;
        }
        stack.setAmount(remaining);
        item.setItemStack(stack);
        return false;
    }

    /** @return the amount absorbed by the highest eligible collector, if any. */
    private int absorb(ItemStack stack, Location itemLocation) {
        List<Location> candidates = manager.collectorsBelow(itemLocation.getChunk(), itemLocation.getY());
        for (Location collectorLocation : candidates) {
            ChunkCollectorData data = manager.readData(collectorLocation);
            if (data == null) {
                continue;
            }
            long capacity = manager.capacityFor(data.upgradeTier());
            long room = capacity - data.totalStored();
            if (room <= 0) {
                continue;
            }
            long toAbsorb = Math.min(room, stack.getAmount());
            data.setStored(stack.getType(), data.stored(stack.getType()) + toAbsorb);
            manager.writeData(collectorLocation, data);
            return (int) toAbsorb;
        }
        return 0;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!manager.isCollectorItem(event.getItemInHand())) {
            return;
        }
        Player player = event.getPlayer();
        Location location = event.getBlock().getLocation();

        String claimTag = FactionsHook.getClaimFactionTag(location);
        String playerTag = FactionsHook.getFactionTag(player);
        boolean staffBuild = staffManager.isStaffBuild(player.getUniqueId());
        if (!staffBuild && (claimTag == null || !claimTag.equalsIgnoreCase(playerTag))) {
            event.setCancelled(true);
            player.sendMessage(messages.get(player, "collector.claim-only"));
            return;
        }
        if (!staffBuild && manager.countInChunk(event.getBlock().getChunk()) >= manager.maxPerChunk()) {
            event.setCancelled(true);
            player.sendMessage(messages.get(player, "collector.chunk-limit"));
            return;
        }
        if (!staffBuild && manager.countForOwner(player.getUniqueId()) >= manager.maxPerPlayer()) {
            event.setCancelled(true);
            player.sendMessage(messages.get(player, "collector.player-limit"));
            return;
        }

        ChunkCollectorData data = manager.readItemData(event.getItemInHand(), player.getUniqueId(),
                claimTag != null ? claimTag : playerTag);
        manager.register(location, data);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !manager.isTracked(block.getLocation())) {
            return;
        }
        ChunkCollectorData data = manager.readData(block.getLocation());
        if (data == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!staffManager.isStaffBuild(player.getUniqueId())) {
            String claimTag = FactionsHook.getClaimFactionTag(block.getLocation());
            String playerTag = FactionsHook.getFactionTag(player);
            if (claimTag == null || !claimTag.equalsIgnoreCase(playerTag)) {
                player.sendMessage(messages.get(player, "collector.cannot-access"));
                return;
            }
            if (!rolePermissions.canUse(player, "collector-open")) { player.sendMessage(messages.get(player, "factions.role-permission-denied")); return; }
        }
        event.setCancelled(true);
        ChunkCollectorMenu.open(player, manager, messages, block.getLocation(), data);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Location location = block.getLocation();
        if (!manager.isTracked(location)) {
            return;
        }
        ChunkCollectorData data = manager.readData(location);
        if (data == null) {
            return;
        }
        Player player = event.getPlayer();
        boolean staffBuild = staffManager.isStaffBuild(player.getUniqueId());

        if (!staffBuild) {
            String claimTag = FactionsHook.getClaimFactionTag(location);
            String playerTag = FactionsHook.getFactionTag(player);
            if (claimTag == null || !claimTag.equalsIgnoreCase(playerTag)) {
                event.setCancelled(true);
                player.sendMessage(messages.get(player, "collector.not-your-claim"));
                return;
            }
            if (!rolePermissions.canUse(player, "collector-break")) { event.setCancelled(true); player.sendMessage(messages.get(player, "factions.role-permission-denied")); return; }
            if (manager.isSilkTouchRequired()
                    && player.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.SILK_TOUCH) <= 0) {
                event.setCancelled(true);
                player.sendMessage(messages.get(player, "collector.silk-touch-required"));
                return;
            }
        }

        event.setDropItems(false);
        block.getWorld().dropItemNaturally(location.clone().add(0.5, 0.5, 0.5),
                manager.createCollectorItem(manager.displayName(), data));
        manager.unregister(location);
    }

    /** Hoppers can't be placed within hopper-block-radius of any tracked collector. */
    @EventHandler(ignoreCancelled = true)
    public void onHopperPlace(BlockPlaceEvent event) {
        if (event.getBlock().getType() != Material.HOPPER) {
            return;
        }
        int radius = manager.hopperBlockRadius();
        if (radius <= 0) {
            return;
        }
        if (!manager.collectorsNear(event.getBlock().getLocation(), radius).isEmpty()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(messages.get(event.getPlayer(), "collector.hopper-blocked"));
        }
    }

    /** A hopper touching a collector block directly can't push into or pull out of it. */
    @EventHandler(ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        if (isCollectorInventory(event.getSource()) || isCollectorInventory(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    private boolean isCollectorInventory(org.bukkit.inventory.Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (!(holder instanceof ShulkerBox shulkerBox)) {
            return false;
        }
        return manager.isTracked(shulkerBox.getLocation());
    }

    /**
     * Fallback for anything the real-time {@link #onItemSpawn} hook missed
     * (an item that merged into an existing untagged stack, one that
     * existed before a collector was placed nearby, etc.) -- sweeps every
     * tracked collector's own chunk (skipping unloaded ones) for tagged
     * items still sitting on the ground.
     */
    public void scanForMissedItems() {
        if (!manager.isEnabled()) {
            return;
        }
        for (Location location : manager.allLocations()) {
            if (location.getWorld() == null || !location.getWorld().isChunkLoaded(
                    location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                continue;
            }
            for (org.bukkit.entity.Entity entity : location.getChunk().getEntities()) {
                if (entity instanceof Item item && item.isValid()) {
                    tryCollect(item);
                }
            }
        }
    }
}
