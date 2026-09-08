package me.vertex.core.menu;

import me.vertex.core.lang.MessageFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bad value in a GUI file must degrade to a usable menu with a warning,
 * never take the screen down -- these pin that behaviour.
 */
class MenuRegistryTest {

    private PluginMock plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private MenuRegistry registryWith(String yaml) throws Exception {
        File folder = new File(plugin.getDataFolder(), "gui");
        folder.mkdirs();
        Files.writeString(new File(folder, "test.yml").toPath(), yaml, StandardCharsets.UTF_8);
        MenuRegistry registry = new MenuRegistry(plugin, List.of("test"));
        registry.load();
        return registry;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void readsSizeTitleAndItems() throws Exception {
        MenuLayout layout = registryWith("""
                size: 36
                title: "ᴍʏ ᴍᴇɴᴜ"
                items:
                  back:
                    material: ARROW
                    slot: 31
                    name: "ʙᴀᴄᴋ"
                """).layout("test");

        assertEquals(36, layout.size());
        assertEquals("ᴍʏ ᴍᴇɴᴜ", plain(layout.title(MenuPlaceholders.of())));
        assertNotNull(layout.item("back"));
        assertEquals(31, layout.item("back").slot());
    }

    @Test
    void rejectsAnInvalidSizeRatherThanCreatingABrokenInventory() throws Exception {
        assertEquals(27, registryWith("size: 30").layout("test").size());
        assertEquals(27, registryWith("size: 99").layout("test").size());
        assertEquals(27, registryWith("size: 0").layout("test").size());
    }

    @Test
    void fallsBackToStoneForAnUnknownMaterial() throws Exception {
        MenuLayout layout = registryWith("""
                items:
                  broken:
                    material: NOT_A_REAL_BLOCK
                    name: "x"
                """).layout("test");

        assertEquals(Material.STONE, layout.item("broken").render(MenuPlaceholders.of()).getType());
    }

    /** A template rendered many times supplies its own icon, so no material is valid. */
    @Test
    void allowsAnItemWithNoMaterialAndUsesTheCallersFallback() throws Exception {
        MenuLayout layout = registryWith("""
                items:
                  dynamic:
                    name: "x"
                """).layout("test");

        assertEquals(Material.DIAMOND,
                layout.item("dynamic").render(Material.DIAMOND, MenuPlaceholders.of()).getType());
    }

    @Test
    void treatsADisabledItemAsAbsent() throws Exception {
        MenuLayout layout = registryWith("""
                items:
                  optional:
                    material: ARROW
                    enabled: false
                """).layout("test");

        assertNull(layout.item("optional"));
    }

    @Test
    void ignoresOutOfRangeSlots() throws Exception {
        MenuLayout layout = registryWith("""
                items:
                  bad:
                    material: ARROW
                    slots: [5, 99, -3, 7]
                """).layout("test");

        assertArrayEquals(new int[]{5, 7}, layout.item("bad").slots());
    }

    @Test
    void readsNamedSlotRunsAndFallsBackWhenAbsent() throws Exception {
        MenuLayout layout = registryWith("""
                layout:
                  row: [1, 2, 3]
                """).layout("test");

        assertArrayEquals(new int[]{1, 2, 3}, layout.slots("row", new int[]{9}));
        assertArrayEquals(new int[]{9}, layout.slots("missing", new int[]{9}));
    }

    @Test
    void substitutesInlinePlaceholders() throws Exception {
        MenuLayout layout = registryWith("""
                items:
                  greeting:
                    material: PAPER
                    name: "ʜᴇʟʟᴏ {player}"
                """).layout("test");

        var meta = layout.item("greeting")
                .render(MenuPlaceholders.of().put("player", "Volos_")).getItemMeta();
        assertEquals("ʜᴇʟʟᴏ Volos_", plain(meta.displayName()));
    }

    /** A lore line that is only a block placeholder expands to however many lines it holds. */
    @Test
    void expandsABlockPlaceholderIntoMultipleLoreLines() throws Exception {
        MenuLayout layout = registryWith("""
                items:
                  listing:
                    material: PAPER
                    lore:
                      - "{sources}"
                      - "<gray>end"
                """).layout("test");

        var meta = layout.item("listing").render(MenuPlaceholders.of()
                .putBlock("sources", List.of(
                        MessageFormatter.deserialize("<green>one"),
                        MessageFormatter.deserialize("<green>two"),
                        MessageFormatter.deserialize("<green>three")))).getItemMeta();

        List<Component> lore = meta.lore();
        assertEquals(4, lore.size());
        assertEquals("one", plain(lore.get(0)));
        assertEquals("three", plain(lore.get(2)));
        assertEquals("end", plain(lore.get(3)));
    }

    /**
     * Substituted values are escaped, so a player-supplied name cannot inject
     * formatting into an admin's template.
     */
    @Test
    void escapesFormattingInsideSubstitutedValues() throws Exception {
        MenuLayout layout = registryWith("""
                items:
                  greeting:
                    material: PAPER
                    name: "{player}"
                """).layout("test");

        var meta = layout.item("greeting")
                .render(MenuPlaceholders.of().put("player", "<red>Nope")).getItemMeta();
        assertTrue(plain(meta.displayName()).contains("<red>"),
                "the tag should survive as literal text rather than becoming a colour");
    }

    @Test
    void paintsTheFillerAcrossEverySlotWhenEnabled() throws Exception {
        MenuLayout layout = registryWith("""
                size: 9
                filler:
                  enabled: true
                  material: GRAY_STAINED_GLASS_PANE
                  name: " "
                """).layout("test");

        var inventory = layout.createInventory(() -> null, MenuPlaceholders.of());
        for (int slot = 0; slot < 9; slot++) {
            assertEquals(Material.GRAY_STAINED_GLASS_PANE, inventory.getItem(slot).getType());
        }
    }

    @Test
    void leavesTheInventoryEmptyWhenTheFillerIsDisabled() throws Exception {
        MenuLayout layout = registryWith("""
                size: 9
                filler:
                  enabled: false
                  material: GRAY_STAINED_GLASS_PANE
                """).layout("test");

        assertNull(layout.createInventory(() -> null, MenuPlaceholders.of()).getItem(0));
    }

    @Test
    void returnsAUsableLayoutForAMenuThatWasNeverLoaded() {
        MenuLayout layout = new MenuRegistry(plugin, List.of()).layout("absent");

        assertEquals(27, layout.size());
        assertNull(layout.item("anything"));
    }
}
