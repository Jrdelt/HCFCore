package me.hcfcore.core.luckperms;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LuckPermsHookTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
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

        assertDoesNotThrow(() -> LuckPermsHook.grantTemporaryPermission(player, "essentials.fix", 60));
    }
}
