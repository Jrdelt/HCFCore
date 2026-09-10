package me.vertex.core.dupe;

import me.vertex.core.item.TrackedItemIds;
import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Opt-in persistent item-instance IDs and the server-authoritative suspected
 * dupe queue. IDs stay in PDC only; nothing about them is shown in normal
 * item lore, and this class deliberately never deletes or confiscates items.
 */
public final class DupeManager {
    public static final String ALERT_PERMISSION = "vertex.dupe.alert";
    private static final int MAX_CASE_DETAILS = 2_000;

    private final Plugin plugin;
    private final DupeStorage storage;
    private final Messages messages;
    private final TrackedItemIds trackedItemIds;
    private final File file;
    private final NamespacedKey identityKey;
    private final NamespacedKey wandKey;
    private final NamespacedKey blueprintKey;
    private final NamespacedKey collectorKey;
    private final NamespacedKey spawnerKey;
    private final AtomicBoolean scanQueued = new AtomicBoolean();
    private final Set<CompletableFuture<?>> pendingWrites = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Main-thread state for a whole loaded-chunk reconciliation cycle. */
    private final Map<String, List<Evidence>> reconciliationObserved = new LinkedHashMap<>();
    private List<Chunk> reconciliationChunks = List.of();
    private int reconciliationCursor;

    private volatile boolean enabled;
    private volatile Set<Material> trackedMaterials = Set.of();
    private volatile int scanIntervalTicks;
    private volatile int pageSize;
    private volatile int loadedChunksPerPass;

    public DupeManager(Plugin plugin, DupeStorage storage, Messages messages, TrackedItemIds trackedItemIds) {
        this.plugin = plugin;
        this.storage = storage;
        this.messages = messages;
        this.trackedItemIds = trackedItemIds;
        this.file = new File(plugin.getDataFolder(), "dupes.yml");
        this.identityKey = new NamespacedKey(plugin, "tracked_item_id");
        this.wandKey = new NamespacedKey(plugin, "wand_tier");
        this.blueprintKey = new NamespacedKey(plugin, "blueprint_template");
        this.collectorKey = new NamespacedKey(plugin, "chunk_collector");
        this.spawnerKey = new NamespacedKey(plugin, "spawner_type");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("dupes.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        enabled = config.getBoolean("enabled", true);
        scanIntervalTicks = Math.max(20, Math.min(20 * 60, config.getInt("scan-interval-ticks", 100)));
        pageSize = Math.max(1, Math.min(50, config.getInt("staff-list-page-size", 10)));
        loadedChunksPerPass = Math.max(1, Math.min(64, config.getInt("loaded-chunks-per-pass", 8)));
        reconciliationObserved.clear();
        reconciliationChunks = List.of();
        reconciliationCursor = 0;
        Set<Material> configured = EnumSet.noneOf(Material.class);
        for (String raw : config.getStringList("tracked-materials")) {
            Material material = Material.matchMaterial(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
            if (material == null || material.isAir()) {
                plugin.getLogger().warning("dupes.yml: ignoring unknown tracked material '" + raw + "'.");
                continue;
            }
            configured.add(material);
        }
        trackedMaterials = Set.copyOf(configured);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int scanIntervalTicks() {
        return scanIntervalTicks;
    }

    public int pageSize() {
        return pageSize;
    }

    /** Adds an ID to one eligible, unstacked instance and returns it; otherwise returns null. */
    public String ensureIdentity(ItemStack item) {
        if (!enabled || item == null || item.getType().isAir() || item.getAmount() != 1) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        String existing = meta.getPersistentDataContainer().get(identityKey, PersistentDataType.STRING);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        if (!shouldTrack(item, meta)) {
            return null;
        }
        String created = UUID.randomUUID().toString();
        meta.getPersistentDataContainer().set(identityKey, PersistentDataType.STRING, created);
        item.setItemMeta(meta);
        return created;
    }

    /** Existing identity IDs are always retained even if the current config no longer targets the material. */
    public String identityOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(identityKey, PersistentDataType.STRING);
    }

    public void scheduleScan() {
        if (!enabled || !scanQueued.compareAndSet(false, true)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                scanReconciliationPass();
            } finally {
                scanQueued.set(false);
            }
        });
    }

