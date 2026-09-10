package me.vertex.core.item;

/**
 * What a {@link TrackedItemIds}-tagged item is, so one shared instance-ID
 * PDC key can be reused by every future item-identity feature without each
 * one inventing its own marker key.
 *
 * <p>This is an extensible marker shell only -- Phase 0 (item identity +
 * the dupe-investigation framework) doesn't tag any real item with it yet.
 * Real values get added when a later phase actually needs to tag physical
 * items, e.g. Custom Enchantments' Runes and enchanted items. {@link #GENERIC}
 * exists only so the enum and {@link TrackedItemIds} compile and are
 * testable before that phase begins.
 */
public enum ItemKind {
    /** Placeholder value; not assigned to any real item by this phase. */
    GENERIC
}
