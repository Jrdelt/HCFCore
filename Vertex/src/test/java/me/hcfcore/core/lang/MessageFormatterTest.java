package me.hcfcore.core.lang;

import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageFormatterTest {

    @Test
    void legacyHexCodeIsRenderedAsThatColor() {
        var component = MessageFormatter.deserialize("&#4E3F96Vertex");
        assertEquals(TextColor.fromHexString("#4E3F96"), component.color());
        assertEquals("Vertex", PlainTextComponentSerializer.plainText().serialize(component));
    }

    @Test
    void legacyHexCodeDoesNotLeakLiteralTextIntoTheMessage() {
        var component = MessageFormatter.deserialize("&#4E3F96&lVertex &7-> hi");
        String plain = PlainTextComponentSerializer.plainText().serialize(component);
        assertEquals("Vertex -> hi", plain);
    }
}
