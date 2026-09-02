package me.hcfcore.core.kit;

import me.hcfcore.core.storage.Storage;
import me.hcfcore.core.user.User;
import me.hcfcore.core.user.UserManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
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
    private final File file;
    private final Map<String, Kit> kits = new LinkedHashMap<>();
    // Single thread so concurrent /kit save and /kit delete calls persist in
    // the order they happened, instead of racing on kits.yml.
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "HCFCore-KitIO");
        thread.setDaemon(true);
        return thread;
    });

    public KitManager(Plugin plugin, Storage storage, UserManager userManager) {
        this.plugin = plugin;
        this.storage = storage;
        this.userManager = userManager;
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
            kits.put(name.toLowerCase(Locale.ROOT), new Kit(name, permission, cooldown, armor, contents));
        }
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
            player.sendMessage(Component.text("You don't have permission to use that kit.", NamedTextColor.RED));
            return;
        }

        String key = kit.getName().toLowerCase(Locale.ROOT);
        User user = userManager.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        long expiry = user == null ? 0L : user.getCooldownExpiry(key);

        if (expiry > now && !player.hasPermission("hcfcore.kit.bypasscooldown")) {
            long remaining = (expiry - now) / 1000L;
            player.sendMessage(Component.text("You can use this kit again in " + remaining + "s.", NamedTextColor.RED));
            return;
        }

        PlayerInventory inventory = player.getInventory();
        inventory.setArmorContents(kit.getArmor());
        for (ItemStack item : kit.getContents()) {
            if (item != null) {
                inventory.addItem(item);
            }
        }

        player.sendMessage(Component.text("Applied kit " + kit.getName() + ".", NamedTextColor.GREEN));

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

    public void save(String name, Player player, String permission, int cooldownSeconds) {
        PlayerInventory inventory = player.getInventory();
        Kit kit = new Kit(name, permission, cooldownSeconds, inventory.getArmorContents(), inventory.getContents());
        kits.put(name.toLowerCase(Locale.ROOT), kit);
        persistAsync();
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
