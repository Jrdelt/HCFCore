package me.hcfcore.core.ability;

import me.hcfcore.core.lang.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only catalog for everyone; a viewer with hcfcore.ability.give who
 * clicks an icon receives a copy instead (see AbilityMenuListener).
 */
public final class AbilitiesMenu {

    public static void open(Player player, Plugin plugin, AbilityManager abilityManager, Messages messages) {
        List<Ability> abilities = new ArrayList<>(abilityManager.getAbilities().values());
        int size = Math.max(9, ((abilities.size() / 9) + 1) * 9);

        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, size, messages.get(player, "ability.gui-title"));
        holder.inventory = inventory;

        boolean canGive = player.hasPermission("hcfcore.ability.give");

        int slot = 0;
        for (Ability ability : abilities) {
            ItemStack icon = abilityManager.createItem(ability);
            ItemMeta meta = icon.getItemMeta();
            List<Component> existingLore = meta.lore();
            List<Component> lore = new ArrayList<>(existingLore == null ? List.of() : existingLore);
            lore.add(Component.empty());
            lore.add(canGive
                    ? messages.get(player, "ability.gui-click-to-receive")
                    : messages.get(player, "ability.gui-cooldown", "seconds",
                            String.valueOf(ability.getCooldownSeconds())));
            meta.lore(lore);
            icon.setItemMeta(meta);

            inventory.setItem(slot++, icon);
        }

        player.openInventory(inventory);
    }

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
