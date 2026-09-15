package me.vertex.core.enchant;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /runepref notify <individual|summary|off>}: INDIVIDUAL sends one
 * message per successful Auto-Incineration; SUMMARY accumulates them and
 * flushes one combined message after 5 seconds of inactivity (mirroring
 * the single-shared-timer idiom used throughout this codebase's other
 * per-player-UI-refresh features, rather than a delayed task per event);
 * OFF suppresses the chat message only -- rewards and the incineration
 * itself are unaffected by this setting either way.
 */
public final class AutoIncinerationNotifier implements Listener {

    public static final String MODE_KEY = "auto-notify-mode";
    private static final long SUMMARY_DEBOUNCE_MILLIS = 5_000L;
    private static final long TICK_PERIOD = 20L;

    public enum Mode {
        INDIVIDUAL, SUMMARY, OFF
    }

    private record PendingSummary(int count, Map<EnchantManager.Currency, Double> totals, long lastEventAt) {
        PendingSummary withAdded(EnchantManager.Currency currency, double amount) {
            Map<EnchantManager.Currency, Double> next = new EnumMap<>(totals);
            next.merge(currency, amount, Double::sum);
            return new PendingSummary(count + 1, next, System.currentTimeMillis());
        }

        static PendingSummary of(EnchantManager.Currency currency, double amount) {
            Map<EnchantManager.Currency, Double> totals = new EnumMap<>(EnchantManager.Currency.class);
            totals.put(currency, amount);
            return new PendingSummary(1, totals, System.currentTimeMillis());
        }
    }

    private final Plugin plugin;
    private final RunePreferenceManager preferences;
    private final Messages messages;
    private final Map<UUID, PendingSummary> pending = new ConcurrentHashMap<>();
    private BukkitTask task;

    public AutoIncinerationNotifier(Plugin plugin, RunePreferenceManager preferences, Messages messages) {
        this.plugin = plugin;
        this.preferences = preferences;
        this.messages = messages;
    }

    public Mode mode(UUID uuid) {
        try {
            return Mode.valueOf(preferences.get(uuid, RunePreferenceManager.GLOBAL, MODE_KEY, Mode.INDIVIDUAL.name()));
        } catch (IllegalArgumentException e) {
            return Mode.INDIVIDUAL;
        }
    }

    public void setMode(UUID uuid, Mode mode) {
        preferences.set(uuid, RunePreferenceManager.GLOBAL, MODE_KEY, mode.name());
    }

    public void notifyIncinerated(Player player, String runeName, int level, EnchantManager.Currency currency, double reward) {
        Mode mode = mode(player.getUniqueId());
        if (mode == Mode.OFF) {
            return;
        }
        if (mode == Mode.INDIVIDUAL) {
            player.sendMessage(messages.get(player, "rune.auto-notify-individual", "enchant", runeName,
                    "level", level > 0 ? String.valueOf(level) : "-", "reward", format(currency, reward)));
            return;
        }
        UUID uuid = player.getUniqueId();
        pending.merge(uuid, PendingSummary.of(currency, reward), (previous, added) -> previous.withAdded(currency, reward));
        ensureTaskRunning();
    }

    private void ensureTaskRunning() {
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_PERIOD, TICK_PERIOD);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        pending.clear();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (UUID uuid : List.copyOf(pending.keySet())) {
            PendingSummary summary = pending.get(uuid);
            if (summary != null && now - summary.lastEventAt() >= SUMMARY_DEBOUNCE_MILLIS) {
                flush(uuid);
            }
        }
    }

    /** Flushed immediately on quit (before anything is cleared) so nothing pending is lost. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        flush(event.getPlayer().getUniqueId());
    }

    private void flush(UUID uuid) {
        PendingSummary summary = pending.remove(uuid);
        if (summary == null) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return;
        }
        List<String> parts = new java.util.ArrayList<>();
        for (Map.Entry<EnchantManager.Currency, Double> entry : summary.totals().entrySet()) {
            parts.add(format(entry.getKey(), entry.getValue()));
        }
        player.sendMessage(messages.get(player, "rune.auto-notify-summary",
                "count", String.valueOf(summary.count()), "rewards", String.join(" and ", parts)));
    }

    private static String format(EnchantManager.Currency currency, double amount) {
        return currency == EnchantManager.Currency.XP_LEVELS
                ? ((long) Math.ceil(amount)) + " XP"
                : EconomyHook.format(amount);
    }
}
