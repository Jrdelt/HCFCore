package me.vertex.core.trade;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** Crash-recovery snapshot for one side of an unfinished trade. */
record TradeEscrow(UUID sessionId, UUID owner, ItemStack[] items, double money, int experience) { }
