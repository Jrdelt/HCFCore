package me.vertex.core.lang;

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

    @Test
    void plainRemovesMiniMessageFormattingFromAConfiguredName() {
        assertEquals("Bunker Blueprint", MessageFormatter.plain("<red>Bunker Blueprint"));
    }

    @Test
    void spreadLegacyHexCodeIsRenderedAsThatColorNotLiteralText() {
        // The per-digit &x&R&R&G&G&B&B form LegacyComponentSerializer's own
        // hexColors() support emits by default -- what serialize() actually
        // produces for a hex-colored Component -- must deserialize back to
        // the same color, not leak "&xD&x4&xA..." as literal text.
        var component = MessageFormatter.deserialize("&x&D&4&A&F&3&7Vertex");
        assertEquals(TextColor.fromHexString("#D4AF37"), component.color());
        assertEquals("Vertex", PlainTextComponentSerializer.plainText().serialize(component));
    }

    @Test
    void hexColoredLoreSurvivesASerializeDeserializeRoundTrip() {
        // Exactly the path a baked-on item's base-lore snapshot takes:
        // capture the live lore via serialize(), persist it as a plain
        // string, then rebuild it later via deserialize().
        var original = MessageFormatter.deserialize("<#D4AF37>Crate Exclusive");
        var roundTripped = MessageFormatter.deserialize(MessageFormatter.serialize(original));
        assertEquals(TextColor.fromHexString("#D4AF37"), roundTripped.color());
        assertEquals("Crate Exclusive", PlainTextComponentSerializer.plainText().serialize(roundTripped));
    }
}
