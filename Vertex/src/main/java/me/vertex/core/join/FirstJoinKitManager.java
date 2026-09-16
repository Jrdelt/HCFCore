package me.vertex.core.join;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

/**
 * The exact inventory of whoever last ran {@code /firsttimejoin kit},
 * armor/offhand/main storage and all, replayed once onto every player the
 * very first time they ever join. Backed by {@code first-join-kit.yml};
 * {@link ItemStack} is a {@code ConfigurationSerializable}, so the round
 * trip through {@link YamlConfiguration} preserves enchantments, custom
 * model data, and any other plugin's PDC data exactly as staged.
 */
public final class FirstJoinKitManager implements Listener {

    private final Plugin plugin;
    private final File file;
    private volatile List<ItemStack> armor = List.of();
    private volatile List<ItemStack> contents = List.of();
    private volatile boolean configured;

    public FirstJoinKitManager(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "first-join-kit.yml");
    }

    public void load() {
        if (!file.exists()) {
            configured = false;
            armor = List.of();
            contents = List.of();
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        configured = config.getBoolean("configured", false);
        armor = readItems(config, "armor");
        contents = readItems(config, "contents");
    }

    private static List<ItemStack> readItems(YamlConfiguration config, String path) {
        List<?> raw = config.getList(path);
        if (raw == null) {
            return List.of();
        }
        List<ItemStack> items = new ArrayList<>(raw.size());
        for (Object entry : raw) {
            items.add(entry instanceof ItemStack item ? item : null);
        }
        return items;
    }

    public boolean isConfigured() {
        return configured;
    }

    /** Snapshots {@code player}'s current armor, offhand, and main storage as the new first-join kit. */
    public void save(Player player) {
        // Arrays.asList, not List.of -- an empty armor/hotbar/storage slot is a
        // null element in these arrays, and List.of forbids nulls outright.
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> newArmor = new ArrayList<>(Arrays.asList(inventory.getArmorContents()));
        List<ItemStack> newContents = new ArrayList<>();
        newContents.add(inventory.getItemInOffHand());
        newContents.addAll(Arrays.asList(inventory.getStorageContents()));
        this.armor = newArmor;
        this.contents = newContents;
        this.configured = true;
        persist(newArmor, newContents);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!configured || event.getPlayer().hasPlayedBefore()) {
            return;
        }
        Player player = event.getPlayer();
        PlayerInventory inventory = player.getInventory();
        inventory.setArmorContents(armor.toArray(new ItemStack[0]));
        if (!contents.isEmpty()) {
            inventory.setItemInOffHand(contents.get(0));
            inventory.setStorageContents(contents.subList(1, contents.size()).toArray(new ItemStack[0]));
        }
    }

    private void persist(List<ItemStack> armor, List<ItemStack> contents) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("configured", true);
        config.set("armor", armor);
        config.set("contents", contents);
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save first-join-kit.yml", e);
        }
    }
}
