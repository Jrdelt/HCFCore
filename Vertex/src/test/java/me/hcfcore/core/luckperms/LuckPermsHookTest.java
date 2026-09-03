package me.hcfcore.core.luckperms;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class LuckPermsHookTest {

    private ServerMock server;
    private PluginMock plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void isAvailableIsFalseWhenLuckPermsIsNotInstalled() {
        assertFalse(LuckPermsHook.isAvailable());
    }

    @Test
    void grantTemporaryPermissionIsANoOpWhenLuckPermsIsNotInstalled() {
        PlayerMock player = server.addPlayer("Alice");

        assertDoesNotThrow(() -> LuckPermsHook.grantTemporaryPermission(plugin, player, "essentials.fix", 60));
    }

    @Test
    void getPrimaryGroupDisplayNameIsNullWhenLuckPermsIsNotInstalled() {
        PlayerMock player = server.addPlayer("Bob");

        assertNull(LuckPermsHook.getPrimaryGroupDisplayName(player));
    }
}
