package me.vertex.core.spawner;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.faction.RallyManager;
import me.vertex.core.lang.Messages;
import me.vertex.core.staff.StaffManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;

/**
 * The physical side of a stacked spawner: placement restricted to your own
 * faction's claimed land, right-click to stack/withdraw, and break rules
 * (Silk Touch, faction ownership, drop mode). Mob-related behavior
 * (targeting AI, custom drops) lives in SpawnerMobListener; claim
 * lifecycle (unclaim/disband cleanup) lives in SpawnerClaimListener.
 */
public final class SpawnerListener implements Listener {

    private final SpawnerManager spawnerManager;
    private final StaffManager staffManager;
    private final Messages messages;
    private final RallyManager rolePermissions;

    public SpawnerListener(SpawnerManager spawnerManager, StaffManager staffManager, Messages messages, RallyManager rolePermissions) {
        this.spawnerManager = spawnerManager;
        this.staffManager = staffManager;
        this.messages = messages;
        this.rolePermissions = rolePermissions;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getBlock().getType() != Material.SPAWNER) {
            return;
        }
        Player player = event.getPlayer();
        Location location = event.getBlock().getLocation();
        EntityType mobType = SpawnerManager.readSpawnedType(event.getItemInHand());
        if (mobType == null || spawnerManager.getMobConfig(mobType) == null) {
            event.setCancelled(true);
            return;
        }
        String claimTag = FactionsHook.getClaimFactionTag(location);
        String playerTag = FactionsHook.getFactionTag(player);
        boolean staffBuild = staffManager.isStaffBuild(player.getUniqueId());
        if (!staffBuild && (claimTag == null || !claimTag.equalsIgnoreCase(playerTag))) {
            event.setCancelled(true);
            player.sendMessage(messages.get(player, "spawner.claim-only"));
            return;
        }
        if (!staffBuild && !rolePermissions.canUse(player, "spawner-add")) { event.setCancelled(true); player.sendMessage(messages.get(player, "factions.role-permission-denied")); return; }
        spawnerManager.place(location, mobType, claimTag != null ? claimTag : playerTag);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.SPAWNER) {
            return;
        }
        Location location = block.getLocation();
        SpawnerData data = spawnerManager.get(location);
        if (data == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();

        if (!staffManager.isStaffBuild(player.getUniqueId())) {
            String claimTag = FactionsHook.getClaimFactionTag(location);
            String playerTag = FactionsHook.getFactionTag(player);
            if (claimTag == null || !claimTag.equalsIgnoreCase(playerTag)) {
                player.sendMessage(messages.get(player, "spawner.not-your-claim"));
                return;
            }
        }

        ItemStack handItem = event.getItem();
        EntityType handType = SpawnerManager.readSpawnedType(handItem);
        if (handType == null || handType != data.mobType()) {
            SpawnerManagementMenu.open(player, spawnerManager, messages, location, data);
            return;
        }
        if (!staffManager.isStaffBuild(player.getUniqueId()) && !rolePermissions.canUse(player, "spawner-add")) { player.sendMessage(messages.get(player, "factions.role-permission-denied")); return; }

        int originalSize = data.stackSize();
        int deposited = player.isSneaking() ? countMatching(player, handType) : 1;
        int newSize = spawnerManager.increaseStack(location, deposited);
        int actuallyAdded = newSize - originalSize;
        if (actuallyAdded <= 0) {
            player.sendMessage(messages.get(player, "spawner.stack-full"));
            return;
        }
        removeMatching(player, handType, actuallyAdded);
        player.sendMessage(messages.get(player, "spawner.stacked",
                "amount", String.valueOf(actuallyAdded), "size", String.valueOf(newSize)));
    }

    private static int countMatching(Player player, EntityType type) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (SpawnerManager.readSpawnedType(item) == type) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private static void removeMatching(Player player, EntityType type, int amount) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents();
        int remaining = amount;
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack item = contents[slot];
            if (SpawnerManager.readSpawnedType(item) != type) {
                continue;
            }
            int take = Math.min(remaining, item.getAmount());
            if (take >= item.getAmount()) {
                inventory.setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - take);
                inventory.setItem(slot, item);
            }
            remaining -= take;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.SPAWNER) {
            return;
        }
        Location location = block.getLocation();
        SpawnerData data = spawnerManager.get(location);
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
                player.sendMessage(messages.get(player, "spawner.not-your-claim"));
                return;
            }
            if (!rolePermissions.canUse(player, "spawner-remove")) { event.setCancelled(true); player.sendMessage(messages.get(player, "factions.role-permission-denied")); return; }
            if (spawnerManager.isSilkTouchRequired()
                    && event.getPlayer().getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.SILK_TOUCH) <= 0) {
                event.setCancelled(true);
                player.sendMessage(messages.get(player, "spawner.silk-touch-required"));
                return;
            }
        }

        int stackSize = data.stackSize();
        EntityType mobType = data.mobType();
        net.kyori.adventure.text.Component displayName = displayNameFor(mobType);

        // A decrement must leave the physical spawner in place. Letting the
        // BlockBreakEvent complete here would turn the remaining tracked
        // stack into an invisible database/PDC record with no block.
        if (spawnerManager.breakMode() == SpawnerManager.BreakMode.DECREMENT && stackSize > 1) {
            event.setCancelled(true);
            block.getWorld().dropItemNaturally(location.clone().add(0.5, 0.5, 0.5),
                    SpawnerManager.createSpawnerItem(mobType, displayName));
            spawnerManager.decreaseStack(location, 1);
            return;
        }

        event.setDropItems(false);
        int dropped = stackSize;
        for (int i = 0; i < dropped; i++) {
            block.getWorld().dropItemNaturally(location.clone().add(0.5, 0.5, 0.5),
                    SpawnerManager.createSpawnerItem(mobType, displayName));
        }

        spawnerManager.remove(location);
    }

    /** Restores interrupted spawner writes and prunes stale SQL rows lazily as chunks load. */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        spawnerManager.reconcileChunk(event.getChunk());
    }

    private net.kyori.adventure.text.Component displayNameFor(EntityType type) {
        SpawnerManager.MobConfig config = spawnerManager.getMobConfig(type);
        return me.vertex.core.lang.MessageFormatter.deserialize(
                config != null ? config.displayName() : type.name());
    }
}
