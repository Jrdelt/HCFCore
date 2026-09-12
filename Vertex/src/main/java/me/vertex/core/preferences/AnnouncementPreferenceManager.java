package me.vertex.core.preferences;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Delivers optional broadcasts and owns every persistent player communication toggle. */
public final class AnnouncementPreferenceManager implements Listener {
    private final Plugin plugin;
    private final AnnouncementPreferenceStorage storage;
    private final Messages messages;
    private final Map<UUID, Set<AnnouncementCategory>> disabled = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> pendingWrites = ConcurrentHashMap.newKeySet();
    private final Object stateLock = new Object();
    private final Map<UUID, CompletableFuture<Void>> writeChains = new java.util.HashMap<>();
    private final Map<UUID, Object> loading = new java.util.HashMap<>();
    private final Map<UUID, Map<AnnouncementCategory, Boolean>> pendingEdits = new java.util.HashMap<>();

    public AnnouncementPreferenceManager(Plugin plugin, AnnouncementPreferenceStorage storage, Messages messages) {
        this.plugin = plugin;
        this.storage = storage;
        this.messages = messages;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        loadPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        synchronized (stateLock) {
            UUID uuid = event.getPlayer().getUniqueId();
            loading.remove(uuid);
            pendingEdits.remove(uuid);
            disabled.remove(uuid);
        }
    }

    /** Refreshes each connection, preserving all stored categories and explicit edits made while loading. */
    public void loadPlayer(UUID uuid) {
        synchronized (stateLock) {
        if (loading.containsKey(uuid)) return;
        Object token = new Object();
        loading.put(uuid, token);
        pendingEdits.put(uuid, new java.util.EnumMap<>(AnnouncementCategory.class));
        enqueue(uuid, () -> {
            try {
                EnumSet<AnnouncementCategory> stored = storage.loadDisabled(uuid);
                synchronized (stateLock) {
                    if (loading.get(uuid) != token) return;
                    pendingEdits.getOrDefault(uuid, Map.of()).forEach((category, enabled) -> {
                        if (enabled) stored.remove(category); else stored.add(category);
                    });
                    disabled.put(uuid, Set.copyOf(stored));
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not load announcement preferences for " + uuid, e);
            } finally {
                synchronized (stateLock) {
                    if (loading.remove(uuid, token)) pendingEdits.remove(uuid);
                }
            }
        });
        }
    }

    public boolean isEnabled(UUID uuid, AnnouncementCategory category) {
        Set<AnnouncementCategory> values = disabled.getOrDefault(uuid, Set.of());
        if (values.contains(category)) {
            return false;
        }
        // Notifications is the master opt-out for optional Vertex broadcasts;
        // focused announcement toggles still let players opt out of only one
        // category while leaving the rest enabled.
        return !isOptionalAnnouncement(category) || !values.contains(AnnouncementCategory.NOTIFICATIONS);
    }

    private static boolean isOptionalAnnouncement(AnnouncementCategory category) {
        return category == AnnouncementCategory.COINFLIPS || category == AnnouncementCategory.KOTH
                || category == AnnouncementCategory.OUTPOST || category == AnnouncementCategory.MINING
                || category == AnnouncementCategory.SERVER;
    }

    /** Changes the runtime value first so a click immediately affects the next broadcast. */
    public boolean toggle(UUID uuid, AnnouncementCategory category) {
        synchronized (stateLock) {
        Set<AnnouncementCategory> prior = disabled.getOrDefault(uuid, Set.of());
        EnumSet<AnnouncementCategory> next = prior.isEmpty()
                ? EnumSet.noneOf(AnnouncementCategory.class) : EnumSet.copyOf(prior);
        boolean enabled;
        if (next.remove(category)) {
            enabled = true;
        } else {
            next.add(category);
            enabled = false;
        }
        disabled.put(uuid, Set.copyOf(next));
        if (loading.containsKey(uuid)) pendingEdits.get(uuid).put(category, enabled);
        boolean savedEnabled = enabled;
        enqueue(uuid, () -> {
            try {
                me.vertex.core.storage.SqlRetry.run(plugin, "player preference save",
                        () -> storage.save(uuid, category, savedEnabled));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not save announcement preferences for " + uuid, e);
            }
        });
        return enabled;
        }
    }

    /** Caller holds stateLock; each read/write completes before its successor starts. */
    private void enqueue(UUID uuid, Runnable operation) {
        CompletableFuture<Void> previous = writeChains.getOrDefault(uuid, CompletableFuture.completedFuture(null));
        CompletableFuture<Void> next = previous.handle((ignored, error) -> null).thenRunAsync(operation);
        writeChains.put(uuid, next);
        next.whenComplete((ignored, error) -> {
            synchronized (stateLock) { writeChains.remove(uuid, next); }
        });
        track(next);
    }

    public void broadcast(AnnouncementCategory category, String messageKey, String... placeholders) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            send(player, category, messageKey, placeholders);
        }
        Bukkit.getConsoleSender().sendMessage(messages.get(Bukkit.getConsoleSender(), messageKey, placeholders));
    }

    public void send(Player player, AnnouncementCategory category, String messageKey, String... placeholders) {
        if (isEnabled(player.getUniqueId(), category)) {
            player.sendMessage(messages.get(player, messageKey, placeholders));
        }
    }

    public void awaitWrites() {
        try { CompletableFuture.allOf(pendingWrites.toArray(CompletableFuture[]::new)).get(10, java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception error) { plugin.getLogger().log(Level.WARNING, "Could not finish preference writes during shutdown", error); }
    }

    private void track(CompletableFuture<?> future) {
        pendingWrites.add(future);
        future.whenComplete((ignored, error) -> pendingWrites.remove(future));
    }
}
