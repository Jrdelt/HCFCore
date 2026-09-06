package me.vertex.core;

import me.vertex.core.economy.EconomyHook;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertFalse;

class PluginDependencySafetyTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void pluginReportsMissingFactionsDependencyAsUnsafe() {
        assertFalse(VertexPlugin.hasRequiredDependency(Bukkit.getPluginManager()));
    }

    @Test
    void economyAvailabilityChecksTheRegisteredService() {
        assertFalse(EconomyHook.isAvailable());
    }
}
