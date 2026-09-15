package me.vertex.core.resetvault;

import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.holograms.Hologram;
import me.vertex.core.backpack.BackpackManager;
import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import me.vertex.core.luckperms.LuckPermsHook;
import me.vertex.core.preferences.AnnouncementCategory;
import me.vertex.core.preferences.AnnouncementPreferenceManager;
import me.vertex.core.storage.SqlRetry;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Core orchestrator for the Reset Vault module.
 * Manages cache, concurrency, anti-dupe invariants, physical access blocks,
 * item processing, backups, restore/undo, and holograms.
 */
public final class ResetVaultManager {

    private final Plugin plugin;
    private final ResetVaultStorage storage;
    private final Messages messages;
    private final AnnouncementPreferenceManager announcements;
    private final BackpackManager backpackManager;
    private final EnchantManager enchantManager;
    private final ResetVaultToken tokenManager;
    private final ItemDisplayNameResolver nameResolver;

    // Config options
    private boolean enabled = true;
    private volatile Material blockMaterial = Material.BLUE_SHULKER_BOX;
    private volatile Map<String, Integer> rankBaseSlots = new LinkedHashMap<>();
    private volatile Set<String> giveAllowlist = ConcurrentHashMap.newKeySet();
    private volatile Set<String> removeEnchantIds = ConcurrentHashMap.newKeySet();
    private volatile String sotwMilestoneName = "reset-vault-open";

    // Runtime state
    private volatile ResetVaultPhase phase = ResetVaultPhase.CLOSED;
    private final Map<UUID, ResetVaultData> vaultCache = new ConcurrentHashMap<>();
    private final Map<Location, ResetVaultAccessBlock> registeredBlocks = new ConcurrentHashMap<>();
    private final Map<Integer, ResetVaultAccessBlock> accessBlocksById = new ConcurrentHashMap<>();
    private final Map<UUID, VaultSession> activeSessions = new ConcurrentHashMap<>();
    private final Set<UUID> activeMutations = ConcurrentHashMap.newKeySet();
    private final List<ResetVaultStorage.BlacklistEntry> blacklist = new CopyOnWriteArrayList<>();

    // Backup state
    private volatile ResetVaultPhase phaseBeforeBackup = ResetVaultPhase.DEPOSIT;
    private final AtomicBoolean backupRunning = new AtomicBoolean(false);
    private volatile String backupInitiator = "Console";
    private volatile String backupProgress_status = "IDLE";
    private volatile int backupProgress_vaultCount = 0;
    private volatile long backupProgress_compressedSize = 0L;
    private volatile String backupProgress_checksum = "";

    private final Set<CompletableFuture<?>> pendingWrites = ConcurrentHashMap.newKeySet();

