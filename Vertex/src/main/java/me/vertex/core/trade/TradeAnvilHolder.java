package me.vertex.core.trade;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import java.util.UUID;

record TradeAnvilHolder(UUID sessionId, TradeValueType type) implements InventoryHolder {
    @Override public Inventory getInventory() { return null; }
}
