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
        manager.debug(player, "placement", "x", String.valueOf(placed.getX()), "y", String.valueOf(placed.getY()),
                "z", String.valueOf(placed.getZ()), "material", below.getType().name());

        int factionId = FactionsHook.getFactionId(player);
        if (factionId == FactionsHook.NO_FACTION
                || FactionsHook.getClaimFactionId(placed.getLocation()) != factionId) {
            manager.debug(player, "placement-denied");
            player.sendMessage(messages.get(player, "sandbot.not-own-claim"));
            return;
        }

        Location spawnLocation = placed.getLocation().add(0.5, 0, 0.5);
        spawnLocation.setYaw(player.getLocation().getYaw());
        if (!manager.spawn(player, below, spawnLocation)) {
            manager.debug(player, "spawn-failed");
            player.sendMessage(messages.get(player, "sandbot.spawn-failed"));
            return;
        }

        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (heldItem.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            heldItem.setAmount(heldItem.getAmount() - 1);
        }

        player.sendMessage(messages.get(player, "sandbot.spawned"));
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
                manager.persistState();
                player.closeInventory();
                manager.openControlPanel(player, session);
            } else if (event.getSlot() == 15) {
                manager.destroy(session);
                player.closeInventory();
                player.sendMessage(messages.get(player, "sandbot.despawned"));
            }
        }
    }
}
