package me.vertex.core.pvp;

import me.vertex.core.lang.Messages;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Makes a disconnected player leave behind a killable Citizens NPC without
 * duplicating their items. The original inventory is persisted immediately,
 * then either restored on a safe return or dropped exactly once with the NPC.
 */
public final class GhostPlayerManager implements Listener {

    private final Plugin plugin;
    private final CombatManager combatManager;
    private final Messages messages;
    private final File dataFile;
    private final Map<UUID, GhostRecord> ghosts = new HashMap<>();
    private final Map<UUID, UUID> entityOwners = new HashMap<>();
    private BukkitTask expiryTask;
    private boolean enabled;
    private boolean combatTaggedOnly;
    private EntityType npcType;
    private Set<String> allowedWorlds = Set.of();
    private long despawnMillis;

    public GhostPlayerManager(Plugin plugin, CombatManager combatManager, Messages messages) {
        this.plugin = plugin;
        this.combatManager = combatManager;
        this.messages = messages;
        this.dataFile = new File(plugin.getDataFolder(), "ghost-players.yml");
        reload();
        loadRecords();
        expiryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::expireGhosts, 20L, 20L);
        // Citizens has already enabled (it is a soft dependency), but defer
        // one tick so persisted NPC entities have a chance to spawn.
        Bukkit.getScheduler().runTask(plugin, this::bindPersistedNpcs);
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("pvp.ghost-players.enabled", false);
        combatTaggedOnly = plugin.getConfig().getBoolean("pvp.ghost-players.combat-tagged-only", true);
        despawnMillis = Math.max(0L,
                plugin.getConfig().getLong("pvp.ghost-players.despawn-after-seconds", 300)) * 1000L;
        Set<String> worlds = new HashSet<>();
        for (String world : plugin.getConfig().getStringList("pvp.ghost-players.allowed-worlds")) {
            if (world != null && !world.isBlank()) {
                worlds.add(world.toLowerCase(Locale.ROOT));
            }
        }
        allowedWorlds = Set.copyOf(worlds);
        try {
            npcType = EntityType.valueOf(plugin.getConfig().getString("pvp.ghost-players.npc-type", "PLAYER")
                    .trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            npcType = EntityType.PLAYER;
            plugin.getLogger().warning("Invalid pvp.ghost-players.npc-type; using PLAYER.");
        }
        if (!npcType.isAlive()) {
            plugin.getLogger().warning("pvp.ghost-players.npc-type must be a living entity; using PLAYER.");
            npcType = EntityType.PLAYER;
        }
    }

    /**
     * Called by the connection listener before the normal instant combat-log
     * penalty. A successful spawn means the caller must not kill the real
     * player -- their inventory is now held by the NPC instead.
     */
    public boolean handleForcedDisconnect(Player player) {
        if (!enabled || !isAllowedWorld(player.getLocation())
                || (combatTaggedOnly && (combatManager == null || !combatManager.isTagged(player.getUniqueId())))) {
            return false;
        }
        if (!CitizensAPI.hasImplementation()) {
            return false;
        }

        UUID ownerId = player.getUniqueId();
        removeExistingGhost(ownerId, true);
        GhostRecord record = GhostRecord.capture(player, System.currentTimeMillis());
        NPC npc = null;
        try {
            npc = CitizensAPI.getNPCRegistry().createNPC(npcType, player.getName());
            // Persist a protected pending record before clearing the live
            // inventory. A reboot in this transition restores it safely.
            npc.setProtected(true);
            record.npcId = npc.getId();
            ghosts.put(ownerId, record);
            if (!saveRecords()) {
                ghosts.remove(ownerId);
                npc.destroy();
                return false;
            }
            if (!npc.spawn(player.getLocation())) {
                ghosts.remove(ownerId);
                saveRecords();
                npc.destroy();
                return false;
            }
            Entity entity = npc.getEntity();
            if (!(entity instanceof LivingEntity living)) {
                npc.destroy();
                ghosts.remove(ownerId);
                saveRecords();
                return false;
            }
            equipNpc(living, record);
            record.location = living.getLocation();
            clearPlayerInventory(player);
            record.armed = true;
            npc.setProtected(false);
            entityOwners.put(living.getUniqueId(), ownerId);
            saveRecords();
            return true;
        } catch (RuntimeException exception) {
            if (npc != null) {
                npc.destroy();
            }
            // The record is created before clearing the live inventory for
            // crash safety. If spawn/equip then fails, remove it again so a
            // later login cannot restore an inventory the player never lost.
            ghosts.remove(ownerId);
            saveRecords();
            plugin.getLogger().log(Level.WARNING, "Could not create Ghost Player for " + player.getName() + ".", exception);
            return false;
        }
    }

