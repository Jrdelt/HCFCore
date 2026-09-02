package me.hcfcore.core.lang;

import me.hcfcore.core.storage.Storage;
import me.hcfcore.core.user.UserManager;
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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {

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
    void substitutesPlaceholdersInTheResolvedTemplate() {
        Messages messages = new Messages(plugin, new UserManager(plugin, new NoOpStorage()));
        messages.load();
        PlayerMock player = server.addPlayer("Alice");

        String rendered = messages.getRaw(player, "kit.cooldown", "seconds", "42");

        assertEquals("&cYou can use this kit again in 42s.", rendered);
    }

    @Test
    void fallsBackToTheDefaultLocaleWhenAKeyIsMissingInThePlayersLocale() throws IOException {
        // A locale file that only overrides one key -- everything else
        // must still resolve from en_us (the configured default).
        File langFolder = new File(plugin.getDataFolder(), "lang");
        langFolder.mkdirs();
        Files.writeString(new File(langFolder, "xx_test.yml").toPath(),
                "kit:\n  applied: '&aCustom applied text.'\n", StandardCharsets.UTF_8);

        UserManager userManager = new UserManager(plugin, new LocaleStorage("xx_test"));
        Messages messages = new Messages(plugin, userManager);
        messages.load();
        assertTrue(messages.isAvailable("xx_test"));

        PlayerMock player = server.addPlayer("Bob");
        userManager.load(player.getUniqueId());

        // Overridden in xx_test.
        assertEquals("&aCustom applied text.", messages.getRaw(player, "kit.applied"));
        // Not present in xx_test -- must fall back to en_us's real text.
        assertEquals("&cNo permission.", messages.getRaw(player, "kit.gui-no-access"));
    }

    @Test
    void missingKeyInEveryLocaleProducesADiagnosticInsteadOfCrashing() {
        Messages messages = new Messages(plugin, new UserManager(plugin, new NoOpStorage()));
        messages.load();
        PlayerMock player = server.addPlayer("Carol");

        String rendered = messages.getRaw(player, "does.not.exist");

        assertEquals("&cMissing translation: does.not.exist", rendered);
    }

    private static final class NoOpStorage implements Storage {
        @Override
        public void init() {
        }

        @Override
        public Map<String, Long> loadCooldowns(UUID uuid) {
            return Map.of();
        }

        @Override
        public void saveCooldown(UUID uuid, String kitName, long availableAt) {
        }

        @Override
        public Map<String, Long> loadAbilityCooldowns(UUID uuid) {
            return Map.of();
        }

        @Override
        public void saveAbilityCooldown(UUID uuid, String abilityId, long availableAt) {
        }

        @Override
        public String loadLocale(UUID uuid) {
            return null;
        }

        @Override
        public void saveLocale(UUID uuid, String locale) {
        }

        @Override
        public void close() {
        }
    }

    private static final class LocaleStorage implements Storage {
        private final String locale;

        private LocaleStorage(String locale) {
            this.locale = locale;
        }

        @Override
        public void init() {
        }

        @Override
        public Map<String, Long> loadCooldowns(UUID uuid) {
            return Map.of();
        }

        @Override
        public void saveCooldown(UUID uuid, String kitName, long availableAt) {
        }

        @Override
        public Map<String, Long> loadAbilityCooldowns(UUID uuid) {
            return Map.of();
        }

        @Override
        public void saveAbilityCooldown(UUID uuid, String abilityId, long availableAt) {
        }

        @Override
        public String loadLocale(UUID uuid) {
            return locale;
        }

        @Override
        public void saveLocale(UUID uuid, String locale) {
        }

        @Override
        public void close() {
        }
    }
}
