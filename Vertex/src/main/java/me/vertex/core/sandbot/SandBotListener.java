package me.vertex.core.sandbot;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Detects a Sand Bot item being placed on a qualifying trigger block. On
 * success the placement itself is cancelled -- the head never becomes a
 * real block -- and a Citizens NPC takes its place instead.
 */
public final class SandBotListener implements Listener {

    private final SandBotManager manager;
    private final Messages messages;

    public SandBotListener(SandBotManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!manager.isEnabled() || !manager.isGiveItem(event.getItemInHand())) {
            return;
        }

        Player player = event.getPlayer();
        Block placed = event.getBlockPlaced();
        Block below = placed.getRelative(0, -1, 0);

        event.setCancelled(true);

        if (!SandBotManager.isTriggerMaterial(below.getType())) {
            player.sendMessage(messages.get(player, "sandbot.invalid-surface"));
            return;
        }

        int factionId = FactionsHook.getFactionId(player);
        if (factionId == FactionsHook.NO_FACTION
                || FactionsHook.getClaimFactionId(placed.getLocation()) != factionId) {
            player.sendMessage(messages.get(player, "sandbot.not-own-claim"));
            return;
        }

        Location spawnLocation = placed.getLocation().add(0.5, 0, 0.5);
        spawnLocation.setYaw(player.getLocation().getYaw());
        if (!manager.spawn(player, below, spawnLocation)) {
            player.sendMessage(messages.get(player, "sandbot.spawn-failed"));
            return;
        }

        // The item placement is cancelled above, so it's still in the
        // player's hand -- consume exactly one now that the bot actually
        // spawned. event.getItemInHand() is a snapshot, not a live
        // reference, so the live stack has to be re-fetched to mutate it.
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (heldItem.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            heldItem.setAmount(heldItem.getAmount() - 1);
        }

        player.sendMessage(messages.get(player, "sandbot.spawned"));
    }
}
