package me.vertex.core.enchant;

/**
 * A Rune's permanent category, fixed the moment the Rune is created (a
 * purchase, an admin grant -- whatever the source). A SIMPLE Rune always
 * rolls off the SIMPLE table for its entire life; it never upgrades or
 * downgrades tier.
 */
public enum RuneTier {
    SIMPLE,
    ELITE,
    RARE,
    LEGENDARY
}
