package me.vertex.core.item;

/**
 * What a {@link TrackedItemIds}-tagged item is, so one shared instance-ID
 * PDC key can be reused by every future item-identity feature without each
 * one inventing its own marker key.
 *
 * <p>This started as an extensible marker shell only -- Phase 0 (item
 * identity + the dupe-investigation framework) didn't tag any real item
 * with it. Phase 6 (Custom Enchantments) is the first real consumer: every
 * physical Rune and every physical (rolled, not-yet-applied) enchantment
 * item is tagged {@link #RUNE}/{@link #ENCHANTMENT_ITEM}, and every target
 * item that has successfully received a custom enchant is tagged
 * {@link #ENCHANTED_ITEM}. {@code me.vertex.core.dupe.DupeManager} treats
 * any item carrying a real (non-{@link #GENERIC}) kind as worth tracking --
 * see its {@code shouldTrack} method -- so tagging through
 * {@link TrackedItemIds} is the entire integration a new feature needs to
 * plug into the existing anti-dupe framework.
 *
 * <p>Deliberately NOT used for Lucky Gems: they are a stackable, fully
 * consumed-on-use commodity, and {@link TrackedItemIds#ensureInstanceId}
 * assigns one instance ID to the whole {@code ItemStack} object regardless
 * of its stack amount -- splitting or merging a stack of gems would leave
 * more than one physical gem sharing the same "permanent, unique" ID, which
 * defeats the point of an instance ID. The existing anti-dupe framework
 * already only tracks amount-1 stacks for the same reason ({@code
 * DupeManager#ensureIdentity}'s own guard), so this is consistent with, not
 * a departure from, that framework's existing scope.
 */
public enum ItemKind {
    /** Placeholder value; not assigned to any real item on its own. */
    GENERIC,
    /** A physical Rune, permanently tied to one {@code RuneTier} since creation. */
    RUNE,
    /** A physical, not-yet-applied custom enchantment item produced by rolling a Rune. */
    ENCHANTMENT_ITEM,
    /** A gear item that has successfully received at least one custom enchant. */
    ENCHANTED_ITEM,
    /** A non-stackable Riftlands emergency-extraction ticket. */
    RIFTLANDS_TICKET
}
