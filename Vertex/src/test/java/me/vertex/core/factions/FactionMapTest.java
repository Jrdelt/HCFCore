package me.vertex.core.factions;

import me.vertex.core.claims.ChunkKey;
import me.vertex.core.lang.Messages;
import me.vertex.core.storage.Database;
import me.vertex.core.storage.Storage;
import me.vertex.core.user.UserManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactionMapTest {
    @TempDir Path dataFolder;

    private ServerMock server;
    private PluginMock plugin;
    private Database database;
    private FactionService factions;
    private PlayerMock player;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        plugin.getConfig().set("factions.map.width", 7);
        plugin.getConfig().set("factions.map.height", 3);
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        factions = new FactionService(plugin, new FactionStorage(database));
        factions.init();
        Messages messages = new Messages(plugin, new UserManager(plugin, new NoOpStorage()));
        messages.load();
        factions.setMessages(messages);
        player = server.addPlayer("Mapper");
        player.setLocation(new Location(server.addSimpleWorld("world"), 8, 64, 8));
        assertEquals(FactionService.Result.OK, factions.create(player, "Alpha"));
        assertEquals(FactionService.Result.OK, factions.claim(player, ChunkKey.of(player.getLocation()), false));
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.close();
        MockBukkit.unmock();
    }

    @Test
    void configuredWidthAndHeightControlTheRenderedMap() {
        String plain = PlainTextComponentSerializer.plainText().serialize(factions.map(player));
        String[] lines = plain.split("\\n", -1);
        assertTrue(lines[0].contains("7x3"));
        assertEquals(4, lines.length, "header plus exactly three map rows");
        assertEquals(7, lines[1].length());
        assertEquals(7, lines[2].length());
        assertEquals(7, lines[3].length());
        assertEquals('✚', lines[2].charAt(3), "the player should be centered on the map");
        assertTrue(hasGreenCrosshair(factions.map(player)), "the player marker should be green");
    }

    @Test
    void reloadConfigAppliesMapDimensionsToTheNextMapImmediately() {
        plugin.getConfig().set("factions.map.width", 9);
        plugin.getConfig().set("factions.map.height", 5);
        factions.reloadConfig();

        String plain = PlainTextComponentSerializer.plainText().serialize(factions.map(player));
        String[] lines = plain.split("\\n", -1);
        assertTrue(lines[0].contains("9x5"));
        assertEquals(6, lines.length, "reload should affect the very next rendered map");
        assertEquals(9, lines[1].length());
    }

    @Test
    void claimHoverDistinguishesBaseAndExpiringRaidClaims() {
        factions.setClaimMapQueries((factionId, chunk) -> true, chunk -> 0L);
        assertTrue(hoverText(factions.map(player)).contains("Base Claim"));

        factions.setClaimMapQueries((factionId, chunk) -> false,
                chunk -> System.currentTimeMillis() + 3_600_000L);
        String raidHover = hoverText(factions.map(player));
        assertTrue(raidHover.contains("Raid Claim"));
        assertTrue(raidHover.contains("Expires In"));
    }

    private static String hoverText(Component component) {
        StringBuilder text = new StringBuilder();
        collectHover(component, text);
        return text.toString();
    }

    private static void collectHover(Component component, StringBuilder text) {
        HoverEvent<?> hover = component.hoverEvent();
        if (hover != null && hover.action() == HoverEvent.Action.SHOW_TEXT && hover.value() instanceof Component value) {
            text.append(PlainTextComponentSerializer.plainText().serialize(value));
        }
        component.children().forEach(child -> collectHover(child, text));
    }

    private static boolean hasGreenCrosshair(Component component) {
        if (component instanceof TextComponent text && text.content().equals("✚")
                && NamedTextColor.GREEN.equals(text.color())) return true;
        return component.children().stream().anyMatch(FactionMapTest::hasGreenCrosshair);
    }

    private static final class NoOpStorage implements Storage {
        @Override public void init() { }
        @Override public Map<String, Long> loadCooldowns(UUID uuid) { return Map.of(); }
        @Override public void saveCooldown(UUID uuid, String kitName, long availableAt) { }
        @Override public Map<String, Long> loadAbilityCooldowns(UUID uuid) { return Map.of(); }
        @Override public void saveAbilityCooldown(UUID uuid, String abilityId, long availableAt) { }
        @Override public String loadLocale(UUID uuid) { return null; }
        @Override public void saveLocale(UUID uuid, String locale) { }
        @Override public void saveDeath(UUID uuid, me.vertex.core.staff.Death death) { }
        @Override public java.util.List<me.vertex.core.staff.Death> loadDeaths(UUID uuid, int limit) { return java.util.List.of(); }
        @Override public void close() { }
    }
}
