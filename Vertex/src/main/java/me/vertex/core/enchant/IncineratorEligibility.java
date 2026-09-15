package me.vertex.core.enchant;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds every standalone (never-applied) <em>identified</em> rune item
 * currently sitting in a player's hotbar or main inventory -- explicitly
 * excluding armor, offhand, cursor, and any open external container, per
 * the Incinerator spec. Still-sealed (unidentified) Rune boxes are never
 * eligible, regardless of protection settings -- incinerating an unopened
 * Rune would destroy value the player hasn't even seen yet, so the
 * Incinerator only ever touches an already-identified item ({@link
 * EnchantManager#isEnchantItem}), never a mystery box ({@link
 * EnchantManager#isRune}). A deliberately narrower scan than {@link
 * RuneEquipment#bindEligibleItems}, which exists for a different purpose
 * (bind-ability availability) and includes equipped gear.
 */
public final class IncineratorEligibility {

    private IncineratorEligibility() {
    }

    public record Entry(int slotIndex, ItemStack item) {
    }

    /** @return every eligible standalone rune stack, in slot order, regardless of protection state. */
    public static List<Entry> standaloneRunes(Player player, EnchantManager manager) {
        List<Entry> entries = new ArrayList<>();
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item != null && !item.getType().isAir() && manager.isEnchantItem(item)) {
                entries.add(new Entry(slot, item));
            }
        }
        return entries;
    }

    /** @return every eligible, currently-unprotected standalone rune stack -- what the Incinerator/Auto-Incineration actually acts on. */
    public static List<Entry> unprotectedStandaloneRunes(Player player, EnchantManager manager, RunePreferenceManager preferences) {
        List<Entry> unprotected = new ArrayList<>();
        for (Entry entry : standaloneRunes(player, manager)) {
            if (!isProtected(entry.item(), manager, preferences, player.getUniqueId())) {
                unprotected.add(entry);
            }
        }
        return unprotected;
    }

    public static boolean isProtected(ItemStack item, EnchantManager manager, RunePreferenceManager preferences, java.util.UUID uuid) {
        ProtectionKey key = keyOf(item, manager);
        if (key == null) {
            return false;
        }
        return Boolean.parseBoolean(preferences.get(uuid, key.runeId(), key.settingKey(), "false"));
    }

    public record ProtectionKey(String runeId, String settingKey) {
    }

    /** A base (unidentified) rune is keyed by its tier as a pseudo rune-id, since it has no rolled identity yet. */
    public static ProtectionKey keyOf(ItemStack item, EnchantManager manager) {
        EnchantManager.EnchantItemInfo info = manager.enchantItemInfo(item);
        if (info != null) {
            return new ProtectionKey(info.enchantId(), "protected:" + info.level());
        }
        RuneTier tier = manager.tierOf(item);
        if (tier != null) {
            return new ProtectionKey("tier:" + tier.name().toLowerCase(java.util.Locale.ROOT), "protected:0");
        }
        return null;
    }
}
