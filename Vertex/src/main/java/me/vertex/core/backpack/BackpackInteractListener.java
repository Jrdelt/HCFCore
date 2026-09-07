package me.vertex.core.backpack;

import me.vertex.core.lang.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Sneak + Right Click in the air with a Backpack in the offhand opens {@link BackpackMenu}. */
public final class BackpackInteractListener implements Listener {

    private final BackpackManager manager;
    private final Messages messages;
    /** Both hands can report one physical click; open only once per tick. */
    private final Set<UUID> opening = ConcurrentHashMap.newKeySet();
    /** Per-player diagnostics, enabled temporarily through /backpack debug. */
    private final Set<UUID> debugging = ConcurrentHashMap.newKeySet();

    public BackpackInteractListener(BackpackManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    public boolean toggleDebug(UUID playerId) {
        if (debugging.remove(playerId)) {
            return false;
        }
        debugging.add(playerId);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        trace(player, "event action=" + event.getAction() + " hand=" + event.getHand()
                + " cancelled=" + event.isCancelled() + " sneaking=" + player.isSneaking()
                + " event-item=" + itemSummary(event.getItem())
                + " offhand=" + itemSummary(player.getInventory().getItemInOffHand()));
        if (!manager.isEnabled()) {
            trace(player, "ignored: backpacks.yml enabled is false");
            return;
        }
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            trace(player, "ignored: not a right-click action");
            return;
        }
        if (!player.isSneaking()) {
            trace(player, "ignored: player is not sneaking");
            return;
        }
        // Paper may report this click as HAND or OFF_HAND depending on what
        // is in the main hand. Always obtain the actual offhand stack rather
        // than event.getItem(), which can be an event copy on some versions.
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (!manager.isBackpack(offhand)) {
            trace(player, "ignored: offhand item has no Vertex Backpack marker");
            return;
        }
        if (!manager.isSingleBackpack(offhand)) {
            event.setCancelled(true);
            trace(player, "rejected: Backpack stack amount is " + offhand.getAmount());
            player.sendMessage(messages.get(player, "backpack.must-be-single"));
            return;
        }

        BackpackData data = manager.readData(offhand);
        BackpackTier tier = data == null ? null : manager.getTier(data.tierId());
        if (data == null || tier == null) {
            trace(player, "rejected: Backpack tier is missing or invalid");
            player.sendMessage(messages.get(player, "backpack.unknown-tier"));
            return;
        }

        if (!opening.add(player.getUniqueId())) {
            trace(player, "ignored: this click was already handled this tick");
            return;
        }
        event.setCancelled(true);
        trace(player, "accepted: opening Backpack menu next tick");
        // Let every plugin finish the interaction event before opening the
        // inventory. This avoids a later handler restoring/cancelling the
        // use packet over the GUI on Paper/client combinations.
        player.getServer().getScheduler().runTask(manager.plugin(), () -> {
            try {
                if (!player.isOnline()) {
                    return;
                }
                ItemStack current = player.getInventory().getItemInOffHand();
                BackpackData currentData = manager.readData(current);
                BackpackTier currentTier = currentData == null ? null : manager.getTier(currentData.tierId());
                if (currentData == null || currentTier == null || !manager.isSingleBackpack(current)) {
                    trace(player, "open aborted: offhand Backpack changed before the next tick");
                    return;
                }
                manager.ensureInstanceId(current);
                BackpackMenu.open(player, manager, messages, currentTier, currentData, current);
                trace(player, "open success: menu request sent");
            } finally {
                opening.remove(player.getUniqueId());
            }
        });
    }

    private void trace(Player player, String detail) {
        if (!debugging.contains(player.getUniqueId())) {
            return;
        }
        player.sendMessage(messages.get(player, "backpack.debug-trace", "detail", detail));
        manager.plugin().getLogger().info("[Backpack Debug] " + player.getName() + ": " + detail);
    }

    private static String itemSummary(ItemStack item) {
        return item == null || item.isEmpty() ? "empty" : item.getType() + "x" + item.getAmount();
    }
}
