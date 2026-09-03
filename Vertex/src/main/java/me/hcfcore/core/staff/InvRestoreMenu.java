package me.hcfcore.core.staff;

import me.hcfcore.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class InvRestoreMenu {

    public static final String DEATH_INDEX_KEY = "death_index";
    public static final String DEATH_ACTION_KEY = "death_action";
    public static final String TARGET_UUID_KEY = "target_uuid";
    public static final String CONTENTS_VIEW = "contents_view";

    private static final int ROW_WIDTH = 9;
    private static final int GRID_SIZE = 6 * ROW_WIDTH;
    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT = ThreadLocal.withInitial(() -> new SimpleDateFormat("MM/dd HH:mm:ss"));

    public static void open(Player staffPlayer, Player targetPlayer, Plugin plugin, DeathManager deathManager, Messages messages) {
        openByUUID(staffPlayer, targetPlayer.getUniqueId(), plugin, deathManager, messages);
    }

    public static void openByUUID(Player staffPlayer, java.util.UUID targetUUID, Plugin plugin, DeathManager deathManager, Messages messages) {
        deathManager.loadDeaths(targetUUID, 20, deaths -> {
            if (deaths.isEmpty()) {
                staffPlayer.sendMessage(messages.getChat(staffPlayer, "staff.no-deaths-found"));
                return;
            }

            Holder holder = new Holder(deaths, targetUUID);
            Inventory inventory = Bukkit.createInventory(holder, GRID_SIZE,
                    Component.text("ᴅᴇᴀᴛʜs")
                            .color(NamedTextColor.LIGHT_PURPLE));
            holder.inventory = inventory;

            NamespacedKey deathIndexKey = new NamespacedKey(plugin, DEATH_INDEX_KEY);
            NamespacedKey deathActionKey = new NamespacedKey(plugin, DEATH_ACTION_KEY);

            for (int i = 0; i < deaths.size() && i < 45; i++) {
                Death death = deaths.get(i);
                ItemStack icon = createDeathIcon(death, i, targetUUID, staffPlayer, messages);
                ItemMeta meta = icon.getItemMeta();
                if (meta != null) {
                    meta.getPersistentDataContainer().set(deathIndexKey, PersistentDataType.INTEGER, i);
                    meta.getPersistentDataContainer().set(deathActionKey, PersistentDataType.STRING, "restore");
                    icon.setItemMeta(meta);
                }
                inventory.setItem(i, icon);
            }

            staffPlayer.openInventory(inventory);
        });
    }

    public static void openContentsView(Player staffPlayer, Death death, Plugin plugin, java.util.UUID targetUUID, Messages messages) {
        Holder holder = new Holder(List.of(death), targetUUID);
        Inventory inventory = Bukkit.createInventory(holder, GRID_SIZE,
                Component.text("ᴅᴇᴀᴛʜ ᴄᴏɴᴛᴇɴᴛs")
                        .color(NamedTextColor.LIGHT_PURPLE));
        holder.inventory = inventory;

        NamespacedKey deathActionKey = new NamespacedKey(plugin, DEATH_ACTION_KEY);
        int slot = 0;

        List<ItemStack> items = death.getAllItems();
        for (ItemStack item : items) {
            if (slot >= 45) break;
            if (item != null && !item.getType().isAir()) {
                ItemStack displayItem = item.clone();
                ItemMeta meta = displayItem.getItemMeta();
                if (meta != null) {
                    meta.getPersistentDataContainer().set(deathActionKey, PersistentDataType.STRING, "none");
                    displayItem.setItemMeta(meta);
                }
                inventory.setItem(slot, displayItem);
            }
            slot++;
        }

        ItemStack backButton = new ItemStack(Material.BARRIER);
        ItemMeta backMeta = backButton.getItemMeta();
        if (backMeta != null) {
            String backText = messages.getRaw(staffPlayer, "staff.menu-back");
            backMeta.displayName(Component.text(backText)
                    .color(NamedTextColor.RED)
                    .decorate(TextDecoration.BOLD));
            backMeta.getPersistentDataContainer().set(deathActionKey, PersistentDataType.STRING, "back");
            backButton.setItemMeta(backMeta);
        }
        inventory.setItem(GRID_SIZE - 1, backButton);

        staffPlayer.openInventory(inventory);
    }

    private static ItemStack createDeathIcon(Death death, int index, java.util.UUID playerUUID, Player player, Messages messages) {
        ItemStack icon = new ItemStack(Material.SKELETON_SKULL);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            String deathTime = DATE_FORMAT.get().format(new Date(death.getTimestamp()));
            String cause = death.getCause().replace("_", " ");
            String killer = death.getKillerName() != null ? death.getKillerName() : "Environment";

            meta.displayName(Component.text("#" + (index + 1) + " - " + deathTime)
                    .color(NamedTextColor.RED)
                    .decorate(TextDecoration.BOLD));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(playerUUID.toString()).color(NamedTextColor.GRAY));
            lore.add(Component.empty());
            String causeText = messages.getRaw(player, "staff.death-cause").replace("{cause}", cause);
            String killerText = messages.getRaw(player, "staff.death-killer").replace("{killer}", killer);
            lore.add(Component.text(causeText).color(NamedTextColor.GRAY));
            lore.add(Component.text(killerText).color(NamedTextColor.GRAY));
            lore.add(Component.empty());
            lore.add(Component.text(messages.getRaw(player, "staff.death-lore-left"))
                    .color(NamedTextColor.YELLOW));
            lore.add(Component.text(messages.getRaw(player, "staff.death-lore-right"))
                    .color(NamedTextColor.YELLOW));

            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    public static class Holder implements InventoryHolder {
        public final List<Death> deaths;
        public final java.util.UUID targetUUID;
        Inventory inventory;

        public Holder(List<Death> deaths, java.util.UUID targetUUID) {
            this.deaths = deaths;
            this.targetUUID = targetUUID;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
