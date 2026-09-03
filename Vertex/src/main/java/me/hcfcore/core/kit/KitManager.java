package me.hcfcore.core.kit;

import me.hcfcore.core.economy.EconomyHook;
import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.storage.Storage;
import me.hcfcore.core.user.User;
import me.hcfcore.core.user.UserManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.yaml.snakeyaml.Yaml;
import org.bukkit.scheduler.BukkitTask;

public final class KitManager {

    private final Plugin plugin;
    private final Storage storage;
    private final UserManager userManager;
    private final Messages messages;
    private final AtomicLong saveGeneration = new AtomicLong();
    private volatile boolean shuttingDown;
    private volatile Future<?> pendingPersist;
    private final Object persistLock = new Object();
    private final Map<UUID, Long> effectWarmups = new ConcurrentHashMap<>();
    private final AtomicLong effectWarmupGeneration = new AtomicLong();
    private final Map<UUID, Kit> activeEffectKits = new ConcurrentHashMap<>();
    private final java.util.Set<CompletableFuture<Void>> pendingCooldownWrites = ConcurrentHashMap.newKeySet();
    private BukkitTask armorMonitorTask;
    private final Map<String, Kit> kits = new LinkedHashMap<>();
    private volatile List<Kit> kitsWithEffects = new ArrayList<>();
    // Single thread so concurrent /kit save and /kit delete calls persist in
    // the order they happened, instead of racing on kits.yml.
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "HCFCore-KitIO");
        thread.setDaemon(true);
        return thread;
    });

    public KitManager(Plugin plugin, Storage storage, UserManager userManager, Messages messages) {
        this.plugin = plugin;
        this.storage = storage;
        this.userManager = userManager;
        this.messages = messages;
    }

    private File resolveFile() {
        File dataFolder = plugin.getDataFolder();
        if (dataFolder == null) {
            return new File("kits.yml");
        }
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            return new File(dataFolder, "kits.yml");
        }
        return new File(dataFolder, "kits.yml");
    }

    public void load() {
        File configFile = resolveFile();
        if (!configFile.exists()) {
            plugin.saveResource("kits.yml", false);
        }

        Map<String, Object> root = loadRawConfig(configFile);
        kits.clear();
        if (root == null || root.isEmpty()) {
            return;
        }

        Object kitsValue = root.get("kits");
        if (!(kitsValue instanceof Map<?, ?> kitsMap)) {
            return;
        }

        for (Map.Entry<?, ?> entry : kitsMap.entrySet()) {
            String name = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (!(value instanceof Map<?, ?> sectionMap)) {
                continue;
            }

            String permission = asString(sectionMap.get("permission"), "hcfcore.kit." + name.toLowerCase(Locale.ROOT));
            int cooldown = asInt(sectionMap.get("cooldown-seconds"),
                    plugin.getConfig().getInt("kits.default-cooldown-seconds", 0));
            ItemStack[] armor = readItems(sectionMap.get("armor"));
            ItemStack[] contents = readItems(sectionMap.get("contents"));
            Kit.Cost cost = readCost(sectionMap.get("cost"));
            List<Kit.Effect> effects = readEffects(sectionMap.get("effects"));
            String icon = asString(sectionMap.get("icon"), null);
            String purpose = asString(sectionMap.get("purpose"), null);
            kits.put(name.toLowerCase(Locale.ROOT),
                    new Kit(name, permission, cooldown, armor, contents, cost, effects, icon, purpose));
        }

        // Rebuild cache of kits with effects for armor checking optimization
        List<Kit> effectKits = new ArrayList<>();
        for (Kit kit : kits.values()) {
            if (!kit.getEffects().isEmpty()) {
                effectKits.add(kit);
            }
        }
        kitsWithEffects = effectKits;
    }

    private Map<String, Object> loadRawConfig(File file) {
        try (InputStream stream = new FileInputStream(file)) {
            Object loaded = new Yaml().load(stream);
            if (loaded instanceof Map<?, ?> root) {
                return toStringMap(root);
            }
            return Collections.emptyMap();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to parse kits.yml with raw YAML fallback; resetting kit data.", e);
            return Collections.emptyMap();
        }
    }

    private static Map<String, Object> toStringMap(Map<?, ?> source) {
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            converted.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return converted;
    }

    private Kit.Cost readCost(Object section) {
        if (!(section instanceof Map<?, ?> costMap)) {
            return Kit.Cost.NONE;
        }
        double money = asDouble(costMap.get("money"), 0.0);
        Material itemType = null;
        Object itemName = costMap.get("item");
        if (itemName != null) {
            try {
                itemType = Material.valueOf(String.valueOf(itemName).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().log(Level.WARNING, "Unknown cost item material '" + itemName + "' in kits.yml", e);
            }
        }
        int itemAmount = asInt(costMap.get("item-amount"), 1);
        return new Kit.Cost(money, itemType, itemType == null ? 0 : itemAmount);
    }

    private List<Kit.Effect> readEffects(Object rawValue) {
        if (!(rawValue instanceof List<?> raw)) {
            return List.of();
        }
        List<Kit.Effect> effects = new ArrayList<>();
        for (Object entry : raw) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Object typeValue = map.get("type");
            if (typeValue == null) {
                continue;
            }
            PotionEffectType type = Registry.EFFECT.get(
                    NamespacedKey.minecraft(String.valueOf(typeValue).toLowerCase(Locale.ROOT)));
            if (type == null) {
                plugin.getLogger().warning("Unknown potion effect type '" + typeValue + "' in kits.yml");
                continue;
            }
            int amplifier = Math.max(0, asInt(map.get("amplifier"), 0));
            effects.add(new Kit.Effect(type, amplifier));
        }
        return effects;
    }

    private static String asString(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static int asInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static double asDouble(Object value, double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    static String formatMaterial(Material material) {
        String[] words = material.name().split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return result.toString();
    }

    private ItemStack[] readItems(Object rawValue) {
        if (!(rawValue instanceof List<?> raw)) {
            return new ItemStack[0];
        }
        ItemStack[] items = new ItemStack[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            items[i] = parseItem(raw.get(i));
        }
        return items;
    }

    private ItemStack parseItem(Object value) {
        if (value instanceof ItemStack itemStack) {
            return itemStack.clone();
        }
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Object materialValue = map.get("material");
        if (materialValue == null) {
            materialValue = map.get("type");
        }
        if (materialValue == null) {
            return null;
        }
        Material material;
        try {
            material = Material.valueOf(String.valueOf(materialValue).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        int amount = asInt(map.get("amount"), asInt(map.get("count"), 1));
        ItemStack item = new ItemStack(material, Math.max(1, amount));
        applyEnchantments(item, map.get("enchantments"));
        applyPotionEffect(item, map);
        Object abilityId = map.get("ability");
        if (abilityId != null) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "ability_id"),
                    org.bukkit.persistence.PersistentDataType.STRING, String.valueOf(abilityId));
                item.setItemMeta(meta);
            }
        }
        return item;
    }

    private void applyPotionEffect(ItemStack item, Map<?, ?> map) {
        if (!(item.getItemMeta() instanceof PotionMeta meta) || map.get("potion-effect") == null) {
            return;
        }
        PotionEffectType type = Registry.EFFECT.get(
                NamespacedKey.minecraft(String.valueOf(map.get("potion-effect")).toLowerCase(Locale.ROOT)));
        if (type == null) {
            return;
        }
        int duration = Math.max(1, asInt(map.get("potion-duration-ticks"), 100));
        int amplifier = Math.max(0, asInt(map.get("potion-amplifier"), 0));
        meta.addCustomEffect(new PotionEffect(type, duration, amplifier), true);
        item.setItemMeta(meta);
    }

    private static void applyEnchantments(ItemStack item, Object rawValue) {
        if (!(rawValue instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Enchantment enchantment = Registry.ENCHANTMENT.get(
                    NamespacedKey.minecraft(String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT)));
            if (enchantment == null) {
                continue;
            }
            int level = asInt(entry.getValue(), 1);
            item.addUnsafeEnchantment(enchantment, Math.max(1, level));
        }
    }

    private static List<Map<String, Object>> serializeItems(ItemStack[] items) {
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (ItemStack item : items) {
            if (item == null || item.getType() == Material.AIR) {
                serialized.add(null);
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("material", item.getType().name());
            entry.put("amount", item.getAmount());
            if (!item.getEnchantments().isEmpty()) {
                Map<String, Object> enchantments = new LinkedHashMap<>();
                for (Map.Entry<Enchantment, Integer> enchant : item.getEnchantments().entrySet()) {
                    enchantments.put(enchant.getKey().getKey().getKey().toUpperCase(Locale.ROOT), enchant.getValue());
                }
                entry.put("enchantments", enchantments);
            }
            serialized.add(entry);
        }
        return serialized;
    }

    private static List<Map<String, Object>> serializeEffects(List<Kit.Effect> effects) {
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (Kit.Effect effect : effects) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", effect.type().getKey().getKey().toUpperCase(Locale.ROOT));
            entry.put("amplifier", effect.amplifier());
            serialized.add(entry);
        }
        return serialized;
    }

    public Kit get(String name) {
        return kits.get(name.toLowerCase(Locale.ROOT));
    }

    public Map<String, Kit> getKits() {
        return Collections.unmodifiableMap(kits);
    }

    public void start() {
        stopArmorMonitor();
        armorMonitorTask = plugin.getServer().getScheduler().runTaskTimer(plugin,
            this::checkArmorEffects, 1L, 1L);
    }

    public void apply(Player player, Kit kit) {
        if (kit.getPermission() != null && !kit.getPermission().isEmpty() && !player.hasPermission(kit.getPermission())) {
            player.sendMessage(messages.getChat(player, "kit.no-kit-permission"));
            return;
        }

        String key = kit.getName().toLowerCase(Locale.ROOT);
        User user = userManager.get(player.getUniqueId());
        if (user == null) {
            player.sendMessage(messages.getChat(player, "general.data-unavailable"));
            return;
        }
        long now = System.currentTimeMillis();
        long expiry = user.getCooldownExpiry(key);

        if (expiry > now && !player.hasPermission("hcfcore.kit.bypasscooldown")) {
            long remaining = (expiry - now) / 1000L;
            player.sendMessage(messages.getChat(player, "kit.cooldown", "seconds", String.valueOf(remaining)));
            return;
        }

        PlayerInventory inventory = player.getInventory();
        Kit.Cost cost = kit.getCost();
        boolean bypassCost = player.hasPermission("hcfcore.kit.bypasscost");

        // Check the side-effect-free item cost first, so a failed money
        // check below never leaves us having already removed items.
        if (!bypassCost && cost.hasItemCost() && !inventory.containsAtLeast(new ItemStack(cost.itemType()), cost.itemAmount())) {
            player.sendMessage(messages.getChat(player, "kit.cost-item-needed",
                    "amount", String.valueOf(cost.itemAmount()), "item", formatMaterial(cost.itemType())));
            return;
        }

        Economy economy = null;
        if (!bypassCost && cost.hasMoneyCost()) {
            economy = EconomyHook.getEconomy();
            if (economy == null) {
                player.sendMessage(messages.getChat(player, "kit.cost-no-economy"));
                return;
            }
            if (!economy.has(player, cost.money())) {
                player.sendMessage(messages.getChat(player, "kit.cost-money-needed", "amount", EconomyHook.format(cost.money())));
                return;
            }
        }

        ItemStack[] kitArmor = kit.getArmor();
        boolean wearingArmor = hasArmor(inventory.getArmorContents());
        List<ItemStack> itemsToStore = new java.util.ArrayList<>();
        if (wearingArmor) {
            addNonEmpty(itemsToStore, kitArmor);
        }
        addNonEmpty(itemsToStore, kit.getContents());
        if (!canStore(inventory, itemsToStore, !bypassCost && cost.hasItemCost() ? cost : Kit.Cost.NONE)) {
            player.sendMessage(messages.getChat(player, "kit.inventory-full"));
            return;
        }

        if (!bypassCost && cost.hasMoneyCost()) {
            if (economy == null) {
                player.sendMessage(messages.getChat(player, "kit.cost-no-economy"));
                return;
            }
            EconomyResponse response = economy.withdrawPlayer(player, cost.money());
            if (!response.transactionSuccess()) {
                player.sendMessage(messages.getChat(player, "kit.cost-withdraw-failed"));
                return;
            }
        }

        if (!bypassCost && cost.hasItemCost()) {
            inventory.removeItem(new ItemStack(cost.itemType(), cost.itemAmount()));
        }

        if (wearingArmor) {
            inventory.addItem(itemsToStore.toArray(new ItemStack[0]));
        } else {
            inventory.setArmorContents(toBukkitArmorOrder(kitArmor));
            if (!itemsToStore.isEmpty()) {
                inventory.addItem(itemsToStore.toArray(new ItemStack[0]));
            }
        }

        player.sendMessage(messages.getChat(player, "kit.applied", "kit", kit.getName()));

        if (kit.getCooldownSeconds() > 0) {
            long newExpiry = now + kit.getCooldownSeconds() * 1000L;
            user.setCooldownExpiry(key, newExpiry);

            UUID playerId = player.getUniqueId();
            CompletableFuture<Void> write = CompletableFuture.runAsync(() -> {
                try {
                    storage.saveCooldown(playerId, key, newExpiry);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to persist kit cooldown for " + playerId, e);
                }
            }, ioExecutor);
            pendingCooldownWrites.add(write);
            write.whenComplete((ignored, error) -> pendingCooldownWrites.remove(write));
        }
    }

    private void checkArmorEffects() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Kit matchingKit = findArmorKit(player);
            UUID uuid = player.getUniqueId();
            Kit activeKit = activeEffectKits.get(uuid);

            if (matchingKit == null) {
                effectWarmups.remove(uuid);
                if (activeKit != null) {
                    clearEffects(player, activeKit);
                    activeEffectKits.remove(uuid);
                    player.sendMessage(messages.getChat(player, "kit.effects-removed"));
                }
                continue;
            }
            if (activeKit != null && activeKit.getName().equalsIgnoreCase(matchingKit.getName())) {
                // Armor never changed, so this isn't a re-equip -- just
                // silently top up whatever an external source (a PvP
                // potion wearing off, a milk bucket, a totem) stripped,
                // instead of clearing everything and re-running the
                // warmup/message for effects that never actually left.
                reapplyMissingEffects(player, activeKit);
                continue;
            }
            if (activeKit != null) {
                clearEffects(player, activeKit);
                activeEffectKits.remove(uuid);
            }
            if (!matchingKit.getEffects().isEmpty() && !effectWarmups.containsKey(uuid)) {
                startEffectWarmup(player, matchingKit);
            }
        }
    }

    private Kit findArmorKit(Player player) {
        ItemStack[] actualArmor = player.getInventory().getArmorContents();
        for (Kit kit : kitsWithEffects) {
            if (hasSameArmor(actualArmor, toBukkitArmorOrder(kit.getArmor()))) {
                return kit;
            }
        }
        return null;
    }

    private void startEffectWarmup(Player player, Kit kit) {
        int warmupSeconds = Math.max(0, plugin.getConfig().getInt("kits.effect-warmup-seconds", 3));
        if (warmupSeconds == 0) {
            applyEffects(player, kit);
            activeEffectKits.put(player.getUniqueId(), kit);
            return;
        }

        UUID uuid = player.getUniqueId();
        long token = effectWarmupGeneration.incrementAndGet();
        effectWarmups.put(uuid, token);
        player.sendMessage(messages.getChat(player, "kit.effects-warmup",
                "seconds", String.valueOf(warmupSeconds)));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!tokenEquals(uuid, token)) {
                return;
            }
            effectWarmups.remove(uuid, token);
            Player onlinePlayer = Bukkit.getPlayer(uuid);
            if (onlinePlayer == null || findArmorKit(onlinePlayer) != kit) {
                return;
            }
            applyEffects(onlinePlayer, kit);
            activeEffectKits.put(uuid, kit);
        }, warmupSeconds * 20L);
    }

    private boolean tokenEquals(UUID uuid, long token) {
        return effectWarmups.getOrDefault(uuid, -1L) == token;
    }

    private static boolean hasSameArmor(ItemStack[] actual, ItemStack[] expected) {
        if (actual.length != expected.length) {
            return false;
        }
        for (int i = 0; i < actual.length; i++) {
            ItemStack actualItem = actual[i];
            ItemStack expectedItem = expected[i];
            if (actualItem == null || expectedItem == null) {
                if (actualItem != expectedItem) {
                    return false;
                }
            } else if (!withoutWear(actualItem).isSimilar(withoutWear(expectedItem))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A copy of `item` with its durability reset, for comparison only.
     *
     * <p>isSimilar() compares ItemMeta, and durability lives in the meta
     * as Damageable#damage -- so armor that has taken any wear (a creeper
     * explosion chewing all four pieces at once, or just ordinary combat)
     * would stop matching the pristine kit definition and be read as the
     * player having taken the set off. Only wear is normalized away:
     * enchantments and the rest of the meta still have to match, which is
     * what distinguishes e.g. the archer kit from its donator variant.
     */
    private static ItemStack withoutWear(ItemStack item) {
        if (!(item.getItemMeta() instanceof Damageable meta) || !meta.hasDamage()) {
            return item;
        }
        ItemStack copy = item.clone();
        Damageable copyMeta = (Damageable) copy.getItemMeta();
        copyMeta.setDamage(0);
        copy.setItemMeta(copyMeta);
        return copy;
    }

    private static void applyEffects(Player player, Kit kit) {
        for (Kit.Effect effect : kit.getEffects()) {
            player.addPotionEffect(new PotionEffect(effect.type(), Integer.MAX_VALUE, effect.amplifier(), false, false));
        }
    }

    private static void clearEffects(Player player, Kit kit) {
        for (Kit.Effect effect : kit.getEffects()) {
            PotionEffect current = player.getPotionEffect(effect.type());
            if (current != null && current.getAmplifier() == effect.amplifier()) {
                player.removePotionEffect(effect.type());
            }
        }
    }

    /**
     * Re-adds only the effects that are actually absent right now, without
     * touching ones still present (even at a different amplifier -- an
     * external potion, e.g. a PvP splash potion, sharing an effect type
     * with the kit is legitimately overriding it, not signaling the kit
     * effect "fell off"). Called every tick while the tracked kit's armor
     * is still worn, so once an external override naturally expires, the
     * kit's own effect silently reappears the very next tick -- no
     * warmup, no message, since the armor itself never changed.
     */
    private static void reapplyMissingEffects(Player player, Kit kit) {
        for (Kit.Effect effect : kit.getEffects()) {
            if (player.getPotionEffect(effect.type()) == null) {
                player.addPotionEffect(new PotionEffect(effect.type(), Integer.MAX_VALUE, effect.amplifier(), false, false));
            }
        }
    }

    private void stopArmorMonitor() {
        if (armorMonitorTask != null) {
            armorMonitorTask.cancel();
            armorMonitorTask = null;
        }
        effectWarmups.clear();
        activeEffectKits.clear();
    }

    public void save(String name, Player player, String permission, int cooldownSeconds, Kit.Cost cost) {
        PlayerInventory inventory = player.getInventory();
        Kit kit = new Kit(name, permission, cooldownSeconds,
            fromBukkitArmorOrder(inventory.getArmorContents()), inventory.getStorageContents(), cost);
        kits.put(name.toLowerCase(Locale.ROOT), kit);
        persistAsync();
        waitForPendingPersist();
    }

    private static ItemStack[] toBukkitArmorOrder(ItemStack[] armor) {
        return fromBukkitArmorOrder(armor);
    }

    private static ItemStack[] fromBukkitArmorOrder(ItemStack[] armor) {
        ItemStack[] reversed = new ItemStack[armor.length];
        for (int i = 0; i < armor.length; i++) {
            reversed[i] = armor[armor.length - 1 - i];
        }
        return reversed;
    }

    private static boolean hasArmor(ItemStack[] armor) {
        for (ItemStack item : armor) {
            if (item != null && item.getType() != Material.AIR) {
                return true;
            }
        }
        return false;
    }

    private static void addNonEmpty(List<ItemStack> target, ItemStack[] items) {
        for (ItemStack item : items) {
            if (item != null && item.getType() != Material.AIR) {
                target.add(item.clone());
            }
        }
    }

    private static boolean canStore(PlayerInventory inventory, List<ItemStack> items, Kit.Cost cost) {
        if (items.isEmpty()) {
            return true;
        }
        Inventory simulation = Bukkit.createInventory(null, inventory.getStorageContents().length);
        ItemStack[] storage = inventory.getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            simulation.setItem(i, storage[i] == null ? null : storage[i].clone());
        }
        if (cost.hasItemCost()) {
            simulation.removeItem(new ItemStack(cost.itemType(), cost.itemAmount()));
        }
        return simulation.addItem(items.toArray(new ItemStack[0])).isEmpty();
    }

    /**
     * Returns true if a kit with that name existed and was deleted.
     */
    public boolean delete(String name) {
        Kit removed = kits.remove(name.toLowerCase(Locale.ROOT));
        if (removed == null) {
            return false;
        }
        persistAsync();
        waitForPendingPersist();
        return true;
    }

    /**
     * Snapshots the in-memory kits (main thread, cheap) and writes the
     * whole file back out on the IO thread, so /kit save and /kit delete
     * never block the main thread on disk.
     */
    private void persistAsync() {
        List<Kit> snapshot = List.copyOf(kits.values());
        long generation = saveGeneration.incrementAndGet();
        Future<?> task;
        synchronized (persistLock) {
            task = ioExecutor.submit(() -> {
                if (shuttingDown || generation != saveGeneration.get() || !plugin.isEnabled()) {
                    return;
                }

                File target = resolveFile();
                File parent = target.getParentFile();
                if (parent != null) {
                    if (!parent.exists() && !parent.mkdirs()) {
                        plugin.getLogger().warning("Could not create plugin data folder before saving kits.yml: " + parent.getAbsolutePath());
                        return;
                    }
                    if (!parent.isDirectory()) {
                        plugin.getLogger().warning("Plugin data path is not a directory: " + parent.getAbsolutePath());
                        return;
                    }
                }
                if (generation != saveGeneration.get() || shuttingDown) {
                    return;
                }

                YamlConfiguration config = new YamlConfiguration();
                for (Kit kit : snapshot) {
                    String path = "kits." + kit.getName();
                    config.set(path + ".permission", kit.getPermission());
                    config.set(path + ".cooldown-seconds", kit.getCooldownSeconds());
                    Kit.Cost cost = kit.getCost();
                    config.set(path + ".cost.money", cost.money());
                    if (cost.itemType() != null) {
                        config.set(path + ".cost.item", cost.itemType().name());
                        config.set(path + ".cost.item-amount", cost.itemAmount());
                    }
                    config.set(path + ".armor", serializeItems(kit.getArmor()));
                    config.set(path + ".contents", serializeItems(kit.getContents()));
                    if (!kit.getEffects().isEmpty()) {
                        config.set(path + ".effects", serializeEffects(kit.getEffects()));
                    }
                    if (kit.getIcon() != null && !kit.getIcon().isBlank()) {
                        config.set(path + ".icon", kit.getIcon());
                    }
                    if (kit.getPurpose() != null && !kit.getPurpose().isBlank()) {
                        config.set(path + ".purpose", kit.getPurpose());
                    }
                }

                try {
                    if (generation != saveGeneration.get() || shuttingDown) {
                        return;
                    }
                    if (!target.exists() && !target.createNewFile()) {
                        plugin.getLogger().warning("Could not create kits.yml before saving: " + target.getAbsolutePath());
                        return;
                    }
                    config.save(target);
                } catch (IOException e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to save kits.yml to " + target.getAbsolutePath(), e);
                }
            });
            pendingPersist = task;
        }
    }

    private void waitForPendingPersist() {
        Future<?> task;
        synchronized (persistLock) {
            task = pendingPersist;
        }
        if (task == null) {
            return;
        }
        try {
            task.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Timed out waiting for kits.yml save to finish.", e);
        } finally {
            synchronized (persistLock) {
                if (pendingPersist == task) {
                    pendingPersist = null;
                }
            }
        }
    }

    /**
     * Flushes any pending kit saves/deletes before the plugin shuts down.
     */
    public void shutdown() {
        shuttingDown = true;
        stopArmorMonitor();
        saveGeneration.incrementAndGet();
        waitForPendingPersist();
        awaitCooldownWrites();
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Forcing pending kit writes to stop during shutdown.");
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
    }

    private void awaitCooldownWrites() {
        long timeout = System.currentTimeMillis() + 5000;
        while (true) {
            CompletableFuture<?>[] snapshot = pendingCooldownWrites.toArray(new CompletableFuture[0]);
            if (snapshot.length == 0) {
                return;
            }
            long remaining = timeout - System.currentTimeMillis();
            if (remaining <= 0) {
                plugin.getLogger().warning("Timed out waiting for kit cooldown writes during shutdown.");
                return;
            }
            try {
                CompletableFuture.allOf(snapshot).get(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.TimeoutException e) {
                plugin.getLogger().warning("Timed out waiting for kit cooldown writes during shutdown.");
                return;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed while waiting for kit cooldown writes.", e);
                return;
            }
        }
    }
}
