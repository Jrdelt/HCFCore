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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class KitManager {

    private final Plugin plugin;
    private final Storage storage;
    private final UserManager userManager;
    private final Messages messages;
    private final File file;
    private final Map<String, Kit> kits = new LinkedHashMap<>();
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
        this.file = new File(plugin.getDataFolder(), "kits.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("kits.yml", false);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = config.getConfigurationSection("kits");
        kits.clear();
        if (root == null) {
            return;
        }

        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section == null) {
                continue;
            }
            String permission = section.getString("permission", "hcfcore.kit." + name.toLowerCase(Locale.ROOT));
            int cooldown = section.getInt("cooldown-seconds", 0);
            ItemStack[] armor = readItems(section, "armor");
            ItemStack[] contents = readItems(section, "contents");
            Kit.Cost cost = readCost(section.getConfigurationSection("cost"));
            kits.put(name.toLowerCase(Locale.ROOT), new Kit(name, permission, cooldown, armor, contents, cost));
        }
    }

    private Kit.Cost readCost(ConfigurationSection section) {
        if (section == null) {
            return Kit.Cost.NONE;
        }
        double money = section.getDouble("money", 0.0);
        Material itemType = null;
        String itemName = section.getString("item");
        if (itemName != null && !itemName.isEmpty()) {
            try {
                itemType = Material.valueOf(itemName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().log(Level.WARNING, "Unknown cost item material '" + itemName + "' in kits.yml", e);
            }
        }
        int itemAmount = section.getInt("item-amount", 1);
        return new Kit.Cost(money, itemType, itemType == null ? 0 : itemAmount);
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

    private ItemStack[] readItems(ConfigurationSection section, String path) {
        List<?> raw = section.getList(path);
        if (raw == null) {
            return new ItemStack[0];
        }
        ItemStack[] items = new ItemStack[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            items[i] = raw.get(i) instanceof ItemStack stack ? stack : null;
        }
        return items;
    }

    public Kit get(String name) {
        return kits.get(name.toLowerCase(Locale.ROOT));
    }

    public Map<String, Kit> getKits() {
        return kits;
    }

    public void apply(Player player, Kit kit) {
        if (kit.getPermission() != null && !kit.getPermission().isEmpty() && !player.hasPermission(kit.getPermission())) {
            player.sendMessage(messages.get(player, "kit.no-kit-permission"));
            return;
        }

        String key = kit.getName().toLowerCase(Locale.ROOT);
        User user = userManager.get(player.getUniqueId());
        if (user == null && userManager.hasFailedLoad(player.getUniqueId())) {
            player.sendMessage(messages.get(player, "general.data-unavailable"));
            return;
        }
        long now = System.currentTimeMillis();
        long expiry = user == null ? 0L : user.getCooldownExpiry(key);

        if (expiry > now && !player.hasPermission("hcfcore.kit.bypasscooldown")) {
            long remaining = (expiry - now) / 1000L;
            player.sendMessage(messages.get(player, "kit.cooldown", "seconds", String.valueOf(remaining)));
            return;
        }

        PlayerInventory inventory = player.getInventory();
        Kit.Cost cost = kit.getCost();
        boolean bypassCost = player.hasPermission("hcfcore.kit.bypasscost");

        // Check the side-effect-free item cost first, so a failed money
        // check below never leaves us having already removed items.
        if (!bypassCost && cost.hasItemCost() && !inventory.containsAtLeast(new ItemStack(cost.itemType()), cost.itemAmount())) {
            player.sendMessage(messages.get(player, "kit.cost-item-needed",
                    "amount", String.valueOf(cost.itemAmount()), "item", formatMaterial(cost.itemType())));
            return;
        }

        if (!bypassCost && cost.hasMoneyCost()) {
            Economy economy = EconomyHook.getEconomy();
            if (economy == null) {
                player.sendMessage(messages.get(player, "kit.cost-no-economy"));
                return;
            }
            if (!economy.has(player, cost.money())) {
                player.sendMessage(messages.get(player, "kit.cost-money-needed", "amount", EconomyHook.format(cost.money())));
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
            player.sendMessage(messages.get(player, "kit.inventory-full"));
            return;
        }

        if (!bypassCost && cost.hasMoneyCost()) {
            Economy economy = EconomyHook.getEconomy();
            EconomyResponse response = economy.withdrawPlayer(player, cost.money());
            if (!response.transactionSuccess()) {
                player.sendMessage(messages.get(player, "kit.cost-withdraw-failed"));
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

        player.sendMessage(messages.get(player, "kit.applied", "kit", kit.getName()));

        if (kit.getCooldownSeconds() > 0 && user != null) {
            long newExpiry = now + kit.getCooldownSeconds() * 1000L;
            user.setCooldownExpiry(key, newExpiry);

            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    storage.saveCooldown(player.getUniqueId(), key, newExpiry);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to persist kit cooldown for " + player.getUniqueId(), e);
                }
            });
        }
    }

    public void save(String name, Player player, String permission, int cooldownSeconds, Kit.Cost cost) {
        PlayerInventory inventory = player.getInventory();
        Kit kit = new Kit(name, permission, cooldownSeconds,
            fromBukkitArmorOrder(inventory.getArmorContents()), inventory.getStorageContents(), cost);
        kits.put(name.toLowerCase(Locale.ROOT), kit);
        persistAsync();
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
        return true;
    }

    /**
     * Snapshots the in-memory kits (main thread, cheap) and writes the
     * whole file back out on the IO thread, so /kit save and /kit delete
     * never block the main thread on disk.
     */
    private void persistAsync() {
        List<Kit> snapshot = List.copyOf(kits.values());
        ioExecutor.submit(() -> {
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
                config.set(path + ".armor", Arrays.asList(kit.getArmor()));
                config.set(path + ".contents", Arrays.asList(kit.getContents()));
            }
            try {
                config.save(file);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save kits.yml", e);
            }
        });
    }

    /**
     * Flushes any pending kit saves/deletes before the plugin shuts down.
     */
    public void shutdown() {
        ioExecutor.shutdown();
        try {
            ioExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
