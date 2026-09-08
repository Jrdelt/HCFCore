package me.vertex.core.trade;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Immutable main-thread snapshot of a trade's escrow state. Database work
 * must only use this value, never a live Bukkit inventory.
 */
record TradeSnapshot(UUID sessionId, UUID requester, UUID target,
                     ItemStack[] requesterItems, ItemStack[] targetItems,
                     double requesterMoney, double targetMoney,
                     int requesterExperience, int targetExperience,
                     double requesterHeldMoney, double targetHeldMoney,
                     int requesterHeldExperience, int targetHeldExperience) {

    TradeSnapshot {
        requesterItems = copy(requesterItems);
        targetItems = copy(targetItems);
    }

    static TradeSnapshot capture(TradeSession session) {
        return new TradeSnapshot(session.id, session.requester, session.target,
                session.itemsFor(session.requester), session.itemsFor(session.target),
                session.requesterMoney, session.targetMoney,
                session.requesterExperience, session.targetExperience,
                session.requesterHeldMoney, session.targetHeldMoney,
                session.requesterHeldExperience, session.targetHeldExperience);
    }

    private static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int index = 0; index < items.length; index++) {
            copy[index] = items[index] == null ? null : items[index].clone();
        }
        return copy;
    }
}
