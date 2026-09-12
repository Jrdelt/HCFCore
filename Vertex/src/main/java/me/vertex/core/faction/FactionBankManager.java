package me.vertex.core.faction;

import me.vertex.core.factions.FactionMember;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.factions.event.FactionLifecycleEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.entity.Player;

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
    private volatile AuditLogger auditLogger = (factionId, action, actor, details) -> { };
    private volatile Runnable mutationPublisher = () -> { };

    public FactionBankManager(Plugin plugin, FactionBankStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public void load() {
        try {
            Map<Integer, Balance> loaded = new java.util.HashMap<>();
            for (FactionBankStorage.StoredBank stored : storage.loadAll()) {
                loaded.put(stored.factionId(), new Balance(Math.max(0D, stored.money()),
                        Math.max(0L, stored.experience()), Math.max(0L, stored.tnt())));
            }
            balances.clear();
            balances.putAll(loaded);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load faction bank balances from the database.", e);
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

    public void setAuditLogger(AuditLogger logger) {
        auditLogger = logger == null ? (factionId, action, actor, details) -> { } : logger;
    }

    public void setMutationPublisher(Runnable publisher) {
        mutationPublisher = publisher == null ? () -> { } : publisher;
    }

    /** Refreshes display caches after another shard publishes a bank change. */
    public void refreshAsync() {
        CompletableFuture<Void> refresh = CompletableFuture.runAsync(this::load);
        track(refresh);
    }

    public void audit(int factionId, String action, Player actor, String details) {
        auditLogger.log(factionId, action, actor, details);
    }

    public CompletableFuture<Boolean> depositMoney(int factionId, double amount) {
        if (!Double.isFinite(amount) || amount <= 0D) {
            return CompletableFuture.completedFuture(false);
        }
        return mutate(factionId, previous -> new Balance(previous.money() + amount, previous.experience(), previous.tnt()));
    }

    public CompletableFuture<Boolean> depositMoney(Player actor, int factionId, double amount, String action) {
        if (!Double.isFinite(amount) || amount <= 0D) return CompletableFuture.completedFuture(false);
        return mutateAuthorized(actor, factionId, action,
                previous -> new Balance(previous.money() + amount, previous.experience(), previous.tnt()));
    }

    public CompletableFuture<Boolean> withdrawMoney(int factionId, double amount) {
        if (!Double.isFinite(amount) || amount <= 0D) {
            return CompletableFuture.completedFuture(false);
        }
        return mutate(factionId, previous -> previous.money() + 0.000001D < amount ? null
                : new Balance(Math.max(0D, previous.money() - amount), previous.experience(), previous.tnt()));
    }

    public CompletableFuture<Boolean> withdrawMoney(Player actor, int factionId, double amount, String action) {
        if (!Double.isFinite(amount) || amount <= 0D) return CompletableFuture.completedFuture(false);
        return mutateAuthorized(actor, factionId, action,
                previous -> previous.money() + 0.000001D < amount ? null
                        : new Balance(Math.max(0D, previous.money() - amount),
                                previous.experience(), previous.tnt()));
    }

    public CompletableFuture<Boolean> depositExperience(int factionId, long amount) {
        if (amount <= 0L) {
            return CompletableFuture.completedFuture(false);
        }
        return mutate(factionId, previous -> new Balance(previous.money(), previous.experience() > Long.MAX_VALUE - amount
                ? Long.MAX_VALUE : previous.experience() + amount, previous.tnt()));
    }

    public CompletableFuture<Boolean> depositExperience(Player actor, int factionId, long amount, String action) {
        if (amount <= 0L) return CompletableFuture.completedFuture(false);
        return mutateAuthorized(actor, factionId, action,
                previous -> new Balance(previous.money(),
                        previous.experience() > Long.MAX_VALUE - amount
                                ? Long.MAX_VALUE : previous.experience() + amount,
                        previous.tnt()));
    }

    public CompletableFuture<Boolean> withdrawExperience(int factionId, long amount) {
        if (amount <= 0L) {
            return CompletableFuture.completedFuture(false);
        }
        return mutate(factionId, previous -> previous.experience() < amount ? null
                : new Balance(previous.money(), previous.experience() - amount, previous.tnt()));
    }

    public CompletableFuture<Boolean> withdrawExperience(Player actor, int factionId, long amount, String action) {
        if (amount <= 0L) return CompletableFuture.completedFuture(false);
        return mutateAuthorized(actor, factionId, action,
                previous -> previous.experience() < amount ? null
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

    public CompletableFuture<Boolean> depositTnt(Player actor, int factionId, long amount,
            long capacity, String action) {
        if (amount <= 0L) return CompletableFuture.completedFuture(false);
        return mutateAuthorized(actor, factionId, action,
                previous -> previous.tnt() > capacity - amount ? null
                        : new Balance(previous.money(), previous.experience(), previous.tnt() + amount));
    }

    public CompletableFuture<Boolean> withdrawTnt(int factionId, long amount) {
        if (amount <= 0L) {
            return CompletableFuture.completedFuture(false);
        }
        return mutate(factionId, previous -> previous.tnt() < amount ? null
                : new Balance(previous.money(), previous.experience(), previous.tnt() - amount));
    }

    public CompletableFuture<Boolean> withdrawTnt(Player actor, int factionId, long amount, String action) {
        if (amount <= 0L) return CompletableFuture.completedFuture(false);
        return mutateAuthorized(actor, factionId, action,
                previous -> previous.tnt() < amount ? null
                        : new Balance(previous.money(), previous.experience(), previous.tnt() - amount));
    }

    /** Absolute admin update used by /fa after its permission/audit checks. */
    public CompletableFuture<Boolean> set(int factionId, double money, long experience, long tnt) {
        if (!Double.isFinite(money) || money < 0D || experience < 0L || tnt < 0L) {
            return CompletableFuture.completedFuture(false);
        }
        return mutate(factionId, ignored -> new Balance(money, experience, tnt));
    }

    private Balance balance(int factionId) {
        return balances.getOrDefault(factionId, Balance.EMPTY);
    }

    private CompletableFuture<Boolean> mutate(int factionId, Function<Balance, Balance> mutation) {
        return mutate(factionId, null, mutation);
    }

    private CompletableFuture<Boolean> mutateAuthorized(Player actor, int factionId, String action,
            Function<Balance, Balance> mutation) {
        FactionMember member = actor == null ? null
                : FactionsHook.service().member(actor.getUniqueId());
        if (member == null || member.factionId() != factionId) {
            return CompletableFuture.completedFuture(false);
        }
        Authority authority = new Authority(actor.getUniqueId(), member.role(), action,
                FactionsHook.service().configuredActionDefaultFor(member.role(), action));
        return mutate(factionId, authority, mutation);
    }

    private CompletableFuture<Boolean> mutate(int factionId, Authority authority,
            Function<Balance, Balance> mutation) {
        final CompletableFuture<Boolean> request;
        synchronized (writeChains) {
            CompletableFuture<Void> previous = writeChains.getOrDefault(factionId, CompletableFuture.completedFuture(null));
            request = previous.handle((ignored, error) -> null).thenApplyAsync(ignored -> {
                try {
                    Function<FactionBankStorage.StoredBank, FactionBankStorage.StoredBank> storedMutation = stored -> {
                        Balance next = mutation.apply(new Balance(stored.money(), stored.experience(), stored.tnt()));
                        return next == null ? null : new FactionBankStorage.StoredBank(factionId,
                                next.money(), next.experience(), next.tnt());
                    };
                    java.util.Optional<FactionBankStorage.StoredBank> committed = authority == null
                            ? storage.mutate(factionId, storedMutation)
                            : storage.mutateAuthorized(factionId, authority.actor(), authority.role(),
                                    authority.action(), authority.defaultAllowed(), storedMutation);
                    if (committed.isEmpty()) return false;
                    FactionBankStorage.StoredBank stored = committed.get();
                    Balance next = new Balance(stored.money(), stored.experience(), stored.tnt());
                    balances.put(factionId, next);
                    mutationPublisher.run();
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
    public void onFactionDisband(FactionLifecycleEvent event) {
        if (event.action() == FactionLifecycleEvent.Action.DISBAND) deleteFaction(event.faction().id());
    }

    private void deleteFaction(int factionId) {
        synchronized (writeChains) {
            CompletableFuture<Void> previous = writeChains.getOrDefault(factionId, CompletableFuture.completedFuture(null));
            CompletableFuture<Void> deletion = previous.handle((ignored, error) -> null).thenRunAsync(() -> {
                try {
                    storage.delete(factionId);
                    balances.remove(factionId);
                    mutationPublisher.run();
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

    private record Authority(java.util.UUID actor, me.vertex.core.factions.FactionRole role,
                             String action, boolean defaultAllowed) {}

    @FunctionalInterface
    public interface AuditLogger {
        void log(int factionId, String action, Player actor, String details);
    }
}
