package me.hcfcore.core.kit;

import me.hcfcore.core.economy.EconomyHook;
import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.user.User;
import me.hcfcore.core.user.UserManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.milkbowl.vault.economy.Economy;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Left-click an icon to claim that kit, right-click to preview its
 * contents without claiming it. Each icon is tagged via PersistentDataContainer
 * with the kit's id so KitMenuListener can identify it without a slot map.
 */
public final class KitsMenu {

    public static final String KIT_ID_KEY = "kit_id";

    public static void open(Player player, Plugin plugin, KitManager kitManager, UserManager userManager,
                             Messages messages) {
        List<Kit> kits = new ArrayList<>(kitManager.getKits().values());
        int size = Math.max(9, ((kits.size() / 9) + 1) * 9);

        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, size, messages.get(player, "kit.gui-title"));
        holder.inventory = inventory;

        NamespacedKey kitIdKey = new NamespacedKey(plugin, KIT_ID_KEY);
        User user = userManager.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        Economy economy = EconomyHook.getEconomy();
        boolean bypassCost = player.hasPermission("hcfcore.kit.bypasscost");

        int slot = 0;
        for (Kit kit : kits) {
            String key = kit.getName().toLowerCase(Locale.ROOT);
            ItemStack icon = buildIcon(kit);
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(Component.text(kit.getName(), NamedTextColor.GOLD));

            List<Component> lore = new ArrayList<>();
            boolean hasPermission = kit.getPermission() == null || kit.getPermission().isEmpty()
                    || player.hasPermission(kit.getPermission());
            lore.add(hasPermission
                    ? messages.get(player, "kit.gui-access")
                    : messages.get(player, "kit.gui-no-access"));

            long expiry = user == null ? 0L : user.getCooldownExpiry(key);
            if (expiry > now) {
                long remaining = (expiry - now) / 1000L;
                lore.add(messages.get(player, "kit.gui-cooldown", "seconds", String.valueOf(remaining)));
            } else {
                lore.add(messages.get(player, "kit.gui-ready"));
            }

            Kit.Cost cost = kit.getCost();
            if (cost.hasMoneyCost()) {
                boolean canAfford = bypassCost || (economy != null && economy.has(player, cost.money()));
                lore.add(colorize(messages.get(player, "kit.gui-cost-money", "amount", EconomyHook.format(cost.money())),
                        canAfford));
            }
            if (cost.hasItemCost()) {
                boolean canAfford = bypassCost
                        || player.getInventory().containsAtLeast(new ItemStack(cost.itemType()), cost.itemAmount());
                lore.add(colorize(messages.get(player, "kit.gui-cost-item",
                        "amount", String.valueOf(cost.itemAmount()), "item", KitManager.formatMaterial(cost.itemType())),
                        canAfford));
            }
            lore.add(Component.empty());
            lore.add(messages.get(player, "kit.gui-left-click"));
            lore.add(messages.get(player, "kit.gui-right-click"));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(kitIdKey, PersistentDataType.STRING, kit.getName());
            icon.setItemMeta(meta);

            inventory.setItem(slot++, icon);
        }

        player.openInventory(inventory);
    }

    private static Component colorize(Component costLine, boolean canAfford) {
        return costLine.color(canAfford ? NamedTextColor.GREEN : NamedTextColor.RED);
    }

    private static ItemStack buildIcon(Kit kit) {
        for (ItemStack item : kit.getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                return item.clone();
            }
        }
        for (ItemStack item : kit.getArmor()) {
            if (item != null && item.getType() != Material.AIR) {
                return item.clone();
            }
        }
        return new ItemStack(Material.CHEST);
    }

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
