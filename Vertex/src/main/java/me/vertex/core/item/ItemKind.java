package me.vertex.core.item;

/**
 * What a {@link TrackedItemIds}-tagged item is, so one shared instance-ID
 * PDC key can be reused by every future item-identity feature without each
 * one inventing its own marker key.
 *
 * <p>Custom Enchantments tag only a target item that has successfully
 * received a custom enchant ({@link #ENCHANTED_ITEM}). Base Runes and
 * rolled enchant items deliberately remain untagged so they can stack when
 * their data is identical. {@code me.vertex.core.dupe.DupeManager} treats
 * any item carrying a real (non-{@link #GENERIC}) kind as worth tracking --
 * see its {@code shouldTrack} method -- so tagging through
 * {@link TrackedItemIds} is the entire integration a new feature needs to
 * plug into the existing anti-dupe framework.
 *
 * <p>{@link #RUNE} and {@link #ENCHANTMENT_ITEM} remain for backwards
 * compatibility with items produced by older builds; new stackable rune
 * items must not be assigned either kind.
 */
public enum ItemKind {
    /** Placeholder value; not assigned to any real item on its own. */
    GENERIC,
    /** Legacy marker for a physical Rune produced by an older build. */
    RUNE,
    /** Legacy marker for a rolled custom-enchantment item produced by an older build. */
    ENCHANTMENT_ITEM,
    /** A gear item that has successfully received at least one custom enchant. */
    ENCHANTED_ITEM,
    /** A non-stackable Riftlands emergency-extraction ticket. */
    RIFTLANDS_TICKET
}
