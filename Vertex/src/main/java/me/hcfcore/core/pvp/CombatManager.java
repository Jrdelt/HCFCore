package me.hcfcore.core.pvp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ephemeral combat-tag tracking. No DB round-trip: tags don't need to
 * survive a restart, so this stays entirely in memory. The action bar
 * refreshes fast (a handful of times a second, not once a second) since
 * showing a live opponent health bar is the whole point.
 */
public final class CombatManager {

    /** Sentinel opponent id for /combattag's server-test mode -- never a real player's UUID. */
    public static final UUID SERVER_UUID = new UUID(0L, 0L);

    private final Plugin plugin;
    private volatile long combatDurationMillis;
    private volatile boolean logoutPenalty;
    private volatile long updateIntervalTicks;
    private final Map<UUID, Long> taggedUntil = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> opponents = new ConcurrentHashMap<>();
    private BukkitTask task;

    public CombatManager(Plugin plugin, int combatSeconds, boolean logoutPenalty, int updateIntervalTicks) {
        this.plugin = plugin;
        this.combatDurationMillis = combatSeconds * 1000L;
        this.logoutPenalty = logoutPenalty;
        this.updateIntervalTicks = Math.max(1, updateIntervalTicks);
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, updateIntervalTicks, updateIntervalTicks);
    }

    /**
     * Applies newly-reloaded pvp.* config values in place, so callers that
     * hold a reference to this instance (listeners, commands) don't go
     * stale on a config reload. Existing tags keep their already-computed
     * expiry; only new tags and the action-bar cadence pick up the change.
     */
    public void reconfigure(int combatSeconds, boolean logoutPenalty, int updateIntervalTicks) {
        this.combatDurationMillis = combatSeconds * 1000L;
        this.logoutPenalty = logoutPenalty;
        long newInterval = Math.max(1, updateIntervalTicks);
        if (newInterval != this.updateIntervalTicks) {
            this.updateIntervalTicks = newInterval;
            if (task != null) {
                task.cancel();
                task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, newInterval, newInterval);
            }
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
        taggedUntil.clear();
        opponents.clear();
    }

    public void tag(Player a, Player b) {
        long until = System.currentTimeMillis() + combatDurationMillis;
        taggedUntil.put(a.getUniqueId(), until);
        taggedUntil.put(b.getUniqueId(), until);
        opponents.put(a.getUniqueId(), b.getUniqueId());
        opponents.put(b.getUniqueId(), a.getUniqueId());
    }

    /**
     * Testing-only: tags a single player against the synthetic "Server"
     * opponent so the action bar can be exercised solo, without a second
     * online player to spar against.
     */
    public void tagAgainstServer(Player player) {
        long until = System.currentTimeMillis() + combatDurationMillis;
        taggedUntil.put(player.getUniqueId(), until);
        opponents.put(player.getUniqueId(), SERVER_UUID);
    }

    public boolean isTagged(UUID uuid) {
        Long until = taggedUntil.get(uuid);
        return until != null && until > System.currentTimeMillis();
    }

    public boolean logoutPenaltyEnabled() {
        return logoutPenalty;
    }

    /**
     * Clears the given player's tag and, if they were mutually tagged
     * against someone, clears that opponent's tag too -- otherwise the
     * opponent is left thinking they're still in combat with someone who
     * is no longer tagged back.
     */
    public void clear(UUID uuid) {
        UUID opponentId = opponents.remove(uuid);
        taggedUntil.remove(uuid);

        if (opponentId != null && !SERVER_UUID.equals(opponentId) && uuid.equals(opponents.get(opponentId))) {
            opponents.remove(opponentId);
            taggedUntil.remove(opponentId);
            Player opponent = Bukkit.getPlayer(opponentId);
            if (opponent != null) {
                opponent.sendActionBar(Component.text("You are no longer in combat.", NamedTextColor.GREEN));
            }
        }
    }

    public long remainingMillis(UUID uuid) {
        Long until = taggedUntil.get(uuid);
        if (until == null) {
            return 0L;
        }
        return Math.max(0L, until - System.currentTimeMillis());
    }

    public UUID getOpponentId(UUID uuid) {
        return opponents.get(uuid);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> iterator = taggedUntil.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            UUID uuid = entry.getKey();
            Player player = Bukkit.getPlayer(uuid);

            if (entry.getValue() <= now) {
                iterator.remove();
                opponents.remove(uuid);
                if (player != null) {
                    player.sendActionBar(Component.text("You are no longer in combat.", NamedTextColor.GREEN));
                }
                continue;
            }

            if (player != null) {
                player.sendActionBar(render(player, entry.getValue() - now));
            }
        }
    }

    private Component render(Player player, long remainingMillis) {
        long remainingSeconds = (remainingMillis + 999) / 1000;
        double timeFraction = combatDurationMillis <= 0 ? 0 : clamp01((double) remainingMillis / combatDurationMillis);
        TextColor timeColor = gradient(1 - timeFraction);

        Component result = Component.text("⚔ ", NamedTextColor.DARK_RED)
                .append(Component.text(remainingSeconds + "s", timeColor, TextDecoration.BOLD));

        UUID opponentId = opponents.get(player.getUniqueId());
        if (SERVER_UUID.equals(opponentId)) {
            result = result
                    .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("Server", NamedTextColor.WHITE, TextDecoration.ITALIC));
            return result;
        }

        Player opponent = opponentId == null ? null : Bukkit.getPlayer(opponentId);
        if (opponent != null && opponent.isOnline()) {
            double health = Math.max(0, opponent.getHealth());
            var maxHealthAttribute = opponent.getAttribute(Attribute.MAX_HEALTH);
            double maxHealth = Math.max(1, maxHealthAttribute == null ? 20.0 : maxHealthAttribute.getValue());
            TextColor healthColor = gradient(clamp01(health / maxHealth));
            TextColor pingColor = pingColor(opponent.getPing());

            result = result
                    .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(opponent.getName(), NamedTextColor.WHITE))
                    .append(Component.text("  ❤ ", NamedTextColor.GRAY))
                    .append(Component.text(String.format(Locale.ROOT, "%.1f", health), healthColor))
                    .append(Component.text("  ⚡ ", NamedTextColor.GRAY))
                    .append(Component.text(opponent.getPing() + "ms", pingColor));
        }

        return result;
    }

    private static TextColor gradient(double greenFraction) {
        double t = clamp01(greenFraction);
        int red = (int) Math.round(255 * (1 - t));
        int green = (int) Math.round(255 * t);
        return TextColor.color(red, green, 0);
    }

    private static TextColor pingColor(int ping) {
        if (ping <= 100) {
            return NamedTextColor.GREEN;
        }
        if (ping <= 200) {
            return NamedTextColor.YELLOW;
        }
        return NamedTextColor.RED;
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
