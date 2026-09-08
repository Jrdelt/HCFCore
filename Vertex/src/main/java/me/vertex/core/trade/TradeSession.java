package me.vertex.core.trade;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Authoritative, single-server state for a two-player trade. The shared
 * inventory is only a rendered escrow view; all lifecycle decisions belong
 * to this object and {@link TradeManager}.
 */
final class TradeSession {
    final UUID id = UUID.randomUUID();
    final UUID requester;
    final UUID target;
    final long createdAt = System.currentTimeMillis();
    long lastActivity = createdAt;
    Inventory inventory;
    boolean requesterLocked;
    boolean targetLocked;
    UUID firstLocked;
    boolean finishing;
    double requesterMoney;
    double targetMoney;
    int requesterExperience;
    int targetExperience;
    double requesterHeldMoney;
    double targetHeldMoney;
    int requesterHeldExperience;
    int targetHeldExperience;

    TradeSession(UUID requester, UUID target) {
        this.requester = requester;
        this.target = target;
    }

    boolean involves(UUID player) { return requester.equals(player) || target.equals(player); }
    boolean isRequester(UUID player) { return requester.equals(player); }
    boolean locked(UUID player) { return isRequester(player) ? requesterLocked : targetLocked; }
    void lock(UUID player) {
        if (isRequester(player)) requesterLocked = true; else targetLocked = true;
        if (firstLocked == null) firstLocked = player;
    }
    boolean bothLocked() { return requesterLocked && targetLocked; }
    int[] slotsFor(UUID player) { return isRequester(player) ? TradeMenu.LEFT_SLOTS : TradeMenu.RIGHT_SLOTS; }
    ItemStack[] itemsFor(UUID player) {
        int[] slots = slotsFor(player);
        ItemStack[] result = new ItemStack[slots.length];
        for (int i = 0; i < slots.length; i++) {
            ItemStack item = inventory.getItem(slots[i]);
            result[i] = item == null || item.isEmpty() ? null : item.clone();
        }
        return result;
    }
}
