package me.vertex.core.shield;

import dev.kitteh.factions.Faction;
import dev.kitteh.factions.event.FactionAutoDisbandEvent;
import dev.kitteh.factions.event.FactionCreateEvent;
import dev.kitteh.factions.event.FactionDisbandEvent;
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
    public void onCreate(FactionCreateEvent event) {
        Faction faction = event.getFaction();
        if (faction != null) {
            shield.onFactionCreated(faction.id());
        }
    }

    @EventHandler
    public void onDisband(FactionDisbandEvent event) {
        Faction faction = event.getFaction();
        if (faction != null) {
            shield.onFactionDisbanded(faction.id());
        }
    }

    @EventHandler
    public void onAutoDisband(FactionAutoDisbandEvent event) {
        Faction faction = event.getFaction();
        if (faction != null) {
            shield.onFactionDisbanded(faction.id());
        }
    }
}
