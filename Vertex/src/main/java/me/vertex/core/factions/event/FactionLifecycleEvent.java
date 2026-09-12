package me.vertex.core.factions.event;

import me.vertex.core.factions.FactionData;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Vertex-native faction creation, rename, and disband lifecycle event. */
public final class FactionLifecycleEvent extends Event {
    public enum Action { CREATE, RENAME, DISBAND }
    private static final HandlerList HANDLERS = new HandlerList();
    private final Action action; private final FactionData faction; private final Player actor; private final String previousTag;
    public FactionLifecycleEvent(Action action, FactionData faction, Player actor, String previousTag) { this.action=action; this.faction=faction; this.actor=actor; this.previousTag=previousTag; }
    public Action action() { return action; } public FactionData faction() { return faction; } public Player actor() { return actor; } public String previousTag() { return previousTag; }
    @Override public HandlerList getHandlers() { return HANDLERS; } public static HandlerList getHandlerList() { return HANDLERS; }
}
