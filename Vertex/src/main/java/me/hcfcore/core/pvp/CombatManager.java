package me.hcfcore.core.pvp;

import me.hcfcore.core.lang.MessageFormatter;
import me.hcfcore.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

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
    private final Messages messages;
    private volatile long combatDurationMillis;
    private volatile boolean logoutPenalty;
    private volatile long updateIntervalTicks;
    private volatile String actionbarVsServer;
    private volatile String actionbarVsPlayer;
    private volatile String actionbarVsUnknown;
    private final Map<UUID, Long> taggedUntil = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> opponents = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> clickTimestamps = new ConcurrentHashMap<>();
    private BukkitTask task;

    public CombatManager(Plugin plugin, Messages messages, int combatSeconds, boolean logoutPenalty,
                          int updateIntervalTicks, String actionbarVsServer, String actionbarVsPlayer,
                          String actionbarVsUnknown) {
        this.plugin = plugin;
        this.messages = messages;
        this.combatDurationMillis = combatSeconds * 1000L;
        this.logoutPenalty = logoutPenalty;
        this.updateIntervalTicks = Math.max(1, updateIntervalTicks);
        this.actionbarVsServer = actionbarVsServer;
        this.actionbarVsPlayer = actionbarVsPlayer;
        this.actionbarVsUnknown = actionbarVsUnknown;
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
    public void reconfigure(int combatSeconds, boolean logoutPenalty, int updateIntervalTicks,
                             String actionbarVsServer, String actionbarVsPlayer, String actionbarVsUnknown) {
        this.combatDurationMillis = combatSeconds * 1000L;
        this.logoutPenalty = logoutPenalty;
        this.actionbarVsServer = actionbarVsServer;
        this.actionbarVsPlayer = actionbarVsPlayer;
        this.actionbarVsUnknown = actionbarVsUnknown;
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
        clickTimestamps.clear();
    }

    public void tag(Player a, Player b) {
        long until = System.currentTimeMillis() + combatDurationMillis;
        UUID oldA = opponents.put(a.getUniqueId(), b.getUniqueId());
        UUID oldB = opponents.put(b.getUniqueId(), a.getUniqueId());
        clearStaleOpponent(a.getUniqueId(), oldA, b.getUniqueId());
        clearStaleOpponent(b.getUniqueId(), oldB, a.getUniqueId());
        taggedUntil.put(a.getUniqueId(), until);
        taggedUntil.put(b.getUniqueId(), until);
    }

    private void clearStaleOpponent(UUID playerId, UUID oldOpponentId, UUID newOpponentId) {
        if (oldOpponentId != null && !oldOpponentId.equals(newOpponentId)) {
            opponents.remove(oldOpponentId, playerId);
            taggedUntil.remove(oldOpponentId);
        }
    }

    /**
     * Testing-only: tags a single player against the synthetic "Server"
     * opponent so the action bar can be exercised solo, without a second
     * online player to spar against.
     */
    public void tagAgainstServer(Player player) {
        long until = System.currentTimeMillis() + combatDurationMillis;
        UUID oldOpponent = opponents.put(player.getUniqueId(), SERVER_UUID);
        clearStaleOpponent(player.getUniqueId(), oldOpponent, SERVER_UUID);
        taggedUntil.put(player.getUniqueId(), until);
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
                opponent.sendActionBar(messages.get(opponent, "combat.no-longer-in-combat"));
            }
        }
    }

    /**
     * Records a left-click arm swing for CPS tracking. Tracked for every
     * online player unconditionally (not just tagged ones) so the action
     * bar has real recent history the instant a tag starts, not a cold 0.
     */
    public void recordClick(UUID uuid) {
        long now = System.currentTimeMillis();
        Deque<Long> timestamps = clickTimestamps.computeIfAbsent(uuid, id -> new ConcurrentLinkedDeque<>());
        timestamps.addLast(now);
        pruneClicks(timestamps, now);
    }

    /**
     * Clicks in roughly the last second -- i.e. CPS.
     */
    public int getCps(UUID uuid) {
        Deque<Long> timestamps = clickTimestamps.get(uuid);
        if (timestamps == null) {
            return 0;
        }
        pruneClicks(timestamps, System.currentTimeMillis());
        return timestamps.size();
    }

    /**
     * Drops all click history for a player. Only call this on disconnect --
     * unlike clear(), this is not part of combat-tag state, so /uncombat
     * must never touch it.
     */
    public void forgetPlayer(UUID uuid) {
        clickTimestamps.remove(uuid);
    }

    private static void pruneClicks(Deque<Long> timestamps, long now) {
        Long oldest;
        while ((oldest = timestamps.peekFirst()) != null && now - oldest > 1000L) {
            timestamps.pollFirst();
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
                    player.sendActionBar(messages.get(player, "combat.no-longer-in-combat"));
                }
                continue;
            }

            if (player != null) {
                player.sendActionBar(render(player, entry.getValue() - now));
            }
        }
    }

    /**
     * Renders one of the three pvp.actionbar.* templates from config.yml,
     * substituting {@code {seconds}}/{@code {health}} as pre-colored
     * MiniMessage fragments (the countdown fades red->green, health is
     * always red -- neither is a fixed color a template string could
     * express on its own) and the rest as plain text.
     */
    private Component render(Player player, long remainingMillis) {
        long remainingSeconds = (remainingMillis + 999) / 1000;
        double timeFraction = combatDurationMillis <= 0 ? 0 : clamp01((double) remainingMillis / combatDurationMillis);
        String secondsFragment = "<#" + toHex(gradient(1 - timeFraction)) + "><bold>(" + remainingSeconds + "s)</bold>";

        UUID opponentId = opponents.get(player.getUniqueId());
        String template;
        if (SERVER_UUID.equals(opponentId)) {
            template = actionbarVsServer
                    .replace("{seconds}", secondsFragment)
                    .replace("{your_cps}", String.valueOf(getCps(player.getUniqueId())));
        } else {
            Player opponent = opponentId == null ? null : Bukkit.getPlayer(opponentId);
            if (opponent != null && opponent.isOnline()) {
                double health = Math.max(0, opponent.getHealth());
                String healthFragment = "<red>❤ " + String.format(Locale.ROOT, "%.0f", health) + "</red>";
                template = actionbarVsPlayer
                        .replace("{seconds}", secondsFragment)
                        .replace("{health}", healthFragment)
                        // A player's name can't contain MiniMessage syntax --
                        // Minecraft restricts the character set -- so no
                        // escaping is needed, same as elsewhere in the plugin.
                        .replace("{opponent}", opponent.getName())
                        .replace("{your_cps}", String.valueOf(getCps(player.getUniqueId())))
                        .replace("{their_cps}", String.valueOf(getCps(opponentId)));
            } else {
                template = actionbarVsUnknown
                        .replace("{seconds}", secondsFragment)
                        .replace("{your_cps}", String.valueOf(getCps(player.getUniqueId())));
            }
        }

        return MessageFormatter.deserialize(template);
    }

    private static String toHex(TextColor color) {
        return String.format(Locale.ROOT, "%06X", color.value());
    }

    private static TextColor gradient(double greenFraction) {
        double t = clamp01(greenFraction);
        int red = (int) Math.round(255 * (1 - t));
        int green = (int) Math.round(255 * t);
        return TextColor.color(red, green, 0);
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
