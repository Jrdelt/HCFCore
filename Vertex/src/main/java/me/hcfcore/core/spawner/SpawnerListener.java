package me.hcfcore.core.spawner;

import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.staff.StaffManager;
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
import org.bukkit.inventory.ItemStack;
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

    public SpawnerListener(SpawnerManager spawnerManager, StaffManager staffManager, Messages messages) {
        this.spawnerManager = spawnerManager;
        this.staffManager = staffManager;
        this.messages = messages;
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
        spawnerManager.place(location, mobType, claimTag != null ? claimTag : playerTag);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
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

        ItemStack handItem = event.getItem();
        EntityType handType = SpawnerManager.readSpawnedType(handItem);
        if (handType == null || handType != data.mobType()) {
            SpawnerManagementMenu.open(player, spawnerManager, messages, location, data);
            return;
        }

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
            if (spawnerManager.isSilkTouchRequired()
                    && event.getPlayer().getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.SILK_TOUCH) <= 0) {
                event.setCancelled(true);
                player.sendMessage(messages.get(player, "spawner.silk-touch-required"));
                return;
            }
        }

        event.setDropItems(false);
        int stackSize = data.stackSize();
        EntityType mobType = data.mobType();
        net.kyori.adventure.text.Component displayName = displayNameFor(mobType);

        int dropped = spawnerManager.breakMode() == SpawnerManager.BreakMode.DROP_ALL ? stackSize : 1;
        for (int i = 0; i < dropped; i++) {
            block.getWorld().dropItemNaturally(location.clone().add(0.5, 0.5, 0.5),
                    SpawnerManager.createSpawnerItem(mobType, displayName));
        }

        if (dropped >= stackSize) {
            spawnerManager.remove(location);
        } else {
            spawnerManager.decreaseStack(location, dropped);
        }
    }

    private net.kyori.adventure.text.Component displayNameFor(EntityType type) {
        SpawnerManager.MobConfig config = spawnerManager.getMobConfig(type);
        return me.hcfcore.core.lang.MessageFormatter.deserialize(
                config != null ? config.displayName() : type.name());
    }
}
