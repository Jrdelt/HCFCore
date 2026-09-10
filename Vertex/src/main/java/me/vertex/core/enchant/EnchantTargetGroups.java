package me.vertex.core.enchant;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Set;

/**
 * Resolves an enchant's configured {@code compatible-types} tokens (a mix of
 * named groups such as {@code PICKAXES}/{@code ARMOR}/{@code ALL} and plain
 * Material names) against a real target Material.
 *
 * <p>Kept as a tiny, free-standing resolver -- not a {@code Set<Material>}
 * expanded once at load time -- so it stays correct automatically as new
 * Materials are added to the game (a future pickaxe variant matches
 * {@code PICKAXES} for free) rather than needing every group's material set
 * hand-maintained.
 */
final class EnchantTargetGroups {

    private EnchantTargetGroups() {
    }

    /** @param tokens every token already upper-cased, per {@code EnchantManager}'s loader. */
    static boolean matches(Set<String> tokens, Material material) {
        for (String token : tokens) {
            if (matchesToken(token, material)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesToken(String token, Material material) {
        String name = material.name();
        return switch (token) {
            case "ALL" -> true;
            case "PICKAXE", "PICKAXES" -> name.endsWith("_PICKAXE");
            case "AXE", "AXES" -> name.endsWith("_AXE") && !name.endsWith("_PICKAXE");
            case "SHOVEL", "SHOVELS", "SPADE", "SPADES" -> name.endsWith("_SHOVEL");
            case "HOE", "HOES" -> name.endsWith("_HOE");
            case "SWORD", "SWORDS" -> name.endsWith("_SWORD");
            case "BOW", "BOWS" -> material == Material.BOW || material == Material.CROSSBOW;
            case "TOOL", "TOOLS" -> name.endsWith("_PICKAXE") || (name.endsWith("_AXE") && !name.endsWith("_PICKAXE"))
                    || name.endsWith("_SHOVEL") || name.endsWith("_HOE");
            case "HELMET", "HELMETS" -> name.endsWith("_HELMET") || material == Material.TURTLE_HELMET;
            case "CHESTPLATE", "CHESTPLATES" -> name.endsWith("_CHESTPLATE") || material == Material.ELYTRA;
            case "LEGGING", "LEGGINGS" -> name.endsWith("_LEGGINGS");
            case "BOOT", "BOOTS" -> name.endsWith("_BOOTS");
            case "ARMOR", "ARMOUR" -> name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                    || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")
                    || material == Material.TURTLE_HELMET || material == Material.ELYTRA;
            default -> matchesExplicitMaterial(token, material);
        };
    }

    private static boolean matchesExplicitMaterial(String token, Material material) {
        Material configured = Material.matchMaterial(token.toUpperCase(Locale.ROOT));
        return configured != null && configured == material;
    }
}
