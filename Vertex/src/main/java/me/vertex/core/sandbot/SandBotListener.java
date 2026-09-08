package me.vertex.core.sandbot;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import de.oliver.fancynpcs.api.events.NpcInteractEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;


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
        manager.debug(player, "Placement intercepted at " + placed.getX() + ", " + placed.getY() + ", "
                + placed.getZ() + "; floor material is " + below.getType() + ".");

        int factionId = FactionsHook.getFactionId(player);
        if (factionId == FactionsHook.NO_FACTION
                || FactionsHook.getClaimFactionId(placed.getLocation()) != factionId) {
            manager.debug(player, "Placement denied: this block is not claimed by your faction.");
            Component msg = messages != null ? messages.get(player, "sandbot.not-own-claim") : Component.text("§cYou cannot place a Sand Bot outside your claim.");
            player.sendMessage(msg);
            return;
        }

        Location spawnLocation = placed.getLocation().add(0.5, 0, 0.5);
        spawnLocation.setYaw(player.getLocation().getYaw());
        if (!manager.spawn(player, below, spawnLocation)) {
            manager.debug(player, "Spawn failed: FancyNPCs is unavailable, still loading, or your faction was not resolved.");
            Component msg = messages != null ? messages.get(player, "sandbot.spawn-failed") : Component.text("§cFailed to spawn Sand Bot.");
            player.sendMessage(msg);
            return;
        }

        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (heldItem.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            heldItem.setAmount(heldItem.getAmount() - 1);
        }

        Component msg = messages != null ? messages.get(player, "sandbot.spawned") : Component.text("§aSand Bot deployed successfully.");
        player.sendMessage(msg);
    }

    @EventHandler
    public void onNpcInteract(NpcInteractEvent event) {
        SandBotManager.SandBotSession session = manager.getSessionByNpcId(event.getNpc().getData().getId());
        if (session == null) {
            return;
        }
        event.setCancelled(true);
        manager.handleNpcClick(session.npcId, event.getPlayer());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!(event.getView().getTopInventory().getHolder() instanceof SandBotManager.ControlPanelHolder holder)) {
            return;
        }
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }

        SandBotManager.SandBotSession session = manager.getSessionByNpcId(holder.npcId());
        if (session != null && (player.getUniqueId().equals(session.ownerId)
                || player.hasPermission("vertex.sandbot.admin"))) {
            if (event.getSlot() == 11) {
                session.active = !session.active;
                player.closeInventory();
                manager.openControlPanel(player, session);
            } else if (event.getSlot() == 15) {
                manager.destroy(session);
                player.closeInventory();
                player.sendMessage("§aSand Bot despawned.");
            }
        }
    }
}
