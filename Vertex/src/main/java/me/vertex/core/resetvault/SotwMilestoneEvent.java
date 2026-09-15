package me.vertex.core.resetvault;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Custom event fired when an SOTW orchestrator milestone is reached.
 */
public final class SotwMilestoneEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final String milestoneName;

    public SotwMilestoneEvent(String milestoneName) {
        this.milestoneName = milestoneName;
    }

    public String milestoneName() {
        return milestoneName;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
