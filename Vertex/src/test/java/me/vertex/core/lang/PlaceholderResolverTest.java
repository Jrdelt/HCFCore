package me.vertex.core.lang;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Only exercises placeholders that don't reach into the native faction
 * state ({@code {faction}}, {@code {ftop}}, {@code {power}},
 * {@code {faction_role}}, {@code {fplayers_online}}) -- unlike the other
 * integrations this resolver touches (LuckPerms, Essentials, Vault, all
 * genuinely optional and therefore defensive about not being installed),
 * The native faction service starts only during a full plugin boot, so
 * this unit test deliberately leaves it unavailable. The guard this test
 * relies on (skip a placeholder's
 * lookup entirely when the template doesn't contain it) is exactly what
 * keeps a template with no faction tokens safe to resolve here at all.
 */
class PlaceholderResolverTest {

    private ServerMock server;
    private PlayerMock player;
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MM/dd");

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer("Steve");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void resolvesPlayerName() {
        String resolved = resolve("Hello, {name}!");
        assertEquals("Hello, Steve!", resolved);
    }

    @Test
    void resolvesOnlineCount() {
        assertEquals("5 online", resolve("{online} online", 5));
    }

    @Test
    void resolvesExperienceLevel() {
        player.setLevel(42);
        assertEquals("Level 42", resolve("Level {exp}"));
    }

    @Test
    void unusedFactionTokenNeverTouchesFactionsHook() {
        // If the guard were missing, this would throw before ever reaching
        // the assertion -- the native faction service is not booted here.
        assertEquals("plain text, no placeholders", resolve("plain text, no placeholders"));
    }

    @Test
    void ftopPassthroughOnlyAppliesWhenTemplateAsksForIt() {
        assertEquals("rank #3", resolve("rank #{ftop}", 0, "3"));
        // Not present in the template -- factionTop is accepted but ignored.
        assertEquals("no rank shown", resolve("no rank shown", 0, "3"));
    }

    @Test
    void blankFactionTopResolvesToEmptyString() {
        assertEquals("rank #", resolve("rank #{ftop}", 0, null));
    }

    @Test
    void customPlaceholdersOnlySubstituteWhenPresentInTemplate() {
        Map<String, String> custom = Map.of("repair", "12s");
        assertEquals("Repair: 12s", PlaceholderResolver.resolve(player, "Repair: {repair}", 0, null, dateFormatter, custom));
        // A custom key with no matching token in the template is simply unused.
        assertEquals("nothing here", PlaceholderResolver.resolve(player, "nothing here", 0, null, dateFormatter, custom));
    }

    @Test
    void unknownPlaceholdersAreLeftLiteral() {
        assertEquals("<not-a-real-token>", resolve("<not-a-real-token>"));
    }

    @Test
    void multipleOccurrencesOfTheSameTokenAllResolve() {
        assertEquals("Steve and Steve again", resolve("{name} and {name} again"));
    }

    private String resolve(String template) {
        return resolve(template, 0, null);
    }

    private String resolve(String template, int onlineCount) {
        return resolve(template, onlineCount, null);
    }

    private String resolve(String template, int onlineCount, String factionTop) {
        return PlaceholderResolver.resolve(player, template, onlineCount, factionTop, dateFormatter, Map.of());
    }
}
