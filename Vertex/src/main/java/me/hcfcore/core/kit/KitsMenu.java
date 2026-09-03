package me.hcfcore.core.kit;

import me.hcfcore.core.economy.EconomyHook;
import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.user.User;
import me.hcfcore.core.user.UserManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
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
 *
 * Laid out as a fixed 5-row inventory: non-donor kits sit in row 2, each
 * one's donor variant (same name + "-donator") directly below it in row
 * 3, both kept off the leftmost/rightmost columns so nothing touches the
 * inventory's outer edge. Rows 1, 4, and 5 are left as a border.
 */
public final class KitsMenu {

    public static final String KIT_ID_KEY = "kit_id";

    private static final int ROW_WIDTH = 9;
    private static final int GRID_SIZE = 5 * ROW_WIDTH;
    private static final int USABLE_COLUMN_START = 1;
    private static final int USABLE_COLUMN_END = 7;
    private static final int BASE_ROW = 1;
    private static final int DONOR_ROW = 2;

    public static void open(Player player, Plugin plugin, KitManager kitManager, UserManager userManager,
                             Messages messages) {
        List<Kit> allKits = new ArrayList<>(kitManager.getKits().values());
        List<Kit> baseKits = new ArrayList<>();
        List<Kit> donorKits = new ArrayList<>();
        for (Kit kit : allKits) {
            if (kit.getName().toLowerCase(Locale.ROOT).endsWith("-donator")) {
                donorKits.add(kit);
            } else {
                baseKits.add(kit);
            }
        }

        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, GRID_SIZE, messages.get(player, "kit.gui-title"));
        holder.inventory = inventory;

        NamespacedKey kitIdKey = new NamespacedKey(plugin, KIT_ID_KEY);
        User user = userManager.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        Economy economy = EconomyHook.getEconomy();
        boolean bypassCost = player.hasPermission("hcfcore.kit.bypasscost");

        boolean[] donorColumnTaken = new boolean[USABLE_COLUMN_END + 1];
        List<Kit> remainingDonorKits = new ArrayList<>(donorKits);

        int column = USABLE_COLUMN_START;
        for (Kit baseKit : baseKits) {
            if (column > USABLE_COLUMN_END) {
                break;
            }
            inventory.setItem(BASE_ROW * ROW_WIDTH + column,
                    buildIcon(player, baseKit, user, now, economy, bypassCost, messages, kitIdKey));

            String donorName = baseKit.getName() + "-donator";
            Kit matchingDonor = remainingDonorKits.stream()
                    .filter(candidate -> candidate.getName().equalsIgnoreCase(donorName))
                    .findFirst().orElse(null);
            if (matchingDonor != null) {
                remainingDonorKits.remove(matchingDonor);
                inventory.setItem(DONOR_ROW * ROW_WIDTH + column,
                        buildIcon(player, matchingDonor, user, now, economy, bypassCost, messages, kitIdKey));
                donorColumnTaken[column] = true;
            }
            column++;
        }

        // Any donor kit without a same-named base kit (e.g. a donor-only
        // kit) still gets shown, filling whatever donor-row columns the
        // paired kits above didn't already claim.
        int donorColumn = USABLE_COLUMN_START;
        for (Kit leftoverDonor : remainingDonorKits) {
            while (donorColumn <= USABLE_COLUMN_END && donorColumnTaken[donorColumn]) {
                donorColumn++;
            }
            if (donorColumn > USABLE_COLUMN_END) {
                break;
            }
            inventory.setItem(DONOR_ROW * ROW_WIDTH + donorColumn,
                    buildIcon(player, leftoverDonor, user, now, economy, bypassCost, messages, kitIdKey));
            donorColumnTaken[donorColumn] = true;
            donorColumn++;
        }

        player.openInventory(inventory);
    }

    private static ItemStack buildIcon(Player player, Kit kit, User user, long now, Economy economy,
                                        boolean bypassCost, Messages messages, NamespacedKey kitIdKey) {
        String key = kit.getName().toLowerCase(Locale.ROOT);
        ItemStack icon = resolveIconItem(kit);
        ItemMeta meta = icon.getItemMeta();
        meta.displayName(Component.text(kit.getName(), NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

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
        return icon;
    }

    private static Component colorize(Component costLine, boolean canAfford) {
        return costLine.color(canAfford ? NamedTextColor.GREEN : NamedTextColor.RED);
    }

    private static ItemStack resolveIconItem(Kit kit) {
        for (ItemStack item : kit.getArmor()) {
            if (item != null && item.getType() != Material.AIR) {
                return item.clone();
            }
        }
        for (ItemStack item : kit.getContents()) {
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
