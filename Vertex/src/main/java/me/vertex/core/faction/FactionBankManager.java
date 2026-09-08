package me.vertex.core.faction;

import dev.kitteh.factions.Faction;
import dev.kitteh.factions.Factions;
import dev.kitteh.factions.event.FactionDisbandEvent;
import dev.kitteh.factions.event.FactionAutoDisbandEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Durable balances with per-faction serialized writes. A memory value changes
 * only after its database upsert succeeds, so a failed write cannot silently
 * consume money or experience from a player.
 */
public final class FactionBankManager implements Listener {
    private final Plugin plugin;
    private final FactionBankStorage storage;
    private final Map<Integer, Balance> balances = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<Void>> writeChains = new ConcurrentHashMap<>();
    private final java.util.Set<CompletableFuture<?>> pendingWrites = ConcurrentHashMap.newKeySet();

    public FactionBankManager(Plugin plugin, FactionBankStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public void load() {
        try {
            for (FactionBankStorage.StoredBank stored : storage.loadAll()) {
                balances.put(stored.factionId(), new Balance(Math.max(0D, stored.money()),
                        Math.max(0L, stored.experience()), Math.max(0L, stored.tnt())));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load faction bank balances from the database.", e);
        }
    }

    /**
     * Moves any balance held in FactionsUUID's native TNT bank into Vertex's
     * own, once, the first time a server runs a build where Vertex owns TNT.
     *
     * <p>Vertex took ownership because the native bank is a bare field with
     * no save hook and a ceiling read from FactionsUUID's own config, which
     * a Vertex capacity upgrade cannot influence. Without this migration the
     * switchover would read every faction's existing TNT as zero and quietly
     * destroy it. The native value is cleared as it is copied so the two
     * banks can never both claim the same TNT.
     */
    public void migrateNativeTntBanks() {
        File marker = new File(plugin.getDataFolder(), ".tnt-bank-migrated");
        if (marker.exists()) {
            return;
        }
        int migrated = 0;
        for (Faction faction : Factions.factions().all()) {
            int nativeTnt = faction.tntBank();
            if (nativeTnt <= 0) {
                continue;
            }
            faction.tntBank(0);
            depositTnt(faction.id(), nativeTnt, Long.MAX_VALUE);
            migrated++;
        }
        awaitWrites();
        try {
            if (!marker.createNewFile()) {
                plugin.getLogger().warning("Could not mark the TNT bank migration complete; it may repeat next start.");
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not write the TNT bank migration marker.", e);
        }
        if (migrated > 0) {
            plugin.getLogger().info("Moved " + migrated + " faction TNT bank(s) from FactionsUUID into Vertex storage.");
        }
    }

    public double money(int factionId) {
        return balance(factionId).money();
    }

    public long experience(int factionId) {
        return balance(factionId).experience();
    }

    public long tnt(int factionId) {
        return balance(factionId).tnt();
    }

    public CompletableFuture<Boolean> depositMoney(int factionId, double amount) {
        if (!Double.isFinite(amount) || amount <= 0D) {
            return CompletableFuture.completedFuture(false);
        }
        return mutate(factionId, previous -> new Balance(previous.money() + amount, previous.experience(), previous.tnt()));
    }

    public CompletableFuture<Boolean> withdrawMoney(int factionId, double amount) {
        if (!Double.isFinite(amount) || amount <= 0D) {
            return CompletableFuture.completedFuture(false);
        }
        return mutate(factionId, previous -> previous.money() + 0.000001D < amount ? null
                : new Balance(Math.max(0D, previous.money() - amount), previous.experience(), previous.tnt()));
    }

    public CompletableFuture<Boolean> depositExperience(int factionId, long amount) {
        if (amount <= 0L) {
            return CompletableFuture.completedFuture(false);
        }
        return mutate(factionId, previous -> new Balance(previous.money(), previous.experience() > Long.MAX_VALUE - amount
                ? Long.MAX_VALUE : previous.experience() + amount, previous.tnt()));
    }

    public CompletableFuture<Boolean> withdrawExperience(int factionId, long amount) {
        if (amount <= 0L) {
            return CompletableFuture.completedFuture(false);
        }
        return mutate(factionId, previous -> previous.experience() < amount ? null
                : new Balance(previous.money(), previous.experience() - amount, previous.tnt()));
    }

    /**
     * @param capacity the faction's TNT ceiling; the deposit is refused
     *                 outright rather than partially filled when it would
     *                 exceed this, so the caller never has to hand items back.
     */
    public CompletableFuture<Boolean> depositTnt(int factionId, long amount, long capacity) {
        if (amount <= 0L) {
            return CompletableFuture.completedFuture(false);
        }
        return mutate(factionId, previous -> previous.tnt() > capacity - amount ? null
                : new Balance(previous.money(), previous.experience(), previous.tnt() + amount));
    }

    public CompletableFuture<Boolean> withdrawTnt(int factionId, long amount) {
        if (amount <= 0L) {
            return CompletableFuture.completedFuture(false);
        }
        return mutate(factionId, previous -> previous.tnt() < amount ? null
                : new Balance(previous.money(), previous.experience(), previous.tnt() - amount));
    }

    private Balance balance(int factionId) {
        return balances.getOrDefault(factionId, Balance.EMPTY);
    }

    private CompletableFuture<Boolean> mutate(int factionId, Function<Balance, Balance> mutation) {
        final CompletableFuture<Boolean> request;
        synchronized (writeChains) {
            CompletableFuture<Void> previous = writeChains.getOrDefault(factionId, CompletableFuture.completedFuture(null));
            request = previous.handle((ignored, error) -> null).thenApplyAsync(ignored -> {
                Balance next = mutation.apply(balance(factionId));
                if (next == null) {
                    return false;
                }
                try {
                    storage.save(factionId, next.money(), next.experience(), next.tnt());
                    balances.put(factionId, next);
                    return true;
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to save faction bank balance to the database.", e);
                    return false;
                }
            });
            CompletableFuture<Void> chain = request.handle((ignored, error) -> null);
            writeChains.put(factionId, chain);
            chain.whenComplete((ignored, error) -> writeChains.remove(factionId, chain));
        }
        track(request);
        return request;
    }

    @EventHandler
    public void onFactionDisband(FactionDisbandEvent event) {
        deleteFaction(event.getFaction().id());
    }

    @EventHandler
    public void onFactionAutoDisband(FactionAutoDisbandEvent event) {
        deleteFaction(event.getFaction().id());
    }

    private void deleteFaction(int factionId) {
        synchronized (writeChains) {
            CompletableFuture<Void> previous = writeChains.getOrDefault(factionId, CompletableFuture.completedFuture(null));
            CompletableFuture<Void> deletion = previous.handle((ignored, error) -> null).thenRunAsync(() -> {
                try {
                    storage.delete(factionId);
                    balances.remove(factionId);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to delete faction bank balance from the database.", e);
                }
            });
            writeChains.put(factionId, deletion);
            deletion.whenComplete((ignored, error) -> writeChains.remove(factionId, deletion));
            track(deletion);
        }
    }

    private void track(CompletableFuture<?> write) {
        pendingWrites.add(write);
        write.whenComplete((ignored, error) -> pendingWrites.remove(write));
    }

    public void awaitWrites() {
        try {
            CompletableFuture.allOf(pendingWrites.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed while waiting for faction bank writes.", e);
        }
    }

    private record Balance(double money, long experience, long tnt) {
        private static final Balance EMPTY = new Balance(0D, 0L, 0L);
    }
}
