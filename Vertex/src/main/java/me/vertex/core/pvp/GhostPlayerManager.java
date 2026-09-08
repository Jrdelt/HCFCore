package me.vertex.core.pvp;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
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
import java.util.*;
import java.util.logging.Level;

/** Native killable Villager combat-log ghosts; packet NPCs cannot receive damage. */
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
    private Set<String> allowedWorlds = Set.of();
    private long despawnMillis;

    public GhostPlayerManager(Plugin plugin, CombatManager combatManager, Messages messages) {
        this.plugin = plugin;
        this.combatManager = combatManager;
        this.messages = messages;
        dataFile = new File(plugin.getDataFolder(), "ghost-players.yml");
        reload();
        loadRecords();
        expiryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::expireGhosts, 20L, 20L);
        Bukkit.getScheduler().runTask(plugin, this::bindPersistedVillagers);
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("pvp.ghost-players.enabled", false);
        combatTaggedOnly = plugin.getConfig().getBoolean("pvp.ghost-players.combat-tagged-only", true);
        despawnMillis = Math.max(0L, plugin.getConfig().getLong("pvp.ghost-players.despawn-after-seconds", 300)) * 1000L;
        Set<String> worlds = new HashSet<>();
        for (String world : plugin.getConfig().getStringList("pvp.ghost-players.allowed-worlds")) {
            if (world != null && !world.isBlank()) worlds.add(world.toLowerCase(Locale.ROOT));
        }
        allowedWorlds = Set.copyOf(worlds);
    }

    public boolean handleForcedDisconnect(Player player) {
        if (!enabled || !isAllowedWorld(player.getLocation())
                || (combatTaggedOnly && (combatManager == null || !combatManager.isTagged(player.getUniqueId())))) return false;
        UUID ownerId = player.getUniqueId();
        removeExistingGhost(ownerId, true);
        GhostRecord record = GhostRecord.capture(player, System.currentTimeMillis());
        ghosts.put(ownerId, record);
        if (!saveRecords()) {
            ghosts.remove(ownerId);
            return false;
        }
        try {
            Villager villager = player.getWorld().spawn(player.getLocation(), Villager.class, ghost -> {
                ghost.customName(net.kyori.adventure.text.Component.text(player.getName() + "'s Ghost"));
                ghost.setCustomNameVisible(true);
                ghost.setAI(false);
                ghost.setCanPickupItems(false);
                ghost.setRemoveWhenFarAway(false);
                ghost.setPersistent(true);
                if (ghost.getAttribute(Attribute.MAX_HEALTH) != null) ghost.getAttribute(Attribute.MAX_HEALTH).setBaseValue(20.0);
                ghost.setHealth(20.0);
            });
            record.entityId = villager.getUniqueId();
            record.location = villager.getLocation();
            if (!saveRecords()) {
                villager.remove();
                ghosts.remove(ownerId);
                return false;
            }
            equipVillager(villager, record);
            clearPlayerInventory(player);
            record.armed = true;
            entityOwners.put(villager.getUniqueId(), ownerId);
            saveRecords();
            return true;
        } catch (RuntimeException exception) {
            destroyVillager(record);
            ghosts.remove(ownerId);
            saveRecords();
            plugin.getLogger().log(Level.WARNING, "Could not create Ghost Player for " + player.getName() + ".", exception);
            return false;
        }
    }

    public void handleJoin(Player player) {
        GhostRecord record = ghosts.get(player.getUniqueId());
        if (record == null) return;
        if (record.dead) {
            removeRecord(player.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && !player.isDead()) {
                    player.sendMessage(messages.get(player, "ghost-players.killed-on-login"));
                    player.setHealth(0.0);
                }
            });
            return;
        }
        destroyVillager(record);
        restorePlayerInventory(player, record);
        removeRecord(player.getUniqueId());
        player.sendMessage(messages.get(player, "ghost-players.restored"));
    }

    @EventHandler
    public void onGhostDeath(EntityDeathEvent event) {
        UUID ownerId = entityOwners.remove(event.getEntity().getUniqueId());
        if (ownerId == null) return;
        GhostRecord record = ghosts.get(ownerId);
        if (record == null || record.dead || !record.armed) return;
        event.getDrops().clear();
        event.getDrops().addAll(record.allItems());
        record.dead = true;
        record.entityId = null;
        saveRecords();
    }

    public void shutdown() {
        if (expiryTask != null) expiryTask.cancel();
        saveRecords();
    }

    private boolean isAllowedWorld(Location location) {
        return allowedWorlds.isEmpty() || allowedWorlds.contains(location.getWorld().getName().toLowerCase(Locale.ROOT));
    }

    private void expireGhosts() {
        if (despawnMillis <= 0L) return;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (GhostRecord record : ghosts.values()) {
            if (record.armed && !record.dead && record.entityId != null && now - record.createdAt >= despawnMillis) {
                destroyVillager(record);
                changed = true;
            }
        }
        if (changed) saveRecords();
    }

    private void bindPersistedVillagers() {
        for (Map.Entry<UUID, GhostRecord> entry : ghosts.entrySet()) {
            GhostRecord record = entry.getValue();
            LivingEntity entity = findVillager(record);
            if (record.armed && !record.dead && entity instanceof Villager) entityOwners.put(entity.getUniqueId(), entry.getKey());
        }
    }

    private void removeExistingGhost(UUID ownerId, boolean restoreIfOnline) {
        GhostRecord record = ghosts.get(ownerId);
        if (record == null) return;
        Player player = Bukkit.getPlayer(ownerId);
        if (restoreIfOnline && player != null && player.isOnline() && !record.dead) restorePlayerInventory(player, record);
        destroyVillager(record);
        removeRecord(ownerId);
    }

    private LivingEntity findVillager(GhostRecord record) {
        if (record.entityId == null) return null;
        Entity entity = Bukkit.getEntity(record.entityId);
        if (entity instanceof LivingEntity living) return living;
        if (record.location == null || record.location.getWorld() == null) return null;
        World world = record.location.getWorld();
        for (Entity candidate : world.getChunkAt(record.location).getEntities()) {
            if (record.entityId.equals(candidate.getUniqueId()) && candidate instanceof LivingEntity living) return living;
        }
        return null;
    }

    private void destroyVillager(GhostRecord record) {
        LivingEntity entity = findVillager(record);
        if (entity != null) {
            entityOwners.remove(entity.getUniqueId());
            entity.remove();
        }
        record.entityId = null;
    }

    private void removeRecord(UUID ownerId) {
        ghosts.remove(ownerId);
        saveRecords();
    }

    private static void equipVillager(Villager villager, GhostRecord record) {
        EntityEquipment equipment = villager.getEquipment();
        if (equipment == null) return;
        equipment.setArmorContents(copy(record.armor));
        equipment.setItemInMainHand(firstNonEmpty(record.storage));
        equipment.setItemInOffHand(copy(record.offHand));
        equipment.setHelmetDropChance(0);
        equipment.setChestplateDropChance(0);
        equipment.setLeggingsDropChance(0);
        equipment.setBootsDropChance(0);
        equipment.setItemInMainHandDropChance(0);
        equipment.setItemInOffHandDropChance(0);
    }

    private static ItemStack firstNonEmpty(ItemStack[] items) {
        for (ItemStack item : items) if (item != null && !item.getType().isAir()) return item.clone();
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
        if (!dataFile.exists()) return;
        ConfigurationSection root = YamlConfiguration.loadConfiguration(dataFile).getConfigurationSection("ghosts");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            try {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) continue;
                String entityText = section.getString("entity-uuid");
                UUID entityId = entityText == null || entityText.isBlank() ? null : UUID.fromString(entityText);
                ghosts.put(UUID.fromString(id), new GhostRecord(itemArray(section.getList("storage"), 36),
                        itemArray(section.getList("armor"), 4), section.getItemStack("offhand"),
                        section.getItemStack("cursor"), section.getLong("created-at"), entityId,
                        section.getBoolean("armed", false), section.getBoolean("dead", false), section.getLocation("location")));
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
            yaml.set(path + ".entity-uuid", record.entityId == null ? null : record.entityId.toString());
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
        if (values != null) for (int index = 0; index < values.size(); index++) {
            if (values.get(index) instanceof ItemStack item) items[index] = item.clone();
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
        final ItemStack[] storage, armor;
        final ItemStack offHand, cursor;
        final long createdAt;
        UUID entityId;
        boolean armed, dead;
        Location location;

        GhostRecord(ItemStack[] storage, ItemStack[] armor, ItemStack offHand, ItemStack cursor, long createdAt,
                UUID entityId, boolean armed, boolean dead, Location location) {
            this.storage = copy(storage);
            this.armor = copy(armor);
            this.offHand = copy(offHand);
            this.cursor = copy(cursor);
            this.createdAt = createdAt;
            this.entityId = entityId;
            this.armed = armed;
            this.dead = dead;
            this.location = location;
        }

        static GhostRecord capture(Player player, long now) {
            PlayerInventory inventory = player.getInventory();
            return new GhostRecord(inventory.getStorageContents(), inventory.getArmorContents(), inventory.getItemInOffHand(),
                    player.getItemOnCursor(), now, null, false, false, player.getLocation());
        }

        List<ItemStack> allItems() {
            List<ItemStack> items = new ArrayList<>();
            addNonEmpty(items, storage);
            addNonEmpty(items, armor);
            if (offHand != null && !offHand.getType().isAir()) items.add(offHand.clone());
            if (cursor != null && !cursor.getType().isAir()) items.add(cursor.clone());
            return items;
        }

        static void addNonEmpty(List<ItemStack> target, ItemStack[] source) {
            for (ItemStack item : source) if (item != null && !item.getType().isAir()) target.add(item.clone());
        }
    }
}
