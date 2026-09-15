package me.vertex.core.enchant;

import me.vertex.core.enchant.listener.RuneListener;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Auto-Incineration's second trigger path (identification is the first,
 * handled directly in {@link RuneListener#identify}): a standalone rune
 * legitimately obtained through trading/pickup while Auto is active is
 * incinerated immediately instead of entering the inventory. Purely
 * event-driven -- never polls/scans players on a timer.
 */
public final class AutoIncinerationListener implements Listener {

    private final EnchantManager manager;
    private final AutoIncineration autoIncineration;
    private final IncinerationService incineration;
    private final AutoIncinerationNotifier notifier;

    public AutoIncinerationListener(EnchantManager manager, AutoIncineration autoIncineration,
            IncinerationService incineration, AutoIncinerationNotifier notifier) {
        this.manager = manager;
        this.autoIncineration = autoIncineration;
        this.incineration = incineration;
        this.notifier = notifier;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack item = event.getItem().getItemStack();
        if (!autoIncineration.isActive(player) || !autoIncineration.isEligibleAndUnprotected(item, manager, player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        event.getItem().remove();
        IncinerationService.Outcome outcome = incineration.incinerateRolledDirect(player, item);
        if (outcome.success()) {
            notifier.notifyIncinerated(player, outcome.runeName(), outcome.level(), outcome.currency(), outcome.rewardAmount());
        }
    }
}
