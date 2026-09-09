package me.vertex.core.wand;

import org.bukkit.Material;

import java.util.List;

/**
 * One configured wand, e.g. the Gold Sell Wand.
 *
 * @param id       stable key, stored on the item so a wand keeps working across config edits
 * @param type     what left-clicking a container with it does
 * @param material the item it is made from
 * @param uses     how many container interactions a freshly given wand carries
 */
public record WandTier(
        String id,
        WandType type,
        Material material,
        Integer customModelData,
        String name,
        List<String> lore,
        int uses) {

    public WandTier {
        lore = List.copyOf(lore);
    }
}
