package me.vertex.core.enchant;

/**
 * Explicit, config-declared categorization for a rune (never inferred from
 * its id, class, or display name). A rune may carry several tags -- e.g. an
 * AOE mining enchant is both {@code MINING} and {@code AOE} and
 * {@code BLOCK_BREAK}. Nothing in this phase consumes tags yet beyond
 * exposing them on {@link EnchantDefinition}; they exist so every rune ships
 * with correct metadata instead of it being retrofitted once {@code
 * /runeinfo} and tag-driven GUI filtering land in a later phase.
 */
public enum RuneTag {
    COMBAT,
    OFFENSIVE,
    DEFENSIVE,
    MOVEMENT,
    MINING,
    FARMING,
    LOGGING,
    FISHING,
    BLOCK_BREAK,
    BLOCK_PLACE,
    PROJECTILE,
    MOB,
    PLAYER,
    AOE,
    LOOT,
    PASSIVE,
    ACTIVE,
    COOLDOWN,
    EQUIPMENT
}
