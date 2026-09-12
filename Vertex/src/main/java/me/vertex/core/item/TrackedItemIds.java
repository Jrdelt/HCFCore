package me.vertex.core.item;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;

/**
 * Generalizes {@code BackpackManager}'s per-item instance-ID pattern
 * ({@code instanceKey}/{@code ensureInstanceId}/{@code isSameInstance}) into
 * a shared utility any feature can tag a physical item with. Every future
 * item-identity feature -- starting with Custom Enchantments' applied gear
 * and other non-stackable valuables -- should tag through this class rather than inventing
 * its own instance-ID PDC key, so {@code DupeManager} only ever has to
 * understand one identity marker.
 *
 * <p>Instantiated once per plugin (mirroring {@code BackpackManager} and
 * every other per-plugin manager in this codebase, rather than the static
 * utility shape of {@code Numbers}), since it owns two {@link NamespacedKey}s
 * that must be built from a {@link Plugin} reference.
 *
 * <p><b>PDC-marker leak caution:</b> a prior bug let a Chunk Collector's
 * mob-drop marker key leak onto every dropped item, which in turn broke
 * {@code WandManager#hasNoCustomData} -- that method exempts specific keys
 * via an explicit {@code harmlessMarkers} allowlist rather than the whole
 * Vertex namespace, specifically so one feature's marker can never make an
 * unrelated feature (a Wand) treat a tagged item as "plain." This class's
 * instance-ID key is not added to any such allowlist here, since nothing is
 * tagged with it yet in this phase -- but every future {@link ItemKind} that
 * starts using {@link #ensureInstanceId} must independently decide, at the
 * point it starts tagging, whether that key also needs the same treatment
 * for `harmlessMarkers`-style consumers. Keeping instance IDs behind one
 * key with a per-kind tag (rather than one key per {@link ItemKind}) means
 * that decision is made once per consumer, not once per key.
 */
public final class TrackedItemIds {

    private final NamespacedKey instanceIdKey;
    private final NamespacedKey kindKey;

    public TrackedItemIds(Plugin plugin) {
        this.instanceIdKey = new NamespacedKey(plugin, "tracked_instance_id");
        this.kindKey = new NamespacedKey(plugin, "tracked_item_kind");
    }

    /**
     * Assigns a random, permanent instance ID and the given {@link ItemKind}
     * to {@code item}, exactly once. A {@code has()} guard means calling
     * this repeatedly on the same physical item (e.g. every time it's
     * re-opened or re-rendered) never replaces an ID that survived a
     * duplication -- replacing it would erase the very evidence a dupe
     * investigation needs.
     */
    public void ensureInstanceId(ItemStack item, ItemKind kind) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        boolean changed = false;
        if (!pdc.has(instanceIdKey, PersistentDataType.STRING)) {
            pdc.set(instanceIdKey, PersistentDataType.STRING, UUID.randomUUID().toString());
            changed = true;
        }
        if (!kind.name().equals(pdc.get(kindKey, PersistentDataType.STRING))) {
            pdc.set(kindKey, PersistentDataType.STRING, kind.name());
            changed = true;
        }
        if (changed) {
            item.setItemMeta(meta);
        }
    }

    /** The item's permanent instance ID, if it has ever been tagged. */
    public Optional<String> instanceId(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return Optional.empty();
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(instanceIdKey, PersistentDataType.STRING);
        return id == null || id.isBlank() ? Optional.empty() : Optional.of(id);
    }

    /** The {@link ItemKind} the item was tagged with, if any. */
    public Optional<ItemKind> kind(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return Optional.empty();
        }
        String name = item.getItemMeta().getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ItemKind.valueOf(name));
        } catch (IllegalArgumentException notRecognized) {
            // A kind name from a future/older plugin version we don't know
            // about -- treat it as untagged rather than throwing.
            return Optional.empty();
        }
    }

    /** True only when both stacks carry the same, non-null instance ID. */
    public boolean isSameInstance(ItemStack first, ItemStack second) {
        Optional<String> firstId = instanceId(first);
        Optional<String> secondId = instanceId(second);
        return firstId.isPresent() && firstId.equals(secondId);
    }

    /**
     * Copies {@code from}'s instance ID and {@link ItemKind}, verbatim (not
     * reassigning a new ID), onto {@code to} -- for a legitimate vanilla
     * item transformation (an anvil rename/repair, a smithing-table
     * upgrade) whose platform-computed result is a distinct {@code
     * ItemStack} that cannot be assumed to already carry {@code from}'s
     * PDC. Without this, a duplication investigation would lose an item's
     * identity across a transformation nothing about it should treat as
     * suspicious.
     *
     * @return true when {@code from} was tagged and something was copied
     */
    public boolean copy(ItemStack from, ItemStack to) {
        Optional<String> id = instanceId(from);
        Optional<ItemKind> kind = kind(from);
        if (id.isEmpty() && kind.isEmpty()) {
            return false;
        }
        if (to == null || to.getType().isAir()) {
            return false;
        }
        ItemMeta meta = to.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        id.ifPresent(value -> pdc.set(instanceIdKey, PersistentDataType.STRING, value));
        kind.ifPresent(value -> pdc.set(kindKey, PersistentDataType.STRING, value.name()));
        to.setItemMeta(meta);
        return true;
    }
}
