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
    LEGENDARY,
    ARENA,
    /** Admin/event-distributed only -- never appears in any tier's roll table (see {@code seasonal:} in runes.yml). */
    SEASONAL,
    /** The Expanded Rune Module's basic tier (Zeus, Obliterate, Ghost, ...). */
    COMMON,
    /** The Expanded Rune Module's top non-cursed tier (Petrify, Phoenix, Angelic, ...). */
    MYTHIC,
    /** The Expanded Rune Module's niche/anti-gank tier (Equalizer, Fat). */
    CURSED
}
