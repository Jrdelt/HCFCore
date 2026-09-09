package me.vertex.core.gc;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the parts of {@link GcSignPrompt} that don't require simulating a
 * real client editing a sign (see the class doc for why the full convert
 * -> open -> {@code SignChangeEvent} -> restore loop is flagged for manual
 * smoke-testing instead): persisting and reloading the shared sign-input
 * location, and refusing a request outright when nothing is configured.
 *
 * <p>The location write is a targeted line edit (mirroring {@code
 * StorageMigrator#writeStorageType}), not {@code FileConfiguration#save} --
 * this specifically asserts gc.yml's comments survive it, since that is
 * the whole reason that approach exists over the simpler alternative.
 */
class GcSignPromptTest {

    private ServerMock server;
    private PluginMock plugin;
    private GcSignPrompt prompt;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        prompt = new GcSignPrompt(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void isNotConfiguredWhenGcYmlHasNeverBeenWritten() {
        prompt.loadLocation();
        assertFalse(prompt.isConfigured());
    }

    @Test
    void requestRefusesOutrightWhenNotConfigured() {
        prompt.loadLocation();
        var host = server.addPlayer("Host");
        GcSignPrompt.RequestResult result = prompt.request(host, GcSignPrompt.Operation.DEPOSIT, 45,
                amount -> { }, () -> { });
        assertEquals(GcSignPrompt.RequestResult.NOT_CONFIGURED, result);
    }

    @Test
    void setLocationPersistsAndReloadsCorrectly() throws Exception {
        plugin.saveResource("gc.yml", false);
        World world = server.addSimpleWorld("gc-sign-world");
        Location location = new Location(world, 12, 65, -34);

        prompt.setLocation(location);
        assertTrue(prompt.isConfigured());
        assertEquals(world, prompt.location().getWorld());
        assertEquals(12, prompt.location().getBlockX());
        assertEquals(65, prompt.location().getBlockY());
        assertEquals(-34, prompt.location().getBlockZ());

        // A fresh instance loading from the same file must see the same location --
        // this is what actually happens on a server restart.
        GcSignPrompt reloaded = new GcSignPrompt(plugin);
        reloaded.loadLocation();
        assertTrue(reloaded.isConfigured());
        assertEquals("gc-sign-world", reloaded.location().getWorld().getName());
        assertEquals(12, reloaded.location().getBlockX());
        assertEquals(65, reloaded.location().getBlockY());
        assertEquals(-34, reloaded.location().getBlockZ());
    }

    @Test
    void setLocationKeepsGcYmlsCommentsIntact() throws Exception {
        plugin.saveResource("gc.yml", false);
        Path gcYml = new File(plugin.getDataFolder(), "gc.yml").toPath();
        String before = Files.readString(gcYml, StandardCharsets.UTF_8);
        assertTrue(before.contains("# GC (GIFT CARD / CREDIT)"), "sanity check: the bundled file has its header comment");

        World world = server.addSimpleWorld("gc-sign-world");
        prompt.setLocation(new Location(world, 1, 2, 3));

        String after = Files.readString(gcYml, StandardCharsets.UTF_8);
        assertTrue(after.contains("# GC (GIFT CARD / CREDIT)"), "the file header comment must survive the edit");
        assertTrue(after.contains("min-deposit: 1"), "unrelated settings must be untouched");
        assertTrue(after.contains("world: \"gc-sign-world\""));
        assertTrue(after.contains("x: 1"));
        assertTrue(after.contains("y: 2"));
        assertTrue(after.contains("z: 3"));
    }

    @Test
    void setLocationRoundsToTheBlockEvenFromAFractionalCoordinate() throws Exception {
        plugin.saveResource("gc.yml", false);
        World world = server.addSimpleWorld("gc-sign-world");
        prompt.setLocation(new Location(world, 1.9, 64.1, -5.5));

        assertEquals(1, prompt.location().getBlockX());
        assertEquals(64, prompt.location().getBlockY());
        assertEquals(-6, prompt.location().getBlockZ());
    }
}
