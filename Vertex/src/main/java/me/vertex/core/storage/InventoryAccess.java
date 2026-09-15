package me.vertex.core.storage;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Shared admission boundary for player inventory mutations and cross-server snapshots. */
public final class InventoryAccess {
    private InventoryAccess() { }
    private static final java.util.Set<Player> reservations=java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static boolean reserved(Player player){return player!=null&&reservations.contains(player);}
    public static boolean reserve(Plugin plugin,Player player){return ready(plugin,player)&&reservations.add(player);}
    public static void release(Player player){reservations.remove(player);}

    /**
     * Returns whether an ordinary plugin action may mutate this player's
     * inventory. A delivered item remains transaction-owned until its
     * database acknowledgement completes, so unrelated GUI callbacks,
     * grants, and delayed tasks must not touch the inventory in that window.
     */
    public static boolean ready(Plugin plugin, Player player) {
        return readyForHandoff(plugin, player) && !ClaimDelivery.hasMarkedItem(plugin, player);
    }

    /**
     * Delivery reconciliation's narrowly scoped admission check. It may run
     * while its own marked items are present so it can either acknowledge and
     * unmark them or safely retry after an interrupted handoff. No unrelated
     * economy, GUI, command, or grant path may use this method.
     */
    public static boolean readyForHandoff(Plugin plugin, Player player) {
        if (player == null || !player.isOnline() || player.isDead() || reserved(player) || Bukkit.getPlayer(player.getUniqueId()) != player) return false;
        return !(plugin instanceof me.vertex.core.VertexPlugin vertex)
                || vertex.networkManager() == null || vertex.networkManager().inventoryReady(player);
    }
}
