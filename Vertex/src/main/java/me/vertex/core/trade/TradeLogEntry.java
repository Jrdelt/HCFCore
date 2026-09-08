package me.vertex.core.trade;

import org.bukkit.inventory.ItemStack;
import java.util.UUID;

/** Immutable, serialized-at-completion audit snapshot. */
record TradeLogEntry(long id, UUID requester, UUID target, String requesterName, String targetName,
        ItemStack[] requesterItems, ItemStack[] targetItems, double requesterMoney, double targetMoney,
        int requesterExperience, int targetExperience, long timestamp, String status) { }
