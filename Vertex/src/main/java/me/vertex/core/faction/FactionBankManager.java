package me.vertex.core.faction;

import me.vertex.core.factions.FactionMember;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.factions.event.FactionLifecycleEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private final TntDepositWal depositWal;

    public FactionBankManager(Plugin plugin, FactionBankStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.depositWal = new TntDepositWal(plugin.getDataFolder());
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
        replayDepositJournal();
    }

    /**
     * Durably records that {@code amount} TNT is about to leave {@code
     * owner}'s inventory for a bank deposit, before it is actually removed
     * (ISS-12). Neither the credit nor a compensating refund is durable yet
     * at this point -- both are still to come from the caller -- so a crash
     * here must not be silent. Clear the returned operation id with
     * {@link #clearDepositIntent} once either one lands.
     */
    String journalDepositIntent(UUID owner, int factionId, long amount) {
        String operationId = UUID.randomUUID().toString();
        try {
            depositWal.put(new TntDepositWal.Entry(operationId, owner, factionId, amount));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not journal TNT deposit intent for " + owner
                    + " (faction " + factionId + ", amount " + amount + ") before removing the source. A crash "
                    + "before the credit or a refund lands would be unrecoverable and unlogged.", e);
        }
        return operationId;
    }

    /** Called once a journaled deposit's credit or compensating refund is confirmed. */
    void clearDepositIntent(String operationId) {
        try {
            depositWal.remove(operationId);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not clear the resolved TNT deposit journal entry "
                    + operationId + "; it will be harmlessly reported again on the next restart.", e);
        }
    }

    /**
     * Reports any TNT deposit that was mid-flight -- its source already
     * removed, but this JVM never learned whether the credit or a refund
     * completed -- when the last shutdown was not graceful. There is
     * deliberately no automatic recovery: crediting an already-credited
     * deposit or refunding an already-refunded one would itself duplicate
     * value, and the bank balance carries no per-operation receipt to tell
     * the two cases apart. A staff member must check the actual balance and
     * the player's actual inventory/mail before acting.
     */
    private void replayDepositJournal() {
        List<TntDepositWal.Entry> entries;
        try {
            entries = depositWal.load();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not read the TNT deposit journal.", e);
            return;
        }
        for (TntDepositWal.Entry entry : entries) {
            plugin.getLogger().severe("Unresolved TNT deposit from before the last shutdown: owner="
                    + entry.owner() + " faction=" + entry.factionId() + " amount=" + entry.amount()
                    + "; staff must verify whether the bank credit or a refund landed and reconcile manually.");
            clearDepositIntent(entry.operationId());
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

    /**
     * Debits the balance, then hands the TNT straight to the actor if
     * they're still online. {@link me.vertex.core.storage.ItemGiver} has no
     * offline-delivery path by design (the old durable delivery inbox was
     * removed in favor of a plain give that only ever targets an online
     * player) -- so if the actor disconnects in the gap between the debit
     * committing and this scheduled callback running, the TNT is refunded
     * back into the faction's balance instead of being silently destroyed.
     * They simply need to run the withdrawal again once back online.
     */
    public CompletableFuture<Boolean> withdrawTntToInbox(Player actor, int factionId, long amount, String action) {
        if(amount<=0||amount>36L*64||!me.vertex.core.storage.InventoryAccess.ready(plugin,actor))
            return CompletableFuture.completedFuture(false);
        FactionMember member=FactionsHook.service().member(actor.getUniqueId());
        if(member==null||member.factionId()!=factionId)return CompletableFuture.completedFuture(false);
        var authority=new Authority(actor.getUniqueId(),member.role(),action,
                FactionsHook.service().configuredActionDefaultFor(member.role(),action));
        return mutate(factionId,authority,previous->previous.tnt()<amount?null:
                new Balance(previous.money(),previous.experience(),previous.tnt()-amount))
                .thenApply(success->{
                    if(Boolean.TRUE.equals(success)){
                        org.bukkit.Bukkit.getScheduler().runTask(plugin,()->{
                            Player online=org.bukkit.Bukkit.getPlayer(authority.actor());
                            if(online!=null){
                                me.vertex.core.storage.ItemGiver.give(online,
                                        java.util.List.of(new org.bukkit.inventory.ItemStack(org.bukkit.Material.TNT,(int)amount)));
                            } else {
                                mutate(factionId,authority,previous->new Balance(previous.money(),
                                        previous.experience(),previous.tnt()+amount));
                            }
                        });
                    }
                    return success;
                });
    }

    /**
     * Applies an audited admin bank adjustment inside the same per-faction
     * serialized write as every normal bank transaction.  In particular, TNT
     * capacity is checked against the balance that is actually committed,
     * rather than a potentially stale value read by the command handler.
     */
    public CompletableFuture<Boolean> adjustForAdmin(int factionId, BankType type,
            AdminOperation operation, double moneyAmount, long wholeAmount, long tntCapacity) {
        if (type == null || operation == null || tntCapacity < 0L
                || (type == BankType.MONEY && (!Double.isFinite(moneyAmount) || moneyAmount < 0D))
                || (type != BankType.MONEY && wholeAmount < 0L)) {
            return CompletableFuture.completedFuture(false);
        }
        return mutate(factionId, previous -> switch (type) {
            case MONEY -> adjustMoney(previous, operation, moneyAmount);
            case EXPERIENCE -> adjustExperience(previous, operation, wholeAmount);
            case TNT -> adjustTnt(previous, operation, wholeAmount, tntCapacity);
        });
    }

    private static Balance adjustMoney(Balance previous, AdminOperation operation, double amount) {
        double next = switch (operation) {
            case SET -> amount;
            case ADD -> previous.money() + amount;
            case TAKE -> Math.max(0D, previous.money() - amount);
        };
        return Double.isFinite(next) && next >= 0D
                ? new Balance(next, previous.experience(), previous.tnt()) : null;
    }

    private static Balance adjustExperience(Balance previous, AdminOperation operation, long amount) {
        long next;
        if (operation == AdminOperation.SET) next = amount;
        else if (operation == AdminOperation.ADD) {
            if (previous.experience() > Long.MAX_VALUE - amount) return null;
            next = previous.experience() + amount;
        } else next = Math.max(0L, previous.experience() - amount);
        return new Balance(previous.money(), next, previous.tnt());
    }

    private static Balance adjustTnt(Balance previous, AdminOperation operation, long amount, long capacity) {
        long next;
        if (operation == AdminOperation.SET) next = amount;
        else if (operation == AdminOperation.ADD) {
            if (previous.tnt() > capacity - amount) return null;
            next = previous.tnt() + amount;
        } else next = Math.max(0L, previous.tnt() - amount);
        if (next < 0L || next > capacity) return null;
        return new Balance(previous.money(), previous.experience(), next);
    }

    public enum BankType {
        MONEY,
        EXPERIENCE,
        TNT
    }

    public enum AdminOperation {
        SET,
        ADD,
        TAKE
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
        return mutate(factionId,authority,mutation,connection -> {});
    }

    private CompletableFuture<Boolean> mutate(int factionId, Authority authority,
            Function<Balance, Balance> mutation, FactionBankStorage.TransactionEffect effect) {
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
                            ? storage.mutate(factionId, storedMutation, effect)
                            : storage.mutateAuthorized(factionId, authority.actor(), authority.role(),
                                    authority.action(), authority.defaultAllowed(), storedMutation, effect);
                    if (committed.isEmpty()) return false;
                    FactionBankStorage.StoredBank stored = committed.get();
                    Balance next = new Balance(stored.money(), stored.experience(), stored.tnt());
                    balances.put(factionId, next);
                    try { mutationPublisher.run(); }
                    catch(RuntimeException error) { plugin.getLogger().log(Level.WARNING,"Bank committed but cache invalidation failed",error); }
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
