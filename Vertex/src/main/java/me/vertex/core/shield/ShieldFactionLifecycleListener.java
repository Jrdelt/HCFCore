package me.vertex.core.shield;

import me.vertex.core.factions.event.FactionLifecycleEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Starts and clears the new-faction Shield eligibility wait as factions are
 * created and disbanded. A disbanded-and-recreated faction gets a fresh
 * wait because {@link ShieldManager#onFactionDisbanded} removes its row
 * entirely, and {@link ShieldManager#onFactionCreated} always writes a
 * brand new deadline computed from "now".
 */
public final class ShieldFactionLifecycleListener implements Listener {

    private final ShieldManager shield;

    public ShieldFactionLifecycleListener(ShieldManager shield) {
        this.shield = shield;
    }

    @EventHandler
    public void onLifecycle(FactionLifecycleEvent event) {
        if (event.action() == FactionLifecycleEvent.Action.CREATE) shield.onFactionCreated(event.faction().id());
        if (event.action() == FactionLifecycleEvent.Action.DISBAND) shield.onFactionDisbanded(event.faction().id());
    }
}
