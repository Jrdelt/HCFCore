package me.vertex.core.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the shared item-identity utility {@code BackpackManager}'s
 * instance-ID pattern was generalized from: exactly-once assignment,
 * {@link ItemKind} tagging, and same-instance comparison.
 */
class TrackedItemIdsTest {

    private PluginMock plugin;
    private TrackedItemIds tracked;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        tracked = new TrackedItemIds(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void ensureInstanceIdAssignsAnIdOnce() {
        ItemStack item = new ItemStack(Material.DIAMOND);
        tracked.ensureInstanceId(item, ItemKind.GENERIC);
        String first = tracked.instanceId(item).orElseThrow();

        tracked.ensureInstanceId(item, ItemKind.GENERIC);
        String second = tracked.instanceId(item).orElseThrow();

        assertEquals(first, second, "re-tagging an already-tagged item must not replace its instance ID");
    }

    @Test
    void untaggedItemHasNoInstanceIdOrKind() {
        ItemStack item = new ItemStack(Material.DIAMOND);
        assertTrue(tracked.instanceId(item).isEmpty());
        assertTrue(tracked.kind(item).isEmpty());
    }

    @Test
    void kindIsReadableAfterTagging() {
        ItemStack item = new ItemStack(Material.DIAMOND);
        tracked.ensureInstanceId(item, ItemKind.GENERIC);
        assertEquals(ItemKind.GENERIC, tracked.kind(item).orElseThrow());
    }

    @Test
    void isSameInstanceIsTrueOnlyForTheSameTaggedItem() {
        ItemStack first = new ItemStack(Material.DIAMOND);
        ItemStack second = new ItemStack(Material.DIAMOND);
        tracked.ensureInstanceId(first, ItemKind.GENERIC);
        tracked.ensureInstanceId(second, ItemKind.GENERIC);

        assertFalse(tracked.isSameInstance(first, second), "two independently tagged items must not match");

        ItemStack firstClone = first.clone();
        assertTrue(tracked.isSameInstance(first, firstClone), "a clone carries the same PDC instance ID");
    }

    @Test
    void isSameInstanceIsFalseForUntaggedItems() {
        ItemStack first = new ItemStack(Material.DIAMOND);
        ItemStack second = new ItemStack(Material.DIAMOND);
        assertFalse(tracked.isSameInstance(first, second));
    }

    @Test
    void ensureInstanceIdIsANoOpForAnAirItem() {
        ItemStack air = new ItemStack(Material.AIR);
        tracked.ensureInstanceId(air, ItemKind.GENERIC);
        assertTrue(tracked.instanceId(air).isEmpty());
    }
}
