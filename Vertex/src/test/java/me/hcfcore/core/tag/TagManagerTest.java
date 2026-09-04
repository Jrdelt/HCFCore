package me.hcfcore.core.tag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagManagerTest {

    private ServerMock server;
    private PluginMock plugin;
    private TagManager tagManager;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        tagManager = new TagManager(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void writeTagsYaml(String content) {
        try {
            File file = new File(plugin.getDataFolder(), "tags.yml");
            file.getParentFile().mkdirs();
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void sortsAlphabeticallyAscendingAndDescending() {
        // Uses its own fixture rather than the bundled tags.yml, whose
        // tag list is admin content that changes independently of this
        // test (e.g. padded with extra entries to test pagination).
        writeTagsYaml("""
                tags:
                  founder:
                    display: "&dFounder"
                    permission: ''
                    created-at: 1000
                  legend:
                    display: "<gradient:#facc15:#f97316>Legend"
                    permission: ''
                    created-at: 1000
                  newcomer:
                    display: "&7Newcomer"
                    permission: ''
                    created-at: 1000
                  veteran:
                    display: "&bVeteran"
                    permission: ''
                    created-at: 1000
                players: {}
                """);
        tagManager.load();

        List<String> ascending = tagManager.getSorted(TagManager.Sort.ALPHABETICAL, true).stream()
                .map(tag -> GradientColor.stripLeadingColor(tag.display())).toList();
        assertEquals(List.of("Founder", "Legend", "Newcomer", "Veteran"), ascending);

        List<String> descending = tagManager.getSorted(TagManager.Sort.ALPHABETICAL, false).stream()
                .map(tag -> GradientColor.stripLeadingColor(tag.display())).toList();
        assertEquals(List.of("Veteran", "Newcomer", "Legend", "Founder"), descending);
    }

    @Test
    void sortsByAgeOldestFirstAscending() {
        writeTagsYaml("""
                tags:
                  oldest:
                    display: "Oldest"
                    permission: ''
                    created-at: 1000
                  middle:
                    display: "Middle"
                    permission: ''
                    created-at: 2000
                  newest:
                    display: "Newest"
                    permission: ''
                    created-at: 3000
                players: {}
                """);
        tagManager.load();

        List<String> ascending = tagManager.getSorted(TagManager.Sort.AGE, true).stream()
                .map(TagManager.Tag::display).toList();
        assertEquals(List.of("Oldest", "Middle", "Newest"), ascending);
    }

    @Test
    void isUnlockedRespectsBlankPermissionAndGrantedPermission() {
        writeTagsYaml("""
                tags:
                  open:
                    display: "Open"
                    permission: ''
                    created-at: 1000
                  gated:
                    display: "Gated"
                    permission: hcfcore.tag.gated
                    created-at: 1000
                players: {}
                """);
        tagManager.load();

        PlayerMock player = server.addPlayer("Alice");
        TagManager.Tag open = tagManager.get("open");
        TagManager.Tag gated = tagManager.get("gated");

        assertTrue(tagManager.isUnlocked(player, open), "blank permission should be unlocked for everyone");
        assertFalse(tagManager.isUnlocked(player, gated), "gated tag should be locked without the permission");

        player.addAttachment(plugin, "hcfcore.tag.gated", true);
        assertTrue(tagManager.isUnlocked(player, gated), "granting the permission should unlock the tag");
    }

    @Test
    void filterCountsMatchYourUnownedAndAllForAPlayer() {
        writeTagsYaml("""
                tags:
                  open:
                    display: "Open"
                    permission: ''
                    created-at: 1000
                  gated1:
                    display: "Gated1"
                    permission: hcfcore.tag.gated1
                    created-at: 1000
                  gated2:
                    display: "Gated2"
                    permission: hcfcore.tag.gated2
                    created-at: 1000
                players: {}
                """);
        tagManager.load();

        PlayerMock player = server.addPlayer("Bob");
        player.addAttachment(plugin, "hcfcore.tag.gated1", true);

        List<TagManager.Tag> all = tagManager.getSorted(TagManager.Sort.ALPHABETICAL, true);
        long yourCount = all.stream().filter(tag -> tagManager.isUnlocked(player, tag)).count();
        long unownedCount = all.stream().filter(tag -> !tagManager.isUnlocked(player, tag)).count();

        assertEquals(3, all.size());
        assertEquals(2, yourCount, "open + gated1 should be unlocked");
        assertEquals(1, unownedCount, "gated2 should still be locked");
    }

    private static final String LEGEND_FIXTURE = """
            tags:
              legend:
                display: "<gradient:#facc15:#f97316>Legend"
                permission: ''
                created-at: 1000
            players: {}
            """;

    @Test
    void selectThenUnselectClearsThePlayersTag() {
        // Uses its own fixture rather than the bundled tags.yml, whose
        // tag set is admin content that changes independently of this test.
        writeTagsYaml(LEGEND_FIXTURE);
        tagManager.load();
        PlayerMock player = server.addPlayer("Eve");

        tagManager.select(player.getUniqueId(), "legend");
        assertEquals("legend", tagManager.getPlayerTag(player.getUniqueId()));
        assertEquals(1, tagManager.owners("legend"));

        tagManager.unselect(player.getUniqueId());
        assertNull(tagManager.getPlayerTag(player.getUniqueId()));

        // owners is a lifetime counter, not a live "currently equipped"
        // count -- unselecting doesn't roll it back.
        assertEquals(1, tagManager.owners("legend"));
    }

    @Test
    void nicknameMatchAndReversedPersistAcrossAFreshLoad() {
        writeTagsYaml(LEGEND_FIXTURE);
        tagManager.load();
        PlayerMock player = server.addPlayer("Carol");

        tagManager.select(player.getUniqueId(), "legend");
        tagManager.setNicknameMatchEnabled(player.getUniqueId(), true);
        tagManager.setNicknameReversed(player.getUniqueId(), true);
        // Saves are queued onto an executor now (see TagManager.save()), so
        // a "restart" here has to wait for the pending write the same way
        // HCFCorePlugin.onDisable() does in production, or this read races it.
        tagManager.awaitWrites();

        TagManager reloaded = new TagManager(plugin);
        reloaded.load();

        assertEquals("legend", reloaded.getPlayerTag(player.getUniqueId()));
        assertTrue(reloaded.isNicknameMatchEnabled(player.getUniqueId()));
        assertTrue(reloaded.isNicknameReversed(player.getUniqueId()));
        assertEquals(GradientColor.reverse("<gradient:#facc15:#f97316>"), reloaded.getNicknameColor(player));
    }

    @Test
    void nicknameColorIsNullWhenMatchDisabled() {
        writeTagsYaml(LEGEND_FIXTURE);
        tagManager.load();
        PlayerMock player = server.addPlayer("Dave");

        tagManager.select(player.getUniqueId(), "legend");

        assertNull(tagManager.getNicknameColor(player), "nickname-match defaults to disabled");
    }

    @Test
    void materialAndLorePersistAcrossAFreshLoad() {
        writeTagsYaml("""
                tags:
                  legend:
                    display: "Legend"
                    permission: ''
                    created-at: 1000
                    material: NETHER_STAR
                    custom-model-data: 42
                    lore:
                      - '<gray>A name known by all.'
                      - '<gray>Second line.'
                players: {}
                """);
        tagManager.load();

        TagManager.Tag legend = tagManager.get("legend");
        assertEquals("NETHER_STAR", legend.material());
        assertEquals(42, legend.customModelData());
        assertEquals(List.of("<gray>A name known by all.", "<gray>Second line."), legend.lore());

        tagManager.save();
        tagManager.awaitWrites();
        TagManager reloaded = new TagManager(plugin);
        reloaded.load();

        TagManager.Tag reloadedLegend = reloaded.get("legend");
        assertEquals("NETHER_STAR", reloadedLegend.material());
        assertEquals(42, reloadedLegend.customModelData());
        assertEquals(List.of("<gray>A name known by all.", "<gray>Second line."), reloadedLegend.lore());
    }

    @Test
    void materialAndLoreDefaultToNullAndEmptyWhenUnset() {
        writeTagsYaml("""
                tags:
                  plain:
                    display: "Plain"
                    permission: ''
                    created-at: 1000
                players: {}
                """);
        tagManager.load();

        TagManager.Tag plain = tagManager.get("plain");
        assertNull(plain.material());
        assertNull(plain.customModelData());
        assertTrue(plain.lore().isEmpty());
    }

    @Test
    void legacyFlatPlayerTagStringStillLoads() {
        UUID uuid = UUID.randomUUID();
        writeTagsYaml("""
                tags:
                  veteran:
                    display: "Veteran"
                    permission: ''
                    created-at: 1000
                players:
                  %s: veteran
                """.formatted(uuid));
        tagManager.load();

        assertEquals("veteran", tagManager.getPlayerTag(uuid));
        assertFalse(tagManager.isNicknameMatchEnabled(uuid));
    }
}
