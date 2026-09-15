package me.vertex.core.resetvault;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable/defensive representation of a player's Reset Vault state.
 * Ownership is keyed by player UUID; IGN is display/search metadata only.
 * Stored items never expire and are never automatically purged.
 */
public final class ResetVaultData {

    private final UUID uuid;
    private final String lastKnownIgn;
    private final int permanentBonusSlots;
    private final List<ItemStack> items;

    public ResetVaultData(UUID uuid, String lastKnownIgn, int permanentBonusSlots, List<ItemStack> items) {
        this.uuid = Objects.requireNonNull(uuid, "uuid cannot be null");
        this.lastKnownIgn = lastKnownIgn == null ? "" : lastKnownIgn;
        this.permanentBonusSlots = Math.max(0, permanentBonusSlots);
        if (items == null || items.isEmpty()) {
            this.items = List.of();
        } else {
            List<ItemStack> copied = new ArrayList<>(items.size());
            for (ItemStack item : items) {
                if (item != null && !item.getType().isAir() && item.getAmount() > 0) {
                    copied.add(item.clone());
                }
            }
            this.items = Collections.unmodifiableList(copied);
        }
    }

    public UUID uuid() {
        return uuid;
    }

    public String lastKnownIgn() {
        return lastKnownIgn;
    }

    public int permanentBonusSlots() {
        return permanentBonusSlots;
    }

    public List<ItemStack> items() {
        return items;
    }

    public int itemCount() {
        return items.size();
    }

    public ResetVaultData withLastKnownIgn(String ign) {
        return new ResetVaultData(uuid, ign, permanentBonusSlots, items);
    }

    public ResetVaultData withBonusSlots(int bonusSlots) {
        return new ResetVaultData(uuid, lastKnownIgn, bonusSlots, items);
    }

    public ResetVaultData withItems(List<ItemStack> newItems) {
        return new ResetVaultData(uuid, lastKnownIgn, permanentBonusSlots, newItems);
    }

    public ResetVaultData addItem(ItemStack item) {
        Objects.requireNonNull(item, "item cannot be null");
        List<ItemStack> next = new ArrayList<>(items);
        next.add(item.clone());
        return new ResetVaultData(uuid, lastKnownIgn, permanentBonusSlots, next);
    }

    public ResetVaultData removeItem(int logicalIndex) {
        if (logicalIndex < 0 || logicalIndex >= items.size()) {
            return this;
        }
        List<ItemStack> next = new ArrayList<>(items);
        next.remove(logicalIndex);
        return new ResetVaultData(uuid, lastKnownIgn, permanentBonusSlots, next);
    }

    public ResetVaultData setItem(int logicalIndex, ItemStack item) {
        List<ItemStack> next = new ArrayList<>(items);
        if (logicalIndex >= 0 && logicalIndex < next.size()) {
            if (item == null || item.getType().isAir()) {
                next.remove(logicalIndex);
            } else {
                next.set(logicalIndex, item.clone());
            }
        } else if (logicalIndex == next.size() && item != null && !item.getType().isAir()) {
            next.add(item.clone());
        }
        return new ResetVaultData(uuid, lastKnownIgn, permanentBonusSlots, next);
    }
}
