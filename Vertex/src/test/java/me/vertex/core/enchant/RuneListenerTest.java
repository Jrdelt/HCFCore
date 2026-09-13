package me.vertex.core.enchant;

import me.vertex.core.item.TrackedItemIds;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuneListenerTest {
    private EnchantManager manager;
    private RuneListener listener;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        PluginMock plugin = MockBukkit.createMockPlugin();
        manager = new EnchantManager(plugin, new TrackedItemIds(plugin));
        manager.load();
        listener = new RuneListener(manager, null);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void onlyActualEquipmentIsAnApplicationTarget() {
        ItemStack identified = manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE);

        assertFalse(listener.isApplicationTarget(null, null));
        assertFalse(listener.isApplicationTarget(new ItemStack(Material.AIR), null));
        assertFalse(listener.isApplicationTarget(manager.createRune(RuneTier.SIMPLE), null));
        assertFalse(listener.isApplicationTarget(manager.createLuckyGem(), null));
        assertFalse(listener.isApplicationTarget(identified, null));
        assertTrue(listener.isApplicationTarget(new ItemStack(Material.DIAMOND_PICKAXE), null));
    }
}
