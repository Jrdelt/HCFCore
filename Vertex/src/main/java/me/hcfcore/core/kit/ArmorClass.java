package me.hcfcore.core.kit;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Identifies which HCF class a player is currently in from the armor they
 * are wearing, the way the genre conventionally does it -- by armor
 * material rather than by an exact match against a kit definition.
 *
 * <p>Deliberately material-only: worn armor takes durability damage in
 * combat, the donator kit variants carry different enchantments, and a
 * player may have repaired or re-enchanted a piece. Anything stricter
 * would stop recognizing a player's class mid-fight, which is exactly the
 * failure the kit effect tracker used to have.
 */
public final class ArmorClass {

    /**
     * In the order Bukkit's getArmorContents() returns: boots, leggings,
     * chestplate, helmet -- not the top-down order kits.yml authors them in.
     */
    private static final Material[] BARD_SET = {
            Material.GOLDEN_BOOTS,
            Material.GOLDEN_LEGGINGS,
            Material.GOLDEN_CHESTPLATE,
            Material.GOLDEN_HELMET,
    };

    private static final Material[] ARCHER_SET = {
            Material.LEATHER_BOOTS,
            Material.LEATHER_LEGGINGS,
            Material.LEATHER_CHESTPLATE,
            Material.LEATHER_HELMET,
    };

    private ArmorClass() {
    }

    /**
     * Whether the player is wearing a full set of leather armor, i.e. is in
     * the archer kit (or its donator variant, which is also leather).
     * Leather dye is ignored -- matching is on material like every other
     * class check here.
     */
    public static boolean isArcher(Player player) {
        return isWearing(player, ARCHER_SET);
    }

    /**
     * Whether the player is wearing a full set of gold armor, i.e. is in
     * the bard kit (or its donator variant, which is also gold).
     *
     * <p>All four pieces are required: the mage kit also wears a gold
     * helmet and gold boots, over a chainmail chestplate and leggings, so
     * a looser check would treat every mage as a bard.
     */
    public static boolean isBard(Player player) {
        return isWearing(player, BARD_SET);
    }

    private static boolean isWearing(Player player, Material[] expected) {
        ItemStack[] armor = player.getInventory().getArmorContents();
        if (armor.length != expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (armor[i] == null || armor[i].getType() != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
