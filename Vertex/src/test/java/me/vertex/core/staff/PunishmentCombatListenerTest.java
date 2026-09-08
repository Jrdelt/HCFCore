package me.vertex.core.staff;

import me.vertex.core.lang.Messages;
import me.vertex.core.pvp.CombatManager;
import me.vertex.core.storage.Storage;
import me.vertex.core.user.UserManager;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A staff kick/ban must not become a free escape hatch out of a fight. */
class PunishmentCombatListenerTest {

    private ServerMock server;
    private PluginMock plugin;
    private CombatManager combatManager;
    private PunishmentCombatListener listener;
    private PlayerMock staff;
    private PlayerMock target;
    private PlayerMock attacker;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        UserManager userManager = new UserManager(plugin, new NoOpStorage());
        Messages messages = new Messages(plugin, userManager);
        messages.load();
        combatManager = new CombatManager(plugin, messages, 15, 5, true, 4, "{seconds}", "{seconds}", "{seconds}");

        plugin.getConfig().set("staff.punishment-combat-guard.enabled", true);
        plugin.getConfig().set("staff.punishment-combat-guard.commands", List.of("kick", "ban", "tempban"));

        listener = new PunishmentCombatListener(plugin, combatManager, messages);
        staff = server.addPlayer("Staff");
        target = server.addPlayer("Target");
        attacker = server.addPlayer("Attacker");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private boolean dispatch(String message) {
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(staff, message);
        listener.onCommand(event);
        return event.isCancelled();
    }

    @Test
    void blocksKickAndBanWhileTargetIsTagged() {
        combatManager.tag(target, attacker);
        assertTrue(dispatch("/kick Target reason"));
        assertTrue(dispatch("/ban Target cheating"));
        assertTrue(dispatch("/tempban Target 1d"));
    }

    @Test
    void allowsPunishmentWhenTargetIsNotTagged() {
        assertFalse(dispatch("/kick Target reason"));
    }

    @Test
    void allowsPunishmentOnceTheTagHasBeenCleared() {
        combatManager.tag(target, attacker);
        assertTrue(dispatch("/kick Target"));

        combatManager.clear(target.getUniqueId());
        assertFalse(dispatch("/kick Target"));
    }

    /** Silencing someone does not let them leave a fight, so mutes stay unguarded. */
    @Test
    void neverGuardsMutes() {
        combatManager.tag(target, attacker);
        assertFalse(dispatch("/mute Target spam"));
    }

    @Test
    void ignoresCommandsThatAreNotConfigured() {
        combatManager.tag(target, attacker);
        assertFalse(dispatch("/heal Target"));
    }

    @Test
    void matchesNamespacedAliases() {
        combatManager.tag(target, attacker);
        assertTrue(dispatch("/essentials:kick Target"));
    }

    @Test
    void bypassPermissionLetsTheCommandThrough() {
        combatManager.tag(target, attacker);
        staff.addAttachment(plugin, PunishmentCombatListener.BYPASS_PERMISSION, true);
        assertFalse(dispatch("/kick Target"));
    }

    @Test
    void guardCanBeDisabledEntirely() {
        plugin.getConfig().set("staff.punishment-combat-guard.enabled", false);
        combatManager.tag(target, attacker);
        assertFalse(dispatch("/kick Target"));
    }

    /**
     * An offline target has no live tag; their logout is already handled by
     * the combat-logging penalty, so the punishment must not be blocked
     * forever waiting for a tag that can never expire.
     */
    @Test
    void allowsPunishingAnOfflineTarget() {
        combatManager.tag(target, attacker);
        target.disconnect();
        assertFalse(dispatch("/kick Target"));
    }

    @Test
    void ignoresCommandsWithNoTargetArgument() {
        combatManager.tag(target, attacker);
        assertFalse(dispatch("/kick"));
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
        public void saveDeath(UUID uuid, Death death) {
        }

        @Override
        public List<Death> loadDeaths(UUID uuid, int limit) {
            return List.of();
        }

        @Override
        public void close() {
        }
    }
}