    /** Complete same-tick inventory snapshot; only simultaneous copies open a case. */
    public void scanOnlineInventories() {
        if (!enabled) {
            return;
        }
        Map<String, List<Evidence>> observed = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            scanInventory(player.getInventory(), player.getUniqueId().toString(), player.getName(), "inventory", observed);
            scanInventory(player.getEnderChest(), player.getUniqueId().toString(), player.getName(), "ender-chest", observed);
        }
        inspectObserved(observed);
    }

    /** Include an opened container in the next authoritative snapshot. */
    public void scanInventorySoon(Inventory inventory, String source) {
        if (!enabled || inventory == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Map<String, List<Evidence>> observed = new LinkedHashMap<>();
            scanInventory(inventory, null, "container", source, observed);
            for (Player player : Bukkit.getOnlinePlayers()) {
                scanInventory(player.getInventory(), player.getUniqueId().toString(), player.getName(), "inventory", observed);
            }
            inspectObserved(observed);
        });
    }

    /**
     * Reconciles every currently loaded chunk over several scheduled passes.
     * It never forces a chunk load and never scans all chunks in one tick: a
     * completed cycle is the only point at which evidence is compared, so an
     * item in two unopened containers is still caught.
     */
    private void scanReconciliationPass() {
        if (!enabled) {
            return;
        }
        if (reconciliationChunks.isEmpty()) {
            reconciliationObserved.clear();
            reconciliationCursor = 0;
            reconciliationChunks = Bukkit.getWorlds().stream()
                    .flatMap(world -> java.util.Arrays.stream(world.getLoadedChunks()))
                    .sorted(Comparator.comparing((Chunk chunk) -> chunk.getWorld().getName())
                            .thenComparingInt(Chunk::getX).thenComparingInt(Chunk::getZ))
                    .toList();
            for (Player player : Bukkit.getOnlinePlayers()) {
                scanInventory(player.getInventory(), player.getUniqueId().toString(), player.getName(), "inventory",
                        reconciliationObserved);
                scanInventory(player.getEnderChest(), player.getUniqueId().toString(), player.getName(), "ender-chest",
                        reconciliationObserved);
            }
        }

        int remaining = loadedChunksPerPass;
        while (remaining-- > 0 && reconciliationCursor < reconciliationChunks.size()) {
            Chunk chunk = reconciliationChunks.get(reconciliationCursor++);
            if (chunk.isLoaded()) {
                scanChunk(chunk, reconciliationObserved);
            }
        }
        if (reconciliationCursor >= reconciliationChunks.size()) {
            inspectObserved(reconciliationObserved);
            reconciliationObserved.clear();
            reconciliationChunks = List.of();
            reconciliationCursor = 0;
        }
    }

    private void scanChunk(Chunk chunk, Map<String, List<Evidence>> observed) {
        World world = chunk.getWorld();
        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof TileState tile) {
                scanTileIdentity(tile, state, observed);
            }
            if (state instanceof InventoryHolder holder) {
                String source = "container:" + world.getName() + ':' + state.getX() + ':' + state.getY() + ':' + state.getZ();
                scanInventory(holder.getInventory(), null, "container", source, observed);
            }
        }
        for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
            if (entity instanceof Item dropped && dropped.isValid()) {
                scanWorldItem(dropped, observed);
            }
        }
    }

    /** Includes placed high-value block entities such as Vertex spawner members. */
    private void scanTileIdentity(TileState tile, BlockState state, Map<String, List<Evidence>> observed) {
        String identity = tile.getPersistentDataContainer().get(identityKey, PersistentDataType.STRING);
        if (identity == null || identity.isBlank()) return;
        String source = "block-entity:" + state.getWorld().getName() + ':' + state.getX() + ':' + state.getY()
                + ':' + state.getZ();
        observed.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(
                new Evidence(null, "placed block", source, -1, state.getType().name()));
    }

    private void scanWorldItem(Item dropped, Map<String, List<Evidence>> observed) {
        ItemStack stack = dropped.getItemStack();
        String identity = identityOf(stack);
        if (identity == null || identity.isBlank()) {
            identity = ensureIdentity(stack);
            if (identity != null) {
                dropped.setItemStack(stack);
            }
        }
        if (identity == null || identity.isBlank()) {
            return;
        }
        org.bukkit.Location location = dropped.getLocation();
        String source = "world-item:" + location.getWorld().getName() + ':' + location.getBlockX() + ':'
                + location.getBlockY() + ':' + location.getBlockZ() + ':' + dropped.getUniqueId();
        observed.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(
                new Evidence(null, "world item", source, -1, stack.getType().name()));
    }

    private void inspectObserved(Map<String, List<Evidence>> observed) {
        observed.forEach((identity, evidence) -> {
            List<Evidence> distinct = evidence.stream().distinct().toList();
            if (distinct.size() > 1) {
                openCase(identity, distinct);
            }
        });
    }

    private void scanInventory(Inventory inventory, String holderUuid, String holderName, String source,
            Map<String, List<Evidence>> observed) {
        if (inventory == null) {
            return;
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            String identity = identityOf(item);
            if (identity == null || identity.isBlank()) {
                identity = ensureIdentity(item);
                // Inventory#getItem implementations are allowed to return a
                // copy. Set it back explicitly so a just-assigned identity
                // survives every inventory implementation, not only CraftBukkit's.
                if (identity != null) {
                    inventory.setItem(slot, item);
                }
            }
            if (identity != null && !identity.isBlank()) {
                observed.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(
                        new Evidence(holderUuid, holderName, source, slot, item.getType().name()));
            }
        }
    }

    private void openCase(String identity, List<Evidence> evidence) {
        List<Evidence> sorted = evidence.stream().sorted(Comparator.comparing(Evidence::fingerprintPart)).toList();
        String fingerprint = identity + "|" + sorted.stream().map(Evidence::fingerprintPart).reduce((a, b) -> a + "|" + b)
                .orElse("unknown");
        Evidence primary = sorted.getFirst();
        String details = sorted.toString();
        if (details.length() > MAX_CASE_DETAILS) {
            details = details.substring(0, MAX_CASE_DETAILS);
        }
        DupeCase entry = new DupeCase(UUID.randomUUID().toString(), fingerprint, primary.holderUuid(), primary.holderName(),
                identity, primary.material(), "duplicate-item-id", details, "OPEN", System.currentTimeMillis(), null, 0L, null);
        CompletableFuture<Void> write = CompletableFuture.runAsync(() -> {
            try {
                if (storage.create(entry)) {
                    Bukkit.getScheduler().runTask(plugin, () -> alertStaff(entry));
                }
            } catch (Exception error) {
                plugin.getLogger().log(Level.SEVERE, "Failed to persist suspected-dupe case " + entry.id(), error);
            }
        });
        track(write);
    }

    private void alertStaff(DupeCase entry) {
        plugin.getLogger().warning("Suspected duplicate item " + entry.itemId() + " detected; case " + entry.id()
                + " is open. Holder: " + entry.holderName() + ".");
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission(ALERT_PERMISSION)) {
                staff.sendMessage(messages.get(staff, "dupe.alert", "case", entry.id(), "player", entry.holderName()));
            }
        }
    }

    public void notifyStaffOnJoin(Player player) {
        if (!player.hasPermission(ALERT_PERMISSION)) {
            return;
        }
        CompletableFuture<Void> request = CompletableFuture.runAsync(() -> {
            try {
                int count = storage.unresolvedCount();
                if (count > 0) {
                    Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(messages.get(player,
                            "dupe.pending-alert", "count", String.valueOf(count))));
                }
            } catch (Exception error) {
                plugin.getLogger().log(Level.WARNING, "Failed to load unresolved dupe-case count.", error);
            }
        });
        track(request);
    }

    public CompletableFuture<List<DupeCase>> openCases(int page) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return storage.openCases(Math.max(0, page) * pageSize, pageSize);
            } catch (Exception error) {
                plugin.getLogger().log(Level.WARNING, "Failed to load suspected-dupe cases.", error);
                return List.of();
            }
        });
    }

    public CompletableFuture<DupeCase> findCase(String id) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return storage.find(id);
            } catch (Exception error) {
                plugin.getLogger().log(Level.WARNING, "Failed to load suspected-dupe case " + id, error);
                return null;
            }
        });
    }

    /** @return the number of currently open (unresolved) cases. Used for the join-alert summary. */
    public CompletableFuture<Integer> openCaseCount() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return storage.unresolvedCount();
            } catch (Exception error) {
                plugin.getLogger().log(Level.WARNING, "Failed to load open suspected-dupe case count.", error);
                return 0;
            }
        });
    }

    /**
     * Transitions an open case to {@code status} (one of
     * {@link DupeCase#STATUS_RESOLVED}, {@link DupeCase#STATUS_DISMISSED},
     * {@link DupeCase#STATUS_CONFIRMED}). The transition is only applied to
     * a case that is still {@code OPEN} ({@link DupeStorage#resolve}
     * enforces this with a {@code WHERE status = 'OPEN'} guard), so two
     * staff members racing to close the same case can never both "win."
     */
    public CompletableFuture<Boolean> resolve(String id, String staffName, String status, String reason) {
        CompletableFuture<Boolean> request = CompletableFuture.supplyAsync(() -> {
            try {
                boolean changed = storage.resolve(id, staffName, status, reason, System.currentTimeMillis());
                if (changed) {
                    plugin.getLogger().warning("Dupe case " + id + " was marked " + status + " by " + staffName
                            + ": " + reason);
                }
                return changed;
            } catch (Exception error) {
                plugin.getLogger().log(Level.WARNING, "Failed to resolve suspected-dupe case " + id, error);
                return false;
            }
        });
        track(request);
        return request;
    }

    public void awaitWrites() {
        try {
            CompletableFuture.allOf(pendingWrites.toArray(new CompletableFuture[0])).get(5,
                    java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "Failed while waiting for dupe-case writes.", error);
        }
    }

    private void track(CompletableFuture<?> request) {
        pendingWrites.add(request);
        request.whenComplete((ignored, error) -> pendingWrites.remove(request));
    }

    private boolean shouldTrack(ItemStack item, ItemMeta meta) {
        if (trackedMaterials.contains(item.getType())) {
            return true;
        }
        org.bukkit.persistence.PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.has(wandKey, PersistentDataType.STRING)
                || pdc.has(blueprintKey, PersistentDataType.STRING)
                || pdc.has(collectorKey, PersistentDataType.BYTE)
                || pdc.has(spawnerKey, PersistentDataType.STRING)
                // Generic hook for every future item-identity feature: rather
                // than this method accumulating one more hardcoded key per
                // feature forever (as the four checks above did), anything
                // tagged with a real (non-GENERIC) ItemKind through the
                // shared TrackedItemIds utility is automatically considered
                // worth tracking. Custom Enchantments (Runes and physical/
                // applied enchantment items) is the first feature to rely on
                // this rather than adding its own key here.
                || trackedItemIds.kind(item).isPresent();
    }

    private record Evidence(String holderUuid, String holderName, String source, int slot, String material) {
        private String fingerprintPart() {
            return String.valueOf(holderUuid) + ':' + source + ':' + slot + ':' + material;
        }
    }
}
