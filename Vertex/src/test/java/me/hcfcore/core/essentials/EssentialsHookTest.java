package me.hcfcore.core.essentials;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class EssentialsHookTest {

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
    void isAvailableIsFalseWhenEssentialsIsNotInstalled() {
        assertFalse(EssentialsHook.isAvailable());
    }

    @Test
    void getNicknameIsNullWhenEssentialsIsNotInstalled() {
        PlayerMock player = server.addPlayer("Alice");

        assertNull(EssentialsHook.getNickname(player));
    }

    @Test
    void resolveNameFallsBackToTheRealNameWhenEssentialsIsNotInstalled() {
        PlayerMock player = server.addPlayer("Bob");

        assertEquals("Bob", EssentialsHook.resolveName(player));
    }
}
