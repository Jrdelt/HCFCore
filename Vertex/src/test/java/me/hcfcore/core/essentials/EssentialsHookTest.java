package me.hcfcore.core.essentials;

import me.hcfcore.core.lang.MessageFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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

    @Test
    void legacySectionColorCodesConvertToRenderableColor() {
        String result = EssentialsHook.legacyToMiniMessage("§cCarl");
        Component component = MessageFormatter.deserialize(result);

        assertEquals("Carl", PlainTextComponentSerializer.plainText().serialize(component));
        assertEquals(NamedTextColor.RED, component.color());
    }

    @Test
    void legacyAmpersandColorCodesConvertToRenderableColor() {
        String result = EssentialsHook.legacyToMiniMessage("&cDana");
        Component component = MessageFormatter.deserialize(result);

        assertEquals("Dana", PlainTextComponentSerializer.plainText().serialize(component));
        assertEquals(NamedTextColor.RED, component.color());
    }

    @Test
    void legacySectionHexCodesConvertToRenderableColor() {
        String result = EssentialsHook.legacyToMiniMessage("§x§f§f§0§0§0§0Eve");
        Component component = MessageFormatter.deserialize(result);

        assertEquals("Eve", PlainTextComponentSerializer.plainText().serialize(component));
        assertEquals(TextColor.color(0xFF0000), component.color());
    }
}
