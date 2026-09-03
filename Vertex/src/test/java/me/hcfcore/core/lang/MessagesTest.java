package me.hcfcore.core.lang;

import me.hcfcore.core.storage.Storage;
import me.hcfcore.core.user.UserManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

        // Asserted structurally rather than against the literal en_us
        // wording, so a copy edit to lang/en_us.yml can't fail a test
        // that's really about placeholder substitution.
        String template = messages.getRaw(player, "kit.cooldown");
        assertTrue(template.contains("{seconds}"), "kit.cooldown should carry a {seconds} placeholder");

        String rendered = messages.getRaw(player, "kit.cooldown", "seconds", "42");

        assertEquals(template.replace("{seconds}", "42"), rendered);
        assertFalse(rendered.contains("{seconds}"), "the placeholder should have been substituted away");
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
        // Not present in xx_test -- must fall back to en_us's real text,
        // read from the bundled resource so the assertion tracks whatever
        // that text currently is instead of pinning a copy of it here.
        assertEquals(bundledLocale("en_us").getString("kit.gui-no-access"),
                messages.getRaw(player, "kit.gui-no-access"));
    }

    @Test
    void everyBundledLocaleDefinesTheSameKeysAsTheDefault() throws IOException {
        // A key missing from a translation silently falls back to en_us,
        // so shipping an untranslated addition looks fine at runtime until
        // a player on that locale reads English. This makes it visible.
        Set<String> expected = leafKeys(bundledLocale("en_us"));
        for (String locale : List.of("es_us", "pt_br", "de_de")) {
            assertEquals(expected, leafKeys(bundledLocale(locale)),
                    locale + ".yml should define exactly the same keys as en_us.yml");
        }
    }

    /** A bundled locale file as shipped in the jar, off the test classpath. */
    private static YamlConfiguration bundledLocale(String locale) throws IOException {
        try (InputStream in = Messages.class.getResourceAsStream("/lang/" + locale + ".yml")) {
            assertNotNull(in, "bundled lang/" + locale + ".yml should be on the classpath");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    /** Just the value-bearing keys, skipping the sections holding them. */
    private static Set<String> leafKeys(YamlConfiguration config) {
        return config.getKeys(true).stream()
                .filter(key -> !config.isConfigurationSection(key))
                .collect(Collectors.toCollection(TreeSet::new));
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
        public void saveDeath(UUID uuid, me.hcfcore.core.staff.Death death) {
        }

        @Override
        public java.util.List<me.hcfcore.core.staff.Death> loadDeaths(UUID uuid, int limit) {
            return java.util.List.of();
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
        public void saveDeath(UUID uuid, me.hcfcore.core.staff.Death death) {
        }

        @Override
        public java.util.List<me.hcfcore.core.staff.Death> loadDeaths(UUID uuid, int limit) {
            return java.util.List.of();
        }

        @Override
        public void close() {
        }
    }
}