    private CompletableFuture<Void> runAsyncWrite(Runnable action) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(action);
        pendingWrites.add(future);
        future.whenComplete((ignored, ex) -> pendingWrites.remove(future));
        return future;
    }

    private <T> CompletableFuture<T> supplyAsyncWrite(java.util.function.Supplier<T> action) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(action);
        pendingWrites.add(future);
        future.whenComplete((ignored, ex) -> pendingWrites.remove(future));
        return future;
    }

    public ResetVaultManager(
            Plugin plugin,
            ResetVaultStorage storage,
            Messages messages,
            AnnouncementPreferenceManager announcements,
            BackpackManager backpackManager,
            EnchantManager enchantManager
    ) {
        this.plugin = plugin;
        this.storage = storage;
        this.messages = messages;
        this.announcements = announcements;
        this.backpackManager = backpackManager;
        this.enchantManager = enchantManager;
        this.tokenManager = new ResetVaultToken(plugin, messages);
        this.nameResolver = new ItemDisplayNameResolver();
    }

    // ------------------------------------------------------------------
    // Lifecycle & Config Loading
    // ------------------------------------------------------------------

    public void load() throws SQLException {
        reloadConfig();

        // 1. Initialize schema
        storage.init();

        // 2. Load stored global phase
        ResetVaultPhase storedPhase = storage.loadPhase();
        if (storedPhase == ResetVaultPhase.BACKUP_RUNNING) {
            // Server crashed while backup was running! Quarantine and lock.
            plugin.getLogger().severe("Reset Vault detected a crash during an active backup! Booting into RECOVERY_LOCKED.");
            this.phase = ResetVaultPhase.RECOVERY_LOCKED;
            storage.savePhase(ResetVaultPhase.RECOVERY_LOCKED);
        } else {
            this.phase = storedPhase;
        }

        // 3. Load all player data into cache
        vaultCache.clear();
        vaultCache.putAll(storage.loadAllPlayerData());

        // 4. Load access blocks
        registeredBlocks.clear();
        accessBlocksById.clear();
        for (ResetVaultStorage.AccessBlockRow row : storage.loadAccessBlocks()) {
            org.bukkit.World world = Bukkit.getWorld(row.world());
            if (world != null) {
                Location loc = new Location(world, row.x(), row.y(), row.z());
                ResetVaultAccessBlock block = new ResetVaultAccessBlock(row.id(), loc, row.hologramId());
                registeredBlocks.put(loc, block);
                accessBlocksById.put(row.id(), block);
            } else {
                plugin.getLogger().warning("Access block #" + row.id() + " is in unloaded world '" + row.world() + "'");
            }
        }

        // 5. Load blacklist
        blacklist.clear();
        blacklist.addAll(storage.loadBlacklist());

        // 6. Refresh holograms event-driven
        refreshAllHolograms();
    }

    public void reloadConfig() {
        this.enabled = plugin.getConfig().getBoolean("reset-vault.enabled", true);
        
        String configuredMat = plugin.getConfig().getString("reset-vault.block-material", "BLUE_SHULKER_BOX");
        Material mat = Material.matchMaterial(configuredMat == null ? "" : configuredMat);
        if (mat != null && !mat.isAir()) {
            this.blockMaterial = mat;
        } else {
            this.blockMaterial = Material.BLUE_SHULKER_BOX;
        }

        Map<String, Integer> ranks = new LinkedHashMap<>();
        ConfigurationSection ranksSection = plugin.getConfig().getConfigurationSection("reset-vault.rank-base-slots");
        if (ranksSection != null) {
            for (String key : ranksSection.getKeys(false)) {
                ranks.put(key.toLowerCase(Locale.ROOT), Math.max(0, ranksSection.getInt(key, 0)));
            }
        }
        if (ranks.isEmpty()) {
            ranks.put("default", 1);
            ranks.put("vip", 2);
            ranks.put("hero", 3);
            ranks.put("legend", 4);
            ranks.put("titan", 5);
        }
        this.rankBaseSlots = Map.copyOf(ranks);

        Set<String> allowlist = new HashSet<>();
        for (String name : plugin.getConfig().getStringList("reset-vault.give-allowlist")) {
            if (name != null && !name.isBlank()) {
                allowlist.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        this.giveAllowlist = Set.copyOf(allowlist);

        Set<String> enchants = new HashSet<>();
        for (String eId : plugin.getConfig().getStringList("reset-vault.remove-enchant")) {
            if (eId != null && !eId.isBlank()) {
                enchants.add(eId.trim().toLowerCase(Locale.ROOT));
            }
        }
        this.removeEnchantIds = Set.copyOf(enchants);

        this.sotwMilestoneName = plugin.getConfig().getString("reset-vault.sotw-milestone", "reset-vault-open");
        
        if (storage != null) {
            try {
                blacklist.clear();
                blacklist.addAll(storage.loadBlacklist());
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to reload blacklist", e);
            }
            Bukkit.getScheduler().runTask(plugin, this::refreshAllHolograms);
        }
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public Plugin plugin() { return plugin; }
    public Messages messages() { return messages; }
    public ResetVaultStorage storage() { return storage; }
    public ResetVaultToken tokenManager() { return tokenManager; }
    public ItemDisplayNameResolver nameResolver() { return nameResolver; }
    public Material blockMaterial() { return blockMaterial; }
    public String sotwMilestoneName() { return sotwMilestoneName; }
    public ResetVaultPhase phase() { return phase; }
    public List<ResetVaultStorage.BlacklistEntry> blacklist() { return Collections.unmodifiableList(blacklist); }

    public boolean isGiveAllowed(String playerName) {
        if (playerName == null) return false;
        return giveAllowlist.contains(playerName.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------
    // Phase Management & Holograms
    // ------------------------------------------------------------------

    public void setPhase(ResetVaultPhase newPhase) {
        Objects.requireNonNull(newPhase, "newPhase cannot be null");
        this.phase = newPhase;
        runAsyncWrite(() -> {
            try {
                storage.savePhase(newPhase);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to persist Reset Vault phase change: " + newPhase, e);
            }
        });
        Bukkit.getScheduler().runTask(plugin, this::refreshAllHolograms);
    }

    public void refreshAllHolograms() {
        if (!Bukkit.getPluginManager().isPluginEnabled("DecentHolograms")) {
            return;
        }
        for (ResetVaultAccessBlock block : registeredBlocks.values()) {
            updateHologram(block);
        }
    }

    public void updateHologram(ResetVaultAccessBlock block) {
        if (!Bukkit.getPluginManager().isPluginEnabled("DecentHolograms") || block == null) {
            return;
        }
        try {
            Location loc = block.location().clone().add(0.5, 1.8, 0.5);
            List<Component> rawLines = messages.getList(Bukkit.getConsoleSender(), "reset-vault.hologram.lines");
            List<String> formattedLines = new ArrayList<>(rawLines.size());
            String simpleStatus = messages.getRaw(Bukkit.getConsoleSender(), phase.langKey());

            for (Component line : rawLines) {
                String serialized = MessageFormatter.serialize(line);
                String parsed = serialized.replace("{simple_status}", simpleStatus)
                        .replace("{phase}", phase.name());
                formattedLines.add(MessageFormatter.legacyAmpersand(parsed));
            }

            Hologram hologram = DHAPI.getHologram(block.hologramId());
            if (hologram == null) {
                DHAPI.createHologram(block.hologramId(), loc, true, formattedLines);
            } else {
                DHAPI.setHologramLines(hologram, formattedLines);
            }
        } catch (Throwable e) {
            plugin.getLogger().log(Level.WARNING, "Failed to update Reset Vault hologram for block #" + block.id(), e);
        }
    }

    public void removeHologram(String hologramId) {
        if (!Bukkit.getPluginManager().isPluginEnabled("DecentHolograms") || hologramId == null) {
            return;
        }
        try {
            DHAPI.removeHologram(hologramId);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Physical Access Blocks
    // ------------------------------------------------------------------

    public boolean isRegisteredBlock(Location location) {
        if (location == null) return false;
        Location blockLoc = new Location(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        return registeredBlocks.containsKey(blockLoc);
    }

    public ResetVaultAccessBlock getAccessBlock(Location location) {
        if (location == null) return null;
        Location blockLoc = new Location(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        return registeredBlocks.get(blockLoc);
    }

    public ResetVaultAccessBlock getAccessBlock(int id) {
        return accessBlocksById.get(id);
    }

    public boolean isSessionActiveOnBlock(int blockId) {
        for (VaultSession session : activeSessions.values()) {
            if (session.accessBlockId == blockId) {
                return true;
            }
        }
        return false;
    }

    public CompletableFuture<ResetVaultAccessBlock> registerBlock(Location location) {
        Location blockLoc = new Location(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        String hologramId = "rv_block_" + blockLoc.getWorld().getName() + "_" + blockLoc.getBlockX() + "_" + blockLoc.getBlockY() + "_" + blockLoc.getBlockZ();
        return supplyAsyncWrite(() -> {
            try {
                int id = storage.saveAccessBlock(blockLoc.getWorld().getName(), blockLoc.getBlockX(), blockLoc.getBlockY(), blockLoc.getBlockZ(), hologramId);
                ResetVaultAccessBlock block = new ResetVaultAccessBlock(id, blockLoc, hologramId);
                registeredBlocks.put(blockLoc, block);
                accessBlocksById.put(id, block);
                Bukkit.getScheduler().runTask(plugin, () -> updateHologram(block));
                return block;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save Reset Vault access block to database", e);
                throw new RuntimeException(e);
            }
        });
    }

    public CompletableFuture<Void> unregisterBlock(ResetVaultAccessBlock block) {
        registeredBlocks.remove(block.location());
        accessBlocksById.remove(block.id());
        removeHologram(block.hologramId());
        return runAsyncWrite(() -> {
            try {
                storage.deleteAccessBlock(block.id());
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to delete Reset Vault access block #" + block.id(), e);
            }
        });
    }

    // ------------------------------------------------------------------
    // Sessions & Concurrency
    // ------------------------------------------------------------------

    public static final class VaultSession {
        final UUID playerUuid;
        final int accessBlockId;
        final long openedAt;
        volatile boolean readOnly;

        VaultSession(UUID playerUuid, int accessBlockId, boolean readOnly) {
            this.playerUuid = playerUuid;
            this.accessBlockId = accessBlockId;
            this.openedAt = System.currentTimeMillis();
            this.readOnly = readOnly;
        }

        public boolean isReadOnly() {
            return readOnly;
        }

        public UUID uuid() {
            return playerUuid;
        }
    }

    public VaultSession getSession(UUID uuid) {
        return activeSessions.get(uuid);
    }

    public void startSession(UUID uuid, int accessBlockId) {
        boolean readOnly = phase == ResetVaultPhase.BACKUP_PENDING || phase.isReadOnly();
        activeSessions.put(uuid, new VaultSession(uuid, accessBlockId, readOnly));
    }

    public void closeSession(UUID uuid) {
        activeSessions.remove(uuid);
        activeMutations.remove(uuid);
    }

    public int activeSessionCount() {
        return activeSessions.size();
    }

    public boolean acquireMutationLock(UUID uuid) {
        return activeMutations.add(uuid);
    }

    public void releaseMutationLock(UUID uuid) {
        activeMutations.remove(uuid);
    }

    // ------------------------------------------------------------------
    // Capacity Math
    // ------------------------------------------------------------------

    public int rankBaseSlots(Player player) {
        if (player == null) return 0;
        String rank = LuckPermsHook.getPrimaryGroupDisplayName(player);
        if (rank == null || rank.isBlank()) {
            rank = "default";
        }
        return rankBaseSlots.getOrDefault(rank.toLowerCase(Locale.ROOT),
                rankBaseSlots.getOrDefault("default", 1));
    }

    public int totalCapacity(Player player) {
        if (player == null) return 0;
        int base = rankBaseSlots(player);
        ResetVaultData data = getVaultData(player.getUniqueId());
        int bonus = data != null ? data.permanentBonusSlots() : 0;
        return base + bonus;
    }

    public int totalCapacity(UUID uuid, String rankName) {
        int base = rankBaseSlots.getOrDefault(rankName == null ? "default" : rankName.toLowerCase(Locale.ROOT),
                rankBaseSlots.getOrDefault("default", 1));
        ResetVaultData data = getVaultData(uuid);
        int bonus = data != null ? data.permanentBonusSlots() : 0;
        return base + bonus;
    }

    // ------------------------------------------------------------------
    // Vault Data Cache
    // ------------------------------------------------------------------

    public ResetVaultData getVaultData(UUID uuid) {
        return vaultCache.computeIfAbsent(uuid, id -> {
            try {
                ResetVaultData db = storage.loadPlayerData(id);
                if (db != null) return db;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load vault data for " + id, e);
            }
            return new ResetVaultData(id, "", 0, List.of());
        });
    }

    public void updateCache(ResetVaultData data) {
        vaultCache.put(data.uuid(), data);
    }

    // ------------------------------------------------------------------
    // Token Consumption
    // ------------------------------------------------------------------

    public void handleTokenConsume(Player player, ItemStack heldItem) {
        if (player == null || heldItem == null) return;
        if (!tokenManager.isToken(heldItem)) return;

        if (phase.isReadOnly()) {
            player.sendMessage(messages.get(player, "reset-vault.mutation-busy"));
            return;
        }

        UUID uuid = player.getUniqueId();
        if (!acquireMutationLock(uuid)) {
            player.sendMessage(messages.get(player, "reset-vault.mutation-busy"));
            return;
        }

        ItemStack clone = heldItem.clone();
        clone.setAmount(1);
        heldItem.subtract(1);

        ResetVaultData current = getVaultData(uuid);
        ResetVaultData updated = current.withBonusSlots(current.permanentBonusSlots() + 1)
                .withLastKnownIgn(player.getName());

        runAsyncWrite(() -> {
            try {
                SqlRetry.run(plugin, "save reset vault token redemption", () -> storage.savePlayerData(updated));
                Bukkit.getScheduler().runTask(plugin, () -> {
                    ((me.vertex.core.VertexPlugin) plugin).networkManager().publishInvalidation("reset-vault", uuid.toString());
                    updateCache(updated);
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline()) {
                        p.sendMessage(messages.get(p, "reset-vault.token.redeemed",
                                "slots", String.valueOf(totalCapacity(p))));
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to persist token redemption for " + uuid, e);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline()) {
                        Map<Integer, ItemStack> leftovers = p.getInventory().addItem(clone);
                        if (!leftovers.isEmpty()) {
                            ((me.vertex.core.VertexPlugin) plugin).deliveryManager().queue(uuid, leftovers.values(), "Reset Vault Token Refund");
                        }
                        p.sendMessage(messages.getRaw(p, "reset-vault.commit-failed"));
                    } else {
                        ((me.vertex.core.VertexPlugin) plugin).deliveryManager().queue(uuid, List.of(clone), "Reset Vault Token Refund");
                    }
                });
            } finally {
                releaseMutationLock(uuid);
            }
        });
    }

    // ------------------------------------------------------------------
    // Item Eligibility
    // ------------------------------------------------------------------

    public enum EligibilityResult {
        ELIGIBLE,
        REJECTED_AIR,
        REJECTED_STACKABLE,
        REJECTED_NONEMPTY_BACKPACK,
        REJECTED_BLACKLISTED
    }

    public record CheckResult(EligibilityResult result, String rejectionKey) {}

    public CheckResult checkEligibility(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return new CheckResult(EligibilityResult.REJECTED_AIR, "reset-vault.rejected.air");
        }

        // Stackable items must be exactly amount = 1
        if (item.getMaxStackSize() > 1 && item.getAmount() > 1) {
            return new CheckResult(EligibilityResult.REJECTED_STACKABLE, "reset-vault.rejected.stackable");
        }

        // Backpack check: must be empty
        if (backpackManager != null && backpackManager.isBackpack(item)) {
            if (!backpackManager.isBackpackEmpty(item)) {
                return new CheckResult(EligibilityResult.REJECTED_NONEMPTY_BACKPACK, "reset-vault.rejected.nonempty-backpack");
            }
        }

        // Container check (Shulker boxes, bundles)
        String matName = item.getType().name();
        if (matName.endsWith("SHULKER_BOX")) {
            if (item.getItemMeta() instanceof org.bukkit.inventory.meta.BlockStateMeta bsm) {
                if (bsm.getBlockState() instanceof org.bukkit.block.ShulkerBox shulker) {
                    if (!shulker.getInventory().isEmpty()) {
                        return new CheckResult(EligibilityResult.REJECTED_NONEMPTY_BACKPACK, "reset-vault.rejected.nonempty-backpack");
                    }
                }
            }
        } else if (matName.equals("BUNDLE")) {
            if (item.getItemMeta() instanceof org.bukkit.inventory.meta.BundleMeta bundle) {
                if (bundle.hasItems()) {
                    return new CheckResult(EligibilityResult.REJECTED_NONEMPTY_BACKPACK, "reset-vault.rejected.nonempty-backpack");
                }
            }
        }

        // Blacklist check
        for (ResetVaultStorage.BlacklistEntry entry : blacklist) {
            if ("MATERIAL".equalsIgnoreCase(entry.entryType())) {
                if (item.getType().name().equalsIgnoreCase(entry.entryKey())) {
                    String msg = entry.rejectionKey().isBlank() ? "reset-vault.rejected.blacklisted" : entry.rejectionKey();
                    return new CheckResult(EligibilityResult.REJECTED_BLACKLISTED, msg);
                }
            } else if ("CUSTOM_ITEM".equalsIgnoreCase(entry.entryType())) {
                if (matchesCustomItem(item, entry.entryKey())) {
                    String msg = entry.rejectionKey().isBlank() ? "reset-vault.rejected.blacklisted" : entry.rejectionKey();
                    return new CheckResult(EligibilityResult.REJECTED_BLACKLISTED, msg);
                }
            }
        }

        return new CheckResult(EligibilityResult.ELIGIBLE, null);
    }

    public boolean matchesCustomItem(ItemStack item, String targetId) {
        if (item == null || targetId == null) return false;
        String id = targetId.toLowerCase(Locale.ROOT);

        if (backpackManager != null && backpackManager.isBackpack(item)) {
            if ("backpack".equals(id)) return true;
            String tierId = backpackManager.backpackTierId(item);
            if (tierId != null && ("backpack:" + tierId.toLowerCase(Locale.ROOT)).equals(id)) {
                return true;
            }
        }

        if (tokenManager.isToken(item) && ("rv_slot_token".equals(id) || "token".equals(id))) {
            return true;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
                if (key.getKey().equalsIgnoreCase(id) || (key.getNamespace() + ":" + key.getKey()).equalsIgnoreCase(id)) {
                    return true;
                }
            }
        }

        return false;
    }

    // ------------------------------------------------------------------
    // Item Processing for Deposit
    // ------------------------------------------------------------------

    public ItemStack processItemForDeposit(ItemStack source) {
        if (source == null || source.getType().isAir()) {
            return source;
        }
        ItemStack item = source.clone();
        item.setAmount(1);

        // 1. Repair damageable items to full durability
        if (item.getItemMeta() instanceof Damageable damageable) {
            if (damageable.hasDamage()) {
                damageable.setDamage(0);
                item.setItemMeta(damageable);
            }
        }

        // 2. Custom enchants / runes precedence
        if (enchantManager != null) {
            enchantManager.filterCustomEnchants(item, enchantId -> {
                String lower = enchantId.toLowerCase(Locale.ROOT);
                // 1. If in remove-enchant config: remove it even if seasonal
                if (removeEnchantIds.contains(lower)) {
                    return true;
                }
                // 2. Otherwise if Custom Enchants marks it seasonal: preserve exactly
                if (enchantManager.isSeasonal(enchantId)) {
                    return false;
                }
                // 3. Otherwise: remove non-seasonal custom enchants
                return true;
            });
        }

        // 3. Backpack: reset to level 1
        if (backpackManager != null && backpackManager.isBackpack(item)) {
            backpackManager.resetToLevelOne(item);
        }

        return item;
    }

    // ------------------------------------------------------------------
    // Deposit & Withdrawal Operations
    // ------------------------------------------------------------------

    public void depositItem(
            Player player,
            int sourceSlot,
            ItemStack expectedSourceItem,
            Runnable onSuccess,
            Consumer<String> onFail
    ) {
        UUID uuid = player.getUniqueId();
        if (phase != ResetVaultPhase.DEPOSIT) {
            onFail.accept(messages.getRaw(player, "reset-vault.not-deposit-phase"));
            return;
        }

        VaultSession session = getSession(uuid);
        if (session != null && session.isReadOnly()) {
            onFail.accept(messages.getRaw(player, "reset-vault.session-read-only"));
            return;
        }

        int capacity = totalCapacity(player);
        ResetVaultData data = getVaultData(uuid);
        if (data.itemCount() >= capacity) {
            onFail.accept(messages.getRaw(player, "reset-vault.vault-full"));
            return;
        }

        if (!acquireMutationLock(uuid)) {
            onFail.accept(messages.getRaw(player, "reset-vault.mutation-busy"));
            return;
        }

        // Revalidate source slot
        ItemStack liveItem = player.getInventory().getItem(sourceSlot);
        if (liveItem == null || !liveItem.isSimilar(expectedSourceItem) || liveItem.getAmount() != expectedSourceItem.getAmount()) {
            releaseMutationLock(uuid);
            onFail.accept(messages.getRaw(player, "reset-vault.item-modified"));
            return;
        }

        CheckResult check = checkEligibility(liveItem);
        if (check.result() != EligibilityResult.ELIGIBLE) {
            releaseMutationLock(uuid);
            onFail.accept(messages.getRaw(player, check.rejectionKey()));
            return;
        }

        // Process item
        ItemStack processed = processItemForDeposit(liveItem);
        ResetVaultData updated = data.addItem(processed).withLastKnownIgn(player.getName());

        player.getInventory().setItem(sourceSlot, null);

        runAsyncWrite(() -> {
            try {
                SqlRetry.run(plugin, "persist reset vault deposit", () -> storage.savePlayerData(updated));
                Bukkit.getScheduler().runTask(plugin, () -> {
                    ((me.vertex.core.VertexPlugin) plugin).networkManager().publishInvalidation("reset-vault", uuid.toString());
                    try {
                        updateCache(updated);
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null && p.isOnline()) {
                            broadcastDeposit(p, processed);
                        }
                        onSuccess.run();
                    } finally {
                        releaseMutationLock(uuid);
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to commit deposit for " + player.getName(), e);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    releaseMutationLock(uuid);
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline()) {
                        Map<Integer, ItemStack> leftovers = p.getInventory().addItem(expectedSourceItem.clone());
                        if (!leftovers.isEmpty()) {
                            ((me.vertex.core.VertexPlugin) plugin).deliveryManager().queue(uuid, leftovers.values(), "Reset Vault Refund");
                        }
                    } else {
                        ((me.vertex.core.VertexPlugin) plugin).deliveryManager().queue(uuid, List.of(expectedSourceItem.clone()), "Reset Vault Refund");
                    }
                    onFail.accept(messages.getRaw(player, "reset-vault.commit-failed"));
                });
            }
        });
    }

    public void withdrawItem(
            Player player,
            int logicalIndex,
            Runnable onSuccess,
            Consumer<String> onFail
    ) {
        UUID uuid = player.getUniqueId();
        if (phase != ResetVaultPhase.WITHDRAW) {
            onFail.accept(messages.getRaw(player, "reset-vault.not-withdraw-phase"));
            return;
        }

        VaultSession session = getSession(uuid);
        if (session != null && session.isReadOnly()) {
            onFail.accept(messages.getRaw(player, "reset-vault.session-read-only"));
            return;
        }

        ResetVaultData data = getVaultData(uuid);
        if (logicalIndex < 0 || logicalIndex >= data.itemCount()) {
            onFail.accept(messages.getRaw(player, "reset-vault.invalid-slot"));
            return;
        }

        ItemStack itemToWithdraw = data.items().get(logicalIndex);

        // FIX FOR ISS-59: Enforce eligibility checks on withdrawal
        CheckResult eligibility = checkEligibility(itemToWithdraw);
        if (eligibility.result() == EligibilityResult.REJECTED_BLACKLISTED || eligibility.result() == EligibilityResult.REJECTED_NONEMPTY_BACKPACK) {
            onFail.accept(messages.getRaw(player, eligibility.rejectionKey()));
            return;
        }

        // Check if player inventory can accept the item without dropping on ground
        if (player.getInventory().firstEmpty() == -1) {
            onFail.accept(messages.getRaw(player, "reset-vault.inventory-full"));
            return;
        }

        if (!acquireMutationLock(uuid)) {
            onFail.accept(messages.getRaw(player, "reset-vault.mutation-busy"));
            return;
        }

        ResetVaultData updated = data.removeItem(logicalIndex).withLastKnownIgn(player.getName());

        runAsyncWrite(() -> {
            try {
                SqlRetry.run(plugin, "persist reset vault withdrawal", () -> storage.savePlayerData(updated));
                Bukkit.getScheduler().runTask(plugin, () -> {
                    ((me.vertex.core.VertexPlugin) plugin).networkManager().publishInvalidation("reset-vault", uuid.toString());
                    try {
                        updateCache(updated);
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null && p.isOnline()) {
                            Map<Integer, ItemStack> leftovers = p.getInventory().addItem(itemToWithdraw.clone());
                            if (!leftovers.isEmpty()) {
                                ((me.vertex.core.VertexPlugin) plugin).deliveryManager().queue(uuid, leftovers.values(), "Reset Vault Withdrawal");
                            }
                            p.updateInventory();
                            broadcastWithdrawal(p, itemToWithdraw);
                            onSuccess.run();
                        } else {
                            ((me.vertex.core.VertexPlugin) plugin).deliveryManager().queue(uuid, List.of(itemToWithdraw.clone()), "Reset Vault Withdrawal");
                        }
                    } finally {
                        releaseMutationLock(uuid);
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to commit withdrawal for " + player.getName(), e);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    releaseMutationLock(uuid);
                    onFail.accept(messages.getRaw(player, "reset-vault.commit-failed"));
                });
            }
        });
    }

    // ------------------------------------------------------------------
    // Admin Raw Editing
    // ------------------------------------------------------------------

    public void adminInsert(UUID targetUuid, int logicalIndex, ItemStack rawItem, String adminName) {
        if (rawItem == null || rawItem.getType().isAir()) return;
        if (phase.isReadOnly()) return;

        ResetVaultData current = getVaultData(targetUuid);
        ResetVaultData updated = current.setItem(logicalIndex, rawItem);
        updateCache(updated);
        
        runAsyncWrite(() -> {
            try {
                SqlRetry.run(plugin, "admin reset vault insert", () -> {
                    storage.savePlayerData(updated);
                    storage.appendAuditLog(System.currentTimeMillis(), "ADMIN_INSERT", adminName, targetUuid.toString(),
                            "Slot " + logicalIndex + ": " + nameResolver.resolve(rawItem) + " x" + rawItem.getAmount());
                });
                ((me.vertex.core.VertexPlugin) plugin).networkManager().publishInvalidation("reset-vault", targetUuid.toString());
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to persist admin insert for " + targetUuid, e);
            }
        });
    }

    public void adminRemove(UUID targetUuid, int logicalIndex, String adminName) {
        if (phase.isReadOnly()) return;

        ResetVaultData current = getVaultData(targetUuid);
        if (logicalIndex < 0 || logicalIndex >= current.itemCount()) return;
        ItemStack removed = current.items().get(logicalIndex);
        ResetVaultData updated = current.removeItem(logicalIndex);
        updateCache(updated);

        runAsyncWrite(() -> {
            try {
                SqlRetry.run(plugin, "admin reset vault remove", () -> {
                    storage.savePlayerData(updated);
                    storage.appendAuditLog(System.currentTimeMillis(), "ADMIN_REMOVE", adminName, targetUuid.toString(),
                            "Slot " + logicalIndex + ": " + nameResolver.resolve(removed));
                });
                ((me.vertex.core.VertexPlugin) plugin).networkManager().publishInvalidation("reset-vault", targetUuid.toString());
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to persist admin remove for " + targetUuid, e);
            }
        });
    }

    // ------------------------------------------------------------------
    // Broadcasts
    // ------------------------------------------------------------------

    public void broadcastDeposit(Player player, ItemStack item) {
        String rank = LuckPermsHook.getPrimaryGroupDisplayName(player);
        if (rank == null) rank = "";
        String itemName = nameResolver.resolve(item);
        if (announcements != null) {
            announcements.broadcast(AnnouncementCategory.RESET_VAULT, "reset-vault.broadcast.deposit",
                    "player", player.getName(),
                    "rank", rank,
                    "item", itemName);
        } else {
            Bukkit.broadcast(messages.get(Bukkit.getConsoleSender(), "reset-vault.broadcast.deposit",
                    "player", player.getName(),
                    "rank", rank,
                    "item", itemName));
        }
    }

    public void broadcastWithdrawal(Player player, ItemStack item) {
        String rank = LuckPermsHook.getPrimaryGroupDisplayName(player);
        if (rank == null) rank = "";
        String itemName = nameResolver.resolve(item);
        if (announcements != null) {
            announcements.broadcast(AnnouncementCategory.RESET_VAULT, "reset-vault.broadcast.withdraw",
                    "player", player.getName(),
                    "rank", rank,
                    "item", itemName);
        } else {
            Bukkit.broadcast(messages.get(Bukkit.getConsoleSender(), "reset-vault.broadcast.withdraw",
                    "player", player.getName(),
                    "rank", rank,
                    "item", itemName));
        }
    }

    // ------------------------------------------------------------------
    // Backup Orchestration
    // ------------------------------------------------------------------

    public boolean isBackupRunning() {
        return backupRunning.get();
    }

    public String backupProgressStatus() { return backupProgress_status; }
    public int backupProgressVaultCount() { return backupProgress_vaultCount; }
    public long backupProgressCompressedSize() { return backupProgress_compressedSize; }
    public String backupProgressChecksum() { return backupProgress_checksum; }

    public void startBackupProcess(CommandSender initiator) {
        if (!backupRunning.compareAndSet(false, true)) {
            initiator.sendMessage(messages.get(initiator, "reset-vault.backup.already-running"));
            return;
        }

        this.backupInitiator = initiator.getName();
        this.phaseBeforeBackup = this.phase;
        this.phase = ResetVaultPhase.BACKUP_PENDING;
        this.backupProgress_status = "PENDING_DRAIN";

        // Mark all active sessions as read-only
        for (VaultSession session : activeSessions.values()) {
            session.readOnly = true;
        }

        initiator.sendMessage(messages.get(initiator, "reset-vault.backup.pending-drain",
                "sessions", String.valueOf(activeSessions.size())));

        // Wait until sessions and mutations drain to 0, max 5 attempts (5 seconds) before force close
        scheduleDrainCheck(initiator, 5);
    }

    private void scheduleDrainCheck(CommandSender initiator, int attempts) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (activeSessions.isEmpty() && activeMutations.isEmpty() && pendingWrites.isEmpty()) {
                executeBackup(initiator);
            } else if (attempts > 0) {
                scheduleDrainCheck(initiator, attempts - 1);
            } else {
                // Force close remaining sessions
                for (UUID uuid : activeSessions.keySet()) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) p.closeInventory();
                }
                
                if (activeMutations.isEmpty() && pendingWrites.isEmpty()) {
                    executeBackup(initiator);
                } else {
                    scheduleDrainCheck(initiator, 0); // Keep waiting for DB writes to finish
                }
            }
        }, 20L);
    }

    private void executeBackup(CommandSender initiator) {
        this.phase = ResetVaultPhase.BACKUP_RUNNING;
        this.backupProgress_status = "RUNNING";

        runAsyncWrite(() -> {
            try {
                // 1. Snapshot all vault data
                Map<UUID, ResetVaultData> snapshot = storage.loadAllPlayerData();
                this.backupProgress_vaultCount = snapshot.size();

                // 2. Lossless serialization
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (DataOutputStream dos = new DataOutputStream(baos)) {
                    dos.writeInt(1); // Version
                    dos.writeInt(snapshot.size());
                    for (ResetVaultData data : snapshot.values()) {
                        dos.writeLong(data.uuid().getMostSignificantBits());
                        dos.writeLong(data.uuid().getLeastSignificantBits());
                        dos.writeUTF(data.lastKnownIgn());
                        dos.writeInt(data.permanentBonusSlots());
                        byte[] itemBytes = ItemStack.serializeItemsAsBytes(data.items().toArray(new ItemStack[0]));
                        dos.writeInt(itemBytes.length);
                        dos.write(itemBytes);
                    }
                }
                byte[] rawBytes = baos.toByteArray();

                // 3. Lossless GZIP Compression
                ByteArrayOutputStream compressedOut = new ByteArrayOutputStream();
                try (GZIPOutputStream gzos = new GZIPOutputStream(compressedOut)) {
                    gzos.write(rawBytes);
                }
                byte[] compressedBytes = compressedOut.toByteArray();
                this.backupProgress_compressedSize = compressedBytes.length;

                // 4. SHA-256 Checksum calculation
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(compressedBytes);
                StringBuilder hex = new StringBuilder();
                for (byte b : hash) hex.append(String.format("%02x", b));
                String checksum = hex.toString();
                this.backupProgress_checksum = checksum;

                // 5. Verify integrity (decompress and read)
                verifyBackupData(compressedBytes, checksum);

                // 6. Save to database
                String mapLabel = plugin.getConfig().getString("reset-vault.map-label", "Season 1");
                storage.saveBackup(System.currentTimeMillis(), mapLabel, backupInitiator,
                        snapshot.size(), compressedBytes.length, checksum, "COMPLETE", compressedBytes);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    this.phase = phaseBeforeBackup;
                    this.backupRunning.set(false);
                    this.backupProgress_status = "DONE";
                    refreshAllHolograms();
                    if (initiator instanceof Player player && player.isOnline()) {
                        player.sendMessage(messages.get(player, "reset-vault.backup.success",
                                "count", String.valueOf(snapshot.size()),
                                "size", String.valueOf(compressedBytes.length)));
                    } else {
                        plugin.getLogger().info("Reset Vault backup completed successfully ("
                                + snapshot.size() + " vaults, " + compressedBytes.length + " bytes).");
                    }
                });

            } catch (Throwable e) {
                plugin.getLogger().log(Level.SEVERE, "Reset Vault backup failed", e);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    this.phase = phaseBeforeBackup;
                    this.backupRunning.set(false);
                    this.backupProgress_status = "FAILED";
                    refreshAllHolograms();
                    if (initiator instanceof Player player && player.isOnline()) {
                        player.sendMessage(messages.get(player, "reset-vault.backup.failed"));
                    }
                });
            }
        });
    }

    private static void verifyBackupData(byte[] compressedBytes, String expectedChecksum) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(compressedBytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        if (!hex.toString().equalsIgnoreCase(expectedChecksum)) {
            throw new IllegalStateException("Backup checksum verification failed!");
        }

        try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(compressedBytes));
             DataInputStream dis = new DataInputStream(gzis)) {
            int version = dis.readInt();
            if (version != 1) {
                throw new IllegalStateException("Unsupported backup format version: " + version);
            }
            int count = dis.readInt();
            for (int i = 0; i < count; i++) {
                dis.readLong(); // msb
                dis.readLong(); // lsb
                dis.readUTF();  // ign
                dis.readInt();  // bonus slots
                int itemBytesLen = dis.readInt();
                byte[] itemBytes = new byte[itemBytesLen];
                dis.readFully(itemBytes);
                ItemStack.deserializeItemsFromBytes(itemBytes);
            }
        }
    }

    // ------------------------------------------------------------------
    // Restore & Undo
    // ------------------------------------------------------------------

    public CompletableFuture<Boolean> executeRestore(CommandSender initiator, int backupId) {
        ResetVaultPhase previous = this.phase;
        this.phase = ResetVaultPhase.RECOVERY_LOCKED;
        refreshAllHolograms();

        return supplyAsyncWrite(() -> {
            try {
                // 1. Create safety PRE_RESTORE snapshot
                Map<UUID, ResetVaultData> currentSnapshot = storage.loadAllPlayerData();
                byte[] currentCompressed = compressData(currentSnapshot);
                String currentChecksum = computeChecksum(currentCompressed);
                verifyBackupData(currentCompressed, currentChecksum);
                storage.saveBackup(System.currentTimeMillis(), "SAFETY", initiator.getName(),
                        currentSnapshot.size(), currentCompressed.length, currentChecksum, "PRE_RESTORE", currentCompressed);

                // 2. Load target backup
                ResetVaultStorage.BackupRecord record = storage.loadBackupRecord(backupId);
                if (record == null || !record.isSelectable()) {
                    throw new IllegalArgumentException("Backup #" + backupId + " is not valid or complete.");
                }
                byte[] targetData = storage.loadBackupData(backupId);
                if (targetData == null) {
                    throw new IllegalStateException("Backup data for #" + backupId + " is missing!");
                }

                // 3. Verify target backup
                verifyBackupData(targetData, record.checksum());

                // 4. Deserialize target data
                Map<UUID, ResetVaultData> restoredMap = deserializeBackup(targetData);

                // 5. Apply to database and cache
                storage.saveAllPlayerData(restoredMap);
                vaultCache.clear();
                vaultCache.putAll(restoredMap);

                // 6. Record restore history
                storage.saveRestoreHistory(backupId, initiator.getName(), System.currentTimeMillis());

                Bukkit.getScheduler().runTask(plugin, () -> {
                    this.phase = previous;
                    refreshAllHolograms();
                });
                return true;
            } catch (Throwable e) {
                plugin.getLogger().log(Level.SEVERE, "Restore operation failed", e);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    this.phase = previous;
                    refreshAllHolograms();
                });
                return false;
            }
        });
    }

    public CompletableFuture<Boolean> executeUndo(CommandSender initiator) {
        return supplyAsyncWrite(() -> {
            try {
                int preRestoreId = storage.findLatestPreRestoreBackupId();
                if (preRestoreId == -1) {
                    throw new IllegalStateException("No reversible restore point found.");
                }

                ResetVaultPhase previous = this.phase;
                this.phase = ResetVaultPhase.RECOVERY_LOCKED;
                Bukkit.getScheduler().runTask(plugin, this::refreshAllHolograms);

                // Create PRE_UNDO safety snapshot
                Map<UUID, ResetVaultData> currentSnapshot = storage.loadAllPlayerData();
                byte[] currentCompressed = compressData(currentSnapshot);
                String currentChecksum = computeChecksum(currentCompressed);
                verifyBackupData(currentCompressed, currentChecksum);
                storage.saveBackup(System.currentTimeMillis(), "SAFETY", initiator.getName(),
                        currentSnapshot.size(), currentCompressed.length, currentChecksum, "PRE_UNDO", currentCompressed);

                // Load PRE_RESTORE backup
                ResetVaultStorage.BackupRecord record = storage.loadBackupRecord(preRestoreId);
                byte[] preRestoreData = storage.loadBackupData(preRestoreId);
                verifyBackupData(preRestoreData, record.checksum());

                // Apply
                Map<UUID, ResetVaultData> restoredMap = deserializeBackup(preRestoreData);
                storage.saveAllPlayerData(restoredMap);
                vaultCache.clear();
                vaultCache.putAll(restoredMap);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    this.phase = previous;
                    refreshAllHolograms();
                });
                return true;
            } catch (Throwable e) {
                plugin.getLogger().log(Level.SEVERE, "Undo operation failed", e);
                Bukkit.getScheduler().runTask(plugin, this::refreshAllHolograms);
                return false;
            }
        });
    }

    private static byte[] compressData(Map<UUID, ResetVaultData> snapshot) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(1);
            dos.writeInt(snapshot.size());
            for (ResetVaultData data : snapshot.values()) {
                dos.writeLong(data.uuid().getMostSignificantBits());
                dos.writeLong(data.uuid().getLeastSignificantBits());
                dos.writeUTF(data.lastKnownIgn());
                dos.writeInt(data.permanentBonusSlots());
                byte[] itemBytes = ItemStack.serializeItemsAsBytes(data.items().toArray(new ItemStack[0]));
                dos.writeInt(itemBytes.length);
                dos.write(itemBytes);
            }
        }
        ByteArrayOutputStream compressedOut = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(compressedOut)) {
            gzos.write(baos.toByteArray());
        }
        return compressedOut.toByteArray();
    }

    private static String computeChecksum(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private static Map<UUID, ResetVaultData> deserializeBackup(byte[] compressedBytes) throws Exception {
        Map<UUID, ResetVaultData> map = new LinkedHashMap<>();
        try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(compressedBytes));
             DataInputStream dis = new DataInputStream(gzis)) {
            dis.readInt(); // version
            int count = dis.readInt();
            for (int i = 0; i < count; i++) {
                long msb = dis.readLong();
                long lsb = dis.readLong();
                UUID uuid = new UUID(msb, lsb);
                String ign = dis.readUTF();
                int bonusSlots = dis.readInt();
                int len = dis.readInt();
                byte[] bytes = new byte[len];
                dis.readFully(bytes);
                ItemStack[] items = ItemStack.deserializeItemsFromBytes(bytes);
                map.put(uuid, new ResetVaultData(uuid, ign, bonusSlots, Arrays.asList(items)));
            }
        }
        return map;
    }

    // ------------------------------------------------------------------
    // Blacklist Operations
    // ------------------------------------------------------------------

    public CompletableFuture<Void> addBlacklistEntry(String type, String key, String rejectionKey) {
        return runAsyncWrite(() -> {
            try {
                int id = storage.addBlacklistEntry(type, key, rejectionKey);
                blacklist.add(new ResetVaultStorage.BlacklistEntry(id, type, key, rejectionKey));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to add blacklist entry", e);
            }
        });
    }

    public CompletableFuture<Void> removeBlacklistEntry(int id) {
        blacklist.removeIf(entry -> entry.id() == id);
        return runAsyncWrite(() -> {
            try {
                storage.removeBlacklistEntry(id);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to remove blacklist entry #" + id, e);
            }
        });
    }

    // ------------------------------------------------------------------
    // Lifecycle & Migration Drain
    // ------------------------------------------------------------------

    public void awaitWrites() {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        int emptyRounds = 0;
        try {
            while (System.nanoTime() < deadline) {
                CompletableFuture<?>[] snapshot = pendingWrites.toArray(new CompletableFuture[0]);
                if (snapshot.length == 0) {
                    if (++emptyRounds >= 3) return;
                    java.util.concurrent.TimeUnit.MILLISECONDS.sleep(2L);
                    continue;
                }
                emptyRounds = 0;
                long remaining = Math.max(1L, deadline - System.nanoTime());
                CompletableFuture.allOf(snapshot).get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
            }
            plugin.getLogger().warning("Timed out waiting for pending Reset Vault writes.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().log(Level.WARNING, "Interrupted while waiting for Reset Vault writes.", e);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Timed out waiting for pending Reset Vault writes.", e);
        }
    }

    public void shutdown() {
        for (VaultSession session : activeSessions.values()) {
            Player p = Bukkit.getPlayer(session.uuid());
            if (p != null && p.isOnline()) {
                p.closeInventory();
            }
        }
        activeSessions.clear();
        activeMutations.clear();
        for (ResetVaultAccessBlock block : registeredBlocks.values()) {
            removeHologram(block.hologramId());
        }
        registeredBlocks.clear();
        accessBlocksById.clear();
        awaitWrites();
    }

    public void onSeasonReset() {
        this.phase = ResetVaultPhase.CLOSED;
        for (VaultSession session : activeSessions.values()) {
            Player p = Bukkit.getPlayer(session.uuid());
            if (p != null && p.isOnline()) {
                p.closeInventory();
            }
        }
        activeSessions.clear();
        activeMutations.clear();
        for (ResetVaultAccessBlock block : registeredBlocks.values()) {
            removeHologram(block.hologramId());
        }
        registeredBlocks.clear();
        accessBlocksById.clear();
        awaitWrites();
    }
}
