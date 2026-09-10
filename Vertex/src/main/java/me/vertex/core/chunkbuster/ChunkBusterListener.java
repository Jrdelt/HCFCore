package me.vertex.core.chunkbuster;

import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuRegistry;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Right-clicking a block with a Chunk Buster item opens the confirmation
 * GUI (see {@link ChunkBusterMenu}) -- nothing is consumed or removed
 * here, only validated, mirroring {@code WandListener}'s interact-then-act
 * split. Also enforces the temporary block-place/break lock over any
 * chunk with an operation currently in progress.
 */
public final class ChunkBusterListener implements Listener {

    private final ChunkBusterManager manager;
    private final Messages messages;
    private final MenuRegistry menus;

    public ChunkBusterListener(ChunkBusterManager manager, Messages messages, MenuRegistry menus) {
        this.manager = manager;
        this.messages = messages;
        this.menus = menus;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) {
            return;
        }
        ItemStack item = event.getItem();
        ChunkBusterType type = manager.typeOf(item);
        if (type == null) {
            return;
        }
        Player player = event.getPlayer();
        event.setCancelled(true);

        // A right-click that hits nothing (open air, or out of the item's
        // vanilla interact range) has no block to anchor the operation to --
        // tell the player rather than silently doing nothing, which reads as
        // the item being broken.
        Block clicked = event.getClickedBlock();
        if (action == Action.RIGHT_CLICK_AIR || clicked == null) {
            player.sendMessage(messages.get(player, "chunkbuster.need-block-target"));
            return;
        }
        Location target = clicked.getLocation();

        ChunkBusterManager.UseResult result = manager.validate(player, target, type);
        if (result != ChunkBusterManager.UseResult.OK) {
            player.sendMessage(messages.get(player, messageKeyFor(result)));
            return;
        }
        boolean hasSpawners = manager.hasTrackedSpawners(target);
        ChunkBusterMenu.open(player, manager, messages, menus, type, target, hasSpawners);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (manager.isLocked(event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(messages.get(event.getPlayer(), "chunkbuster.area-locked"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (manager.isLocked(event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(messages.get(event.getPlayer(), "chunkbuster.area-locked"));
        }
    }

    static String messageKeyFor(ChunkBusterManager.UseResult result) {
        return switch (result) {
            case TYPE_DISABLED -> "chunkbuster.type-disabled";
            case IN_COMBAT -> "chunkbuster.in-combat";
            case BLOCKED_ZONE -> "chunkbuster.blocked-zone";
            case NOT_YOUR_CLAIM -> "chunkbuster.not-your-claim";
            case NO_ROLE_PERMISSION -> "chunkbuster.no-permission";
            case AREA_BUSY -> "chunkbuster.area-busy";
            case ITEM_MISSING -> "chunkbuster.item-missing";
            case PERSIST_FAILED -> "chunkbuster.persist-failed";
            case OK -> "chunkbuster.started";
        };
    }
}
