package me.vertex.core.enchant;

import me.vertex.core.storage.Storage;
import me.vertex.core.user.User;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/**
 * Persisted rune cooldowns, keyed by player UUID + rune id -- surviving
 * logout, unlike a plain in-memory map. Mirrors {@code
 * me.vertex.core.ability.AbilityManager}'s cooldown mechanics exactly
 * (per-key write chains so concurrent writes for the same player+rune can't
 * race, a pending-writes set drained by {@link #awaitWrites()} at shutdown)
 * rather than reusing that class directly, since it's tied to its own
 * {@code Ability} config type and can't hold rune data.
 *
 * <p>Reuses the existing {@code ability_cooldowns} SQL table verbatim --
 * every rune cooldown is persisted as {@code Storage#saveAbilityCooldown}
 * with an id prefixed {@code "rune:"}. {@link me.vertex.core.user.UserManager#load}
 * already merges every row from that table into {@link User}'s cooldown map
 * under an {@code "ability:"} namespace, so a persisted rune cooldown for
 * enchant id {@code "dasher"} ends up readable from {@link User} as {@code
 * "ability:rune:dasher"} -- this class is the only place that key shape
 * needs to be known.
 */
public final class RuneCooldownStore {

    private final Plugin plugin;
    private final Storage storage;
    private final Set<CompletableFuture<Void>> pendingWrites = ConcurrentHashMap.newKeySet();
    private final Map<String, CompletableFuture<Void>> writeChains = new ConcurrentHashMap<>();

    public RuneCooldownStore(Plugin plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public long remainingMillis(User user, String enchantId) {
        if (user == null || enchantId == null) {
            return 0L;
        }
        return Math.max(0L, user.getCooldownExpiry(key(enchantId)) - System.currentTimeMillis());
    }

    public boolean isOnCooldown(User user, String enchantId) {
        return remainingMillis(user, enchantId) > 0L;
    }

    public void start(Player player, User user, String enchantId, long durationMillis) {
        if (player == null || user == null || enchantId == null || durationMillis <= 0L) {
            return;
        }
        long expiry = System.currentTimeMillis() + durationMillis;
        user.setCooldownExpiry(key(enchantId), expiry);

        UUID uuid = player.getUniqueId();
        String storageId = storageId(enchantId);
        String writeKey = uuid + ":" + storageId;
        CompletableFuture<Void> write = writeChains.compute(writeKey, (ignored, previous) ->
                (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous.handle((done, error) -> null))
                        .thenRunAsync(() -> {
                            try {
                                storage.saveAbilityCooldown(uuid, storageId, expiry);
                            } catch (Exception e) {
                                plugin.getLogger().log(Level.WARNING,
                                        "Failed to persist rune cooldown for " + uuid, e);
                            }
                        }));
        pendingWrites.add(write);
        write.whenComplete((ignored, error) -> {
            pendingWrites.remove(write);
            writeChains.remove(writeKey, write);
        });
    }

    public void awaitWrites() {
        try {
            CompletableFuture.allOf(pendingWrites.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            plugin.getLogger().warning("Timed out waiting for rune cooldown writes during shutdown.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed while waiting for rune cooldown writes.", e);
        }
    }

    private static String storageId(String enchantId) {
        return "rune:" + enchantId.toLowerCase(Locale.ROOT);
    }

    /** The key {@link User}'s merged cooldown map actually stores this under -- see the class doc. */
    private static String key(String enchantId) {
        return "ability:" + storageId(enchantId);
    }
}
