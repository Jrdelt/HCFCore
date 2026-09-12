package me.vertex.core.factions.event;

import me.vertex.core.factions.FactionData;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired before a faction releases all of its claims. */
public final class FactionUnclaimAllEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final FactionData faction; private final Player actor;
    public FactionUnclaimAllEvent(FactionData faction, Player actor) { this.faction=faction; this.actor=actor; }
    public FactionData faction() { return faction; } public Player actor() { return actor; }
    @Override public HandlerList getHandlers() { return HANDLERS; } public static HandlerList getHandlerList() { return HANDLERS; }
}
