package me.vertex.core.preferences;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
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

    public AnnouncementPreferenceManager(Plugin plugin, AnnouncementPreferenceStorage storage, Messages messages) {
        this.plugin = plugin;
        this.storage = storage;
        this.messages = messages;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        loadPlayer(event.getPlayer().getUniqueId());
    }

    /** Loads once per connection; a click before the async read finishes wins over the stale read. */
    public void loadPlayer(UUID uuid) {
        if (disabled.containsKey(uuid)) {
            return;
        }
        CompletableFuture<Void> load = CompletableFuture.runAsync(() -> {
            try {
                EnumSet<AnnouncementCategory> stored = storage.loadDisabled(uuid);
                disabled.putIfAbsent(uuid, Set.copyOf(stored));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not load announcement preferences for " + uuid, e);
            }
        });
        track(load);
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
        boolean savedEnabled = enabled;
        CompletableFuture<Void> save = CompletableFuture.runAsync(() -> {
            try {
                storage.save(uuid, category, savedEnabled);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not save announcement preferences for " + uuid, e);
            }
        });
        track(save);
        return enabled;
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
        CompletableFuture.allOf(pendingWrites.toArray(CompletableFuture[]::new)).join();
    }

    private void track(CompletableFuture<?> future) {
        pendingWrites.add(future);
        future.whenComplete((ignored, error) -> pendingWrites.remove(future));
    }
}
