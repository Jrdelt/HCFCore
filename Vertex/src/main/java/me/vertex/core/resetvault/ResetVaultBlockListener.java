package me.vertex.core.resetvault;

import me.vertex.core.lang.Messages;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Handles physical block events for registered Reset Vault access points.
 * Enforces removal restrictions, explosion immunity, piston protection,
 * and intercepts interactions so vanilla shulker storage never opens.
 */
public final class ResetVaultBlockListener implements Listener {

    private final ResetVaultManager manager;
    private final Messages messages;
    private final NamespacedKey blockMarkerKey;

    public ResetVaultBlockListener(ResetVaultManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
        this.blockMarkerKey = new NamespacedKey(manager.plugin(), "rv_access_block");
    }

    public ItemStack createAccessBlockItem() {
        ItemStack item = new ItemStack(manager.blockMaterial(), 1);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(blockMarkerKey, PersistentDataType.BYTE, (byte) 1);
        meta.displayName(messages.getGui(org.bukkit.Bukkit.getConsoleSender(), "reset-vault.block.item-name"));
        meta.lore(messages.getGuiList(org.bukkit.Bukkit.getConsoleSender(), "reset-vault.block.item-lore"));
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isAccessBlockItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(blockMarkerKey, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (!isAccessBlockItem(item)) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.hasPermission("vertex.reset.admin")) {
            event.setCancelled(true);
            player.sendMessage(messages.get(player, "general.no-permission"));
            return;
        }
        
        Location loc = event.getBlockPlaced().getLocation();

        manager.registerBlock(loc).thenAccept(block -> {
            player.sendMessage(messages.get(player, "reset-vault.block.placed", "id", String.valueOf(block.id())));
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(org.bukkit.event.entity.EntityChangeBlockEvent event) {
        if (manager.isRegisteredBlock(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        ResetVaultAccessBlock accessBlock = manager.getAccessBlock(block.getLocation());
        if (accessBlock == null) {
            return;
        }

        // Intercept right click so shulker never opens
        event.setCancelled(true);
        Player player = event.getPlayer();

        if (manager.phase() == ResetVaultPhase.CLOSED) {
            player.sendMessage(messages.get(player, "reset-vault.phase.closed"));
            return;
        }
        if (manager.phase() == ResetVaultPhase.BACKUP_RUNNING) {
            player.sendMessage(messages.get(player, "reset-vault.phase.backup-running"));
            return;
        }
        if (manager.phase() == ResetVaultPhase.RECOVERY_LOCKED) {
            player.sendMessage(messages.get(player, "reset-vault.phase.recovery-locked"));
            return;
        }

        ResetVaultMenu.open(player, manager, messages, 0, accessBlock.id());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        ResetVaultAccessBlock accessBlock = manager.getAccessBlock(block.getLocation());
        if (accessBlock == null) {
            return;
        }

        Player player = event.getPlayer();

        // Must be in Creative mode AND have permission
        if (player.getGameMode() != GameMode.CREATIVE || !player.hasPermission("vertex.reset.block.break")) {
            event.setCancelled(true);
            player.sendMessage(messages.get(player, "reset-vault.block.break-denied"));
            return;
        }

        // Cannot break if a session is currently active on this block
        if (manager.isSessionActiveOnBlock(accessBlock.id())) {
            event.setCancelled(true);
            player.sendMessage(messages.get(player, "reset-vault.block.break-session-active"));
            return;
        }

        event.setDropItems(false);
        manager.unregisterBlock(accessBlock);
        player.sendMessage(messages.get(player, "reset-vault.block.removed", "id", String.valueOf(accessBlock.id())));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> manager.isRegisteredBlock(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> manager.isRegisteredBlock(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            if (manager.isRegisteredBlock(block.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            if (manager.isRegisteredBlock(block.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (manager.isRegisteredBlock(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }
}
