package me.vertex.core.enchant.binds;

import me.vertex.core.enchant.EnchantDefinition;
import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneCooldownStore;
import me.vertex.core.enchant.RuneEquipment;
import me.vertex.core.enchant.RuneFormatting;
import me.vertex.core.enchant.RuneProtection;
import me.vertex.core.enchant.listener.RuneEffectListener;
import me.vertex.core.lang.Messages;
import me.vertex.core.preferences.AnnouncementCategory;
import me.vertex.core.preferences.AnnouncementPreferenceManager;
import me.vertex.core.user.User;
import me.vertex.core.user.UserManager;
import me.vertex.core.zone.ZoneManager;
import me.vertex.core.zone.ZoneRegion;
import me.vertex.core.zone.ZoneType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shared, sequential per-player ability queue: activating a bind
 * enqueues each of its (up to 3) rune ids as one ordered entry apiece, and
 * one shared repeating task (not one task per player -- see {@code
 * me.vertex.core.faction.RallyManager}'s identical single-timer idiom)
 * pops one entry per player every {@code binds.ability-delay-seconds}
 * (default 2, one global value, never per-bind/preset) and runs it through
 * {@link RuneEffectListener#manuallyActivate}, the exact same Rune Pipeline
 * every passive proc uses.
 */
public final class BindQueue {
    private static final int MAX_PENDING = PlayerBinds.MAX_BIND_INDEX * PlayerBinds.MAX_SLOTS_PER_BIND;
    private static final long TICK_PERIOD = 5L;

    private record Entry(int bindIndex, String enchantId) {
    }

    private record PlayerQueue(Deque<Entry> entries, Set<Integer> queuedBinds, long[] nextAttemptAt) {
        private PlayerQueue() {
            this(new ArrayDeque<>(), new HashSet<>(), new long[] {0L});
        }
    }

    private final Plugin plugin;
    private final EnchantManager enchants;
    private final RuneEffectListener effects;
    private final RuneCooldownStore cooldowns;
    private final UserManager users;
    private final AnnouncementPreferenceManager announcementPreferences;
    private final Messages messages;
    private final Map<UUID, PlayerQueue> queues = new ConcurrentHashMap<>();
    private volatile long delayMillis = 2_000L;
    private volatile ZoneManager zones;
    private BukkitTask task;

    public BindQueue(Plugin plugin, EnchantManager enchants, RuneEffectListener effects, RuneCooldownStore cooldowns,
            UserManager users, AnnouncementPreferenceManager announcementPreferences, Messages messages) {
        this.plugin = plugin;
        this.enchants = enchants;
        this.effects = effects;
        this.cooldowns = cooldowns;
        this.users = users;
        this.announcementPreferences = announcementPreferences;
        this.messages = messages;
    }

    public void setDelaySeconds(double seconds) {
        this.delayMillis = Math.max(0L, Math.round(seconds * 1000D));
    }

    public void setZoneManager(ZoneManager zones) {
        this.zones = zones;
    }

    public void start() {
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_PERIOD, TICK_PERIOD);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        queues.clear();
    }

    /** @return true if the bind was queued; false if the same bind is already pending (spam guard) or the queue is full. */
    public boolean enqueue(Player player, int bindIndex, java.util.List<String> runeIds) {
        if (runeIds.isEmpty()) {
            return false;
        }
        PlayerQueue queue = queues.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerQueue());
        synchronized (queue) {
            if (queue.queuedBinds().contains(bindIndex)) {
                return false;
            }
            if (queue.entries().size() + runeIds.size() > MAX_PENDING) {
                player.sendMessage(messages.get(player, "binds.queue-full"));
                return false;
            }
            queue.queuedBinds().add(bindIndex);
            for (String runeId : runeIds) {
                queue.entries().addLast(new Entry(bindIndex, runeId));
            }
        }
        return true;
    }

    /** Hard-stop cancellation: dies, teleports, opens another inventory, logs out, changes world, is staff-frozen. */
    public void clear(UUID uuid) {
        PlayerQueue queue = queues.get(uuid);
        if (queue != null) {
            synchronized (queue) {
                queue.entries().clear();
                queue.queuedBinds().clear();
            }
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, PlayerQueue> entry : queues.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            processOne(player, entry.getValue(), now);
        }
    }

    private void processOne(Player player, PlayerQueue queue, long now) {
        Entry next;
        synchronized (queue) {
            if (queue.entries().isEmpty() || now < queue.nextAttemptAt()[0]) {
                return;
            }
            next = queue.entries().pollFirst();
            if (next == null) {
                return;
            }
            queue.nextAttemptAt()[0] = now + delayMillis;
            if (queue.entries().stream().noneMatch(e -> e.bindIndex() == next.bindIndex())) {
                queue.queuedBinds().remove(next.bindIndex());
            }
        }
        attempt(player, next.enchantId());
    }

    private boolean tagsAllowedInCurrentZone(Player player, EnchantDefinition definition) {
        ZoneManager zoneManager = zones;
        if (zoneManager == null) {
            return true;
        }
        ZoneRegion region = zoneManager.regionAt(player.getLocation());
        if (region == null) {
            return true;
        }
        ZoneType zone = region.type();
        return RuneProtection.tagsAllowed(definition, zoneManager.blockedRuneTagNames(zone), zoneManager.allowedRuneTagNames(zone));
    }

    private void attempt(Player player, String enchantId) {
        EnchantDefinition definition = enchants.definition(enchantId);
        if (definition == null) {
            return;
        }
        String coloredName = RuneFormatting.coloredNameRaw(enchants.tierOf(enchantId), definition.displayName());
        if (!tagsAllowedInCurrentZone(player, definition)) {
            if (announcementPreferences.isEnabled(player.getUniqueId(), AnnouncementCategory.RUNE_COOLDOWN_MESSAGES)) {
                player.sendMessage(messages.get(player, "binds.rune-blocked-in-zone", "enchant", coloredName));
            }
            return;
        }
        int level = RuneEquipment.highestAvailableLevel(player, enchants, enchantId);
        if (level <= 0) {
            if (announcementPreferences.isEnabled(player.getUniqueId(), AnnouncementCategory.RUNE_COOLDOWN_MESSAGES)) {
                player.sendMessage(messages.get(player, "binds.rune-unavailable", "enchant", coloredName));
            }
            return;
        }
        User user = users == null ? null : users.get(player.getUniqueId());
        if (user != null && cooldowns.isOnCooldown(user, enchantId)) {
            if (announcementPreferences.isEnabled(player.getUniqueId(), AnnouncementCategory.RUNE_COOLDOWN_MESSAGES)) {
                long remaining = cooldowns.remainingMillis(user, enchantId);
                player.sendMessage(messages.get(player, "binds.rune-on-cooldown", "enchant", coloredName,
                        "seconds", String.format(java.util.Locale.ROOT, "%.1f", remaining / 1000D)));
            }
            return;
        }
        EnchantDefinition.Level levelConfig = definition.level(level);
        boolean fired = effects.manuallyActivate(player, definition, levelConfig);
        if (fired && announcementPreferences.isEnabled(player.getUniqueId(), AnnouncementCategory.RUNE_ACTIVATION_MESSAGES)) {
            player.sendMessage(messages.get(player, "binds.rune-activated", "enchant", coloredName));
        }
    }

}
