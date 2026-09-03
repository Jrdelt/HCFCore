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
        tagManager.load();

        List<String> ascending = tagManager.getSorted(TagManager.Sort.ALPHABETICAL, true).stream()
                .map(TagManager.Tag::display).toList();
        assertEquals(List.of("Founder", "Legend", "Newcomer", "Veteran"), ascending);

        List<String> descending = tagManager.getSorted(TagManager.Sort.ALPHABETICAL, false).stream()
                .map(TagManager.Tag::display).toList();
        assertEquals(List.of("Veteran", "Newcomer", "Legend", "Founder"), descending);
    }

    @Test
    void sortsByRarityLegendaryFirstAscending() {
        // Regression test: RARITY previously fell through to the dead
        // rarityOrder() method and actually sorted by player count instead.
        tagManager.load();

        List<String> ascending = tagManager.getSorted(TagManager.Sort.RARITY, true).stream()
                .map(TagManager.Tag::display).toList();
        assertEquals(List.of("Legend", "Founder", "Veteran", "Newcomer"), ascending);

        List<String> descending = tagManager.getSorted(TagManager.Sort.RARITY, false).stream()
                .map(TagManager.Tag::display).toList();
        assertEquals(List.of("Newcomer", "Veteran", "Founder", "Legend"), descending);
    }

    @Test
    void sortsByAgeOldestFirstAscending() {
        writeTagsYaml("""
                tags:
                  oldest:
                    display: "Oldest"
                    permission: ''
                    rarity: COMMON
                    created-at: 1000
                  middle:
                    display: "Middle"
                    permission: ''
                    rarity: COMMON
                    created-at: 2000
                  newest:
                    display: "Newest"
                    permission: ''
                    rarity: COMMON
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
                    rarity: COMMON
                    created-at: 1000
                  gated:
                    display: "Gated"
                    permission: hcfcore.tag.gated
                    rarity: COMMON
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
                    rarity: COMMON
                    created-at: 1000
                  gated1:
                    display: "Gated1"
                    permission: hcfcore.tag.gated1
                    rarity: COMMON
                    created-at: 1000
                  gated2:
                    display: "Gated2"
                    permission: hcfcore.tag.gated2
                    rarity: COMMON
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

    @Test
    void nicknameMatchAndReversedPersistAcrossAFreshLoad() {
        tagManager.load();
        PlayerMock player = server.addPlayer("Carol");

        tagManager.select(player.getUniqueId(), "legend");
        tagManager.setNicknameMatchEnabled(player.getUniqueId(), true);
        tagManager.setNicknameReversed(player.getUniqueId(), true);

        TagManager reloaded = new TagManager(plugin);
        reloaded.load();

        assertEquals("legend", reloaded.getPlayerTag(player.getUniqueId()));
        assertTrue(reloaded.isNicknameMatchEnabled(player.getUniqueId()));
        assertTrue(reloaded.isNicknameReversed(player.getUniqueId()));
        assertEquals(GradientColor.reverse("<gradient:#facc15:#f97316>"), reloaded.getNicknameColor(player));
    }

    @Test
    void nicknameColorIsNullWhenMatchDisabled() {
        tagManager.load();
        PlayerMock player = server.addPlayer("Dave");

        tagManager.select(player.getUniqueId(), "legend");

        assertNull(tagManager.getNicknameColor(player), "nickname-match defaults to disabled");
    }

    @Test
    void legacyFlatPlayerTagStringStillLoads() {
        UUID uuid = UUID.randomUUID();
        writeTagsYaml("""
                tags:
                  veteran:
                    display: "Veteran"
                    permission: ''
                    rarity: RARE
                    created-at: 1000
                players:
                  %s: veteran
                """.formatted(uuid));
        tagManager.load();

        assertEquals("veteran", tagManager.getPlayerTag(uuid));
        assertFalse(tagManager.isNicknameMatchEnabled(uuid));
    }
}
