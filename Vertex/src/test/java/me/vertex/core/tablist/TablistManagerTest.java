package me.vertex.core.tablist;

import me.vertex.core.lang.MessageAssertions;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every config used here deliberately avoids {faction}/{ftop}/{power} --
 * see {@link me.vertex.core.lang.PlaceholderResolverTest} for why those
 * specifically can't be exercised without a live FactionsUUID plugin.
 *
 * <p>Header/footer content itself isn't asserted on: MockBukkit's
 * {@code PlayerMock} doesn't actually back
 * {@code sendPlayerListHeaderAndFooter(Component, Component)} -- the
 * modern Paper method this code (correctly) uses -- with any state its
 * own getters read from; calling the getter after a real, successful
 * send still returns a stale null and throws serializing it. That's a
 * gap in the test double, not in the production call, so these tests
 * cover that resolving and sending a header/footer never throws, and
 * lean on the entry-name assertions (which MockBukkit does track
 * correctly) for the rest of the render pipeline.
 */
class TablistManagerTest {

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
    void renderNowSetsTheEntryName() {
        TablistManager manager = manager(true, "<gray>{name}", List.of("Header line"), List.of("Footer line"));
        PlayerMock player = server.addPlayer("Steve");

        manager.renderNow(player);

        assertEquals("Steve", MessageAssertions.plain(player.playerListName()));
    }

    @Test
    void formatTemplateIsApplied() {
        TablistManager manager = manager(true, "[V] {name}", List.of(), List.of());
        PlayerMock player = server.addPlayer("Steve");

        manager.renderNow(player);

        assertEquals("[V] Steve", MessageAssertions.plain(player.playerListName()));
    }

    @Test
    void resolvingAndSendingAHeaderAndFooterNeverThrows() {
        TablistManager manager = manager(true, "{name}", List.of("Line one", "Line two"), List.of("Footer"));
        PlayerMock player = server.addPlayer("Steve");

        assertDoesNotThrow(() -> manager.renderNow(player));
    }

    @Test
    void emptyHeaderAndFooterListsAreFine() {
        TablistManager manager = manager(true, "{name}", List.of(), List.of());
        PlayerMock player = server.addPlayer("Steve");

        assertDoesNotThrow(() -> manager.renderNow(player));
    }

    @Test
    void disabledManagerLeavesTheVanillaDefaultNameAlone() {
        TablistManager manager = manager(false, "[V] {name}", List.of("Header"), List.of("Footer"));
        PlayerMock player = server.addPlayer("Steve");

        manager.renderNow(player);

        // MockBukkit's own default for an untouched player-list name is the
        // player's own name, matching vanilla -- if this were "[V] Steve"
        // instead, the disabled flag failed to gate rendering.
        assertEquals("Steve", MessageAssertions.plain(player.playerListName()));
    }

    @Test
    void onlineCountReflectsAllOnlinePlayersWithoutAStaffManagerWired() {
        TablistManager manager = manager(true, "{online} online", List.of(), List.of());
        server.addPlayer();
        server.addPlayer();
        PlayerMock viewer = server.addPlayer();

        manager.renderNow(viewer);

        assertEquals("3 online", MessageAssertions.plain(viewer.playerListName()));
    }

    @Test
    void tickRendersEveryOnlinePlayer() {
        TablistManager manager = manager(true, "<gray>{name}", List.of(), List.of());
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");

        manager.start();
        ((BukkitSchedulerMock) server.getScheduler()).performTicks(6);

        assertEquals("Alice", MessageAssertions.plain(alice.playerListName()));
        assertEquals("Bob", MessageAssertions.plain(bob.playerListName()));
    }

    @Test
    void stopRestoresTheVanillaDefaultName() {
        TablistManager manager = manager(true, "[V] {name}", List.of("Header"), List.of("Footer"));
        PlayerMock player = server.addPlayer("Steve");
        manager.renderNow(player);
        assertEquals("[V] Steve", MessageAssertions.plain(player.playerListName()));

        manager.stop();

        assertEquals("Steve", MessageAssertions.plain(player.playerListName()),
                "stop() should hand the tab list name back to vanilla, not leave the custom format applied");
    }

    @Test
    void stopWithNoPriorRenderNeverThrows() {
        TablistManager manager = manager(true, "{name}", List.of(), List.of());

        assertDoesNotThrow(manager::stop);
    }

    @Test
    void removeForgetsAPlayerWithoutTouchingTheirCurrentState() {
        TablistManager manager = manager(true, "{name}", List.of(), List.of());
        PlayerMock player = server.addPlayer("Steve");
        manager.renderNow(player);

        manager.remove(player.getUniqueId());

        // Still whatever was last rendered -- remove() only forgets the
        // cached state (so a later stop() won't try to reset a player who
        // already logged off), it doesn't itself revert anything.
        assertEquals("Steve", MessageAssertions.plain(player.playerListName()));
    }

    private TablistManager manager(boolean enabled, String format, List<String> header, List<String> footer) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("tablist.enabled", enabled);
        config.set("tablist.update-interval-ticks", 5);
        config.set("tablist.format", format);
        config.set("tablist.header", header);
        config.set("tablist.footer", footer);
        return new TablistManager(plugin, config);
    }
}
