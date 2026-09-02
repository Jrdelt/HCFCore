package me.hcfcore.core.ability;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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

    public static void open(Player player, Plugin plugin, AbilityManager abilityManager) {
        List<Ability> abilities = new ArrayList<>(abilityManager.getAbilities().values());
        int size = Math.max(9, ((abilities.size() / 9) + 1) * 9);

        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, size, Component.text("Abilities", NamedTextColor.DARK_AQUA));
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
                    ? Component.text("Click to receive one.", NamedTextColor.GRAY)
                    : Component.text("Cooldown: " + ability.getCooldownSeconds() + "s", NamedTextColor.GRAY));
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
