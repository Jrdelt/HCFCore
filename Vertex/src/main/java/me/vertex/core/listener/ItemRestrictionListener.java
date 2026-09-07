package me.vertex.core.listener;

import me.vertex.core.lang.Messages;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Configurable item-use and crafting-result restrictions. */
public final class ItemRestrictionListener implements Listener {

    private final Plugin plugin;
    private final Messages messages;
    private Set<Material> disabledItems = Set.of();
    private Set<Material> disabledCraftingResults = Set.of();

    public ItemRestrictionListener(Plugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        reload();
    }

    public void reload() {
        FileConfiguration config = plugin.getConfig();
        disabledItems = readMaterials(config, "item-restrictions.disabled-items");
        disabledCraftingResults = readMaterials(config, "item-restrictions.disabled-crafting-results");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemUse(PlayerInteractEvent event) {
        if (!isDisabled(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(messages.get(event.getPlayer(), "item-restrictions.item-disabled"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        // Only block moving a disabled item into the offhand. Moving an
        // existing shield back out remains possible, and every other item
        // (including Backpacks) can use the offhand normally.
        if (!isDisabled(event.getMainHandItem())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(messages.get(event.getPlayer(), "item-restrictions.item-disabled"));
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (isCraftingDisabled(event.getInventory().getResult())) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!isCraftingDisabled(event.getRecipe().getResult())) {
            return;
        }
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player) {
            player.sendMessage(messages.get(player, "item-restrictions.crafting-disabled"));
        }
    }

    private Set<Material> readMaterials(FileConfiguration config, String path) {
        Set<Material> materials = EnumSet.noneOf(Material.class);
        for (String configured : config.getStringList(path)) {
            Material material = Material.matchMaterial(configured == null ? "" : configured.toUpperCase(Locale.ROOT));
            if (material == null || material.isAir()) {
                plugin.getLogger().warning("Ignoring invalid item restriction '" + configured + "' at " + path + '.');
                continue;
            }
            materials.add(material);
        }
        return Set.copyOf(materials);
    }

    private boolean isDisabled(ItemStack item) {
        return item != null && disabledItems.contains(item.getType());
    }

    private boolean isCraftingDisabled(ItemStack item) {
        return item != null && disabledCraftingResults.contains(item.getType());
    }
}