    /** Restores a safe ghost or applies the configured death consequence. */
    public void handleJoin(Player player) {
        UUID ownerId = player.getUniqueId();
        GhostRecord record = ghosts.get(ownerId);
        if (record == null) {
            return;
        }
        if (record.dead) {
            removeRecord(ownerId, false);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && !player.isDead()) {
                    player.sendMessage(messages.get(player, "ghost-players.killed-on-login"));
                    player.setHealth(0.0);
                }
            });
            return;
        }

        destroyNpc(record);
        restorePlayerInventory(player, record);
        removeRecord(ownerId, false);
        player.sendMessage(messages.get(player, "ghost-players.restored"));
    }

    @EventHandler
    public void onGhostDeath(EntityDeathEvent event) {
        UUID ownerId = entityOwners.remove(event.getEntity().getUniqueId());
        if (ownerId == null) {
            return;
        }
        GhostRecord record = ghosts.get(ownerId);
        if (record == null || record.dead || !record.armed) {
            return;
        }
        // Citizens may expose worn equipment as ordinary drops. Clear those
        // first, then add our persisted snapshot exactly once.
        event.getDrops().clear();
        event.getDrops().addAll(record.allItems());
        record.dead = true;
        record.npcId = -1;
        saveRecords();
    }

    /** Keep active ghosts on plugin/server reload; their item records are persisted. */
    public void shutdown() {
        if (expiryTask != null) {
            expiryTask.cancel();
            expiryTask = null;
        }
        saveRecords();
    }

    private boolean isAllowedWorld(Location location) {
        return allowedWorlds.isEmpty() || allowedWorlds.contains(location.getWorld().getName().toLowerCase(Locale.ROOT));
    }

    private void expireGhosts() {
        if (despawnMillis <= 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (GhostRecord record : ghosts.values()) {
            if (record.armed && !record.dead && record.npcId >= 0 && now - record.createdAt >= despawnMillis) {
                destroyNpc(record);
                record.npcId = -1;
                changed = true;
            }
        }
        if (changed) {
            saveRecords();
        }
    }

    private void bindPersistedNpcs() {
        if (!CitizensAPI.hasImplementation()) {
            return;
        }
        for (Map.Entry<UUID, GhostRecord> entry : ghosts.entrySet()) {
            GhostRecord record = entry.getValue();
            if (record.dead || record.npcId < 0) {
                continue;
            }
            NPC npc = CitizensAPI.getNPCRegistry().getById(record.npcId);
            if (!record.armed) {
                if (npc != null) {
                    npc.destroy();
                }
                record.npcId = -1;
                continue;
            }
            if (npc != null && npc.isSpawned() && npc.getEntity() != null) {
                entityOwners.put(npc.getEntity().getUniqueId(), entry.getKey());
            } else {
                // Never strand an inventory if Citizens did not restore an
                // NPC (for example, its world is disabled). A later join
                // safely restores this record to its owner.
                record.npcId = -1;
            }
        }
        saveRecords();
    }

    private void removeExistingGhost(UUID ownerId, boolean restoreIfOnline) {
        GhostRecord existing = ghosts.get(ownerId);
        if (existing == null) {
            return;
        }
        Player player = Bukkit.getPlayer(ownerId);
        if (restoreIfOnline && player != null && player.isOnline() && !existing.dead) {
            restorePlayerInventory(player, existing);
        }
        destroyNpc(existing);
        removeRecord(ownerId, false);
    }

    private void destroyNpc(GhostRecord record) {
        if (record.npcId < 0 || !CitizensAPI.hasImplementation()) {
            return;
        }
        NPC npc = CitizensAPI.getNPCRegistry().getById(record.npcId);
        if (npc != null) {
            Entity entity = npc.getEntity();
            if (entity != null) {
                entityOwners.remove(entity.getUniqueId());
            }
            npc.destroy();
        }
    }

    private void removeRecord(UUID ownerId, boolean ignored) {
        ghosts.remove(ownerId);
        saveRecords();
    }

    private static void equipNpc(LivingEntity npc, GhostRecord record) {
        if (npc instanceof Player playerNpc) {
            PlayerInventory inventory = playerNpc.getInventory();
            inventory.setStorageContents(copy(record.storage));
            inventory.setArmorContents(copy(record.armor));
            inventory.setItemInOffHand(copy(record.offHand));
            return;
        }
        EntityEquipment equipment = npc.getEquipment();
        if (equipment != null) {
            equipment.setArmorContents(copy(record.armor));
            equipment.setItemInMainHand(firstNonEmpty(record.storage));
            equipment.setItemInOffHand(copy(record.offHand));
        }
    }

    private static ItemStack firstNonEmpty(ItemStack[] items) {
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                return item.clone();
            }
        }
        return null;
    }

    private static void clearPlayerInventory(Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.setStorageContents(new ItemStack[inventory.getStorageContents().length]);
        inventory.setArmorContents(new ItemStack[inventory.getArmorContents().length]);
        inventory.setItemInOffHand(null);
        player.setItemOnCursor(null);
    }

    private static void restorePlayerInventory(Player player, GhostRecord record) {
        PlayerInventory inventory = player.getInventory();
        inventory.setStorageContents(copy(record.storage));
        inventory.setArmorContents(copy(record.armor));
        inventory.setItemInOffHand(copy(record.offHand));
        player.setItemOnCursor(copy(record.cursor));
    }

    private void loadRecords() {
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = yaml.getConfigurationSection("ghosts");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            try {
                UUID ownerId = UUID.fromString(id);
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) {
                    continue;
                }
                GhostRecord record = new GhostRecord(
                        itemArray(section.getList("storage"), 36),
                        itemArray(section.getList("armor"), 4),
                        section.getItemStack("offhand"),
                        section.getItemStack("cursor"),
                        section.getLong("created-at"),
                        section.getInt("npc-id", -1),
                        section.getBoolean("armed", false),
                        section.getBoolean("dead", false),
                        section.getLocation("location"));
                ghosts.put(ownerId, record);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ignoring invalid Ghost Player record '" + id + "'.");
            }
        }
    }

    private boolean saveRecords() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, GhostRecord> entry : ghosts.entrySet()) {
            GhostRecord record = entry.getValue();
            String path = "ghosts." + entry.getKey();
            yaml.set(path + ".storage", copy(record.storage));
            yaml.set(path + ".armor", copy(record.armor));
            yaml.set(path + ".offhand", copy(record.offHand));
            yaml.set(path + ".cursor", copy(record.cursor));
            yaml.set(path + ".created-at", record.createdAt);
            yaml.set(path + ".npc-id", record.npcId);
            yaml.set(path + ".armed", record.armed);
            yaml.set(path + ".dead", record.dead);
            yaml.set(path + ".location", record.location);
        }
        try {
            yaml.save(dataFile);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not save Ghost Player records.", exception);
            return false;
        }
    }

    private static ItemStack[] itemArray(List<?> values, int expectedSize) {
        ItemStack[] items = new ItemStack[Math.max(expectedSize, values == null ? 0 : values.size())];
        if (values == null) {
            return items;
        }
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            if (value instanceof ItemStack item) {
                items[index] = item.clone();
            }
        }
        return items;
    }

    private static ItemStack[] copy(ItemStack[] items) {
        return Arrays.stream(items).map(GhostPlayerManager::copy).toArray(ItemStack[]::new);
    }

    private static ItemStack copy(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private static final class GhostRecord {
        private final ItemStack[] storage;
        private final ItemStack[] armor;
        private final ItemStack offHand;
        private final ItemStack cursor;
        private final long createdAt;
        private int npcId;
        private boolean armed;
        private boolean dead;
        private Location location;

        private GhostRecord(ItemStack[] storage, ItemStack[] armor, ItemStack offHand, ItemStack cursor,
                            long createdAt, int npcId, boolean armed, boolean dead, Location location) {
            this.storage = copy(storage);
            this.armor = copy(armor);
            this.offHand = copy(offHand);
            this.cursor = copy(cursor);
            this.createdAt = createdAt;
            this.npcId = npcId;
            this.armed = armed;
            this.dead = dead;
            this.location = location;
        }

        private static GhostRecord capture(Player player, long now) {
            PlayerInventory inventory = player.getInventory();
            return new GhostRecord(inventory.getStorageContents(), inventory.getArmorContents(),
                    inventory.getItemInOffHand(), player.getItemOnCursor(), now, -1, false, false,
                    player.getLocation());
        }

        private List<ItemStack> allItems() {
            List<ItemStack> result = new ArrayList<>();
            addNonEmpty(result, storage);
            addNonEmpty(result, armor);
            if (offHand != null && !offHand.getType().isAir()) {
                result.add(offHand.clone());
            }
            if (cursor != null && !cursor.getType().isAir()) {
                result.add(cursor.clone());
            }
            return result;
        }

        private static void addNonEmpty(List<ItemStack> target, ItemStack[] source) {
            for (ItemStack item : source) {
                if (item != null && !item.getType().isAir()) {
                    target.add(item.clone());
                }
            }
        }
    }
}
