package me.vertex.core.enchant.listener;

import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneTier;
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
        listener = new RuneListener(manager, null, null, null, null);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void onlyActualEquipmentIsAnApplicationTarget() {
        ItemStack identified = manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE);

        assertFalse(listener.isApplicationTarget(null));
        assertFalse(listener.isApplicationTarget(new ItemStack(Material.AIR)));
        assertFalse(listener.isApplicationTarget(manager.createRune(RuneTier.SIMPLE)));
        assertFalse(listener.isApplicationTarget(manager.createLuckyGem()));
        assertFalse(listener.isApplicationTarget(identified));
        assertTrue(listener.isApplicationTarget(new ItemStack(Material.DIAMOND_PICKAXE)));
    }
}
