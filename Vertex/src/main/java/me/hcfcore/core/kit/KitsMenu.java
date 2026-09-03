package me.hcfcore.core.kit;

import me.hcfcore.core.economy.EconomyHook;
import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.user.User;
import me.hcfcore.core.user.UserManager;
import me.hcfcore.core.lang.MessageFormatter;
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
 * Laid out as a fixed 4-row inventory: non-donor kits sit in row 2, each
 * one's donor variant (same name + "-donator") directly below it in row
 * 3, both kept off the leftmost/rightmost columns so nothing touches the
 * inventory's outer edge. Rows 1 and 4 are left as a border.
 */
public final class KitsMenu {

    public static final String KIT_ID_KEY = "kit_id";
    public static final String PAGE_ACTION_KEY = "kit_page_action";

    private static final int ROW_WIDTH = 9;
    private static final int GRID_SIZE = 4 * ROW_WIDTH;
    private static final int USABLE_COLUMN_START = 1;
    private static final int USABLE_COLUMN_END = 7;
    private static final int BASE_ROW = 1;
    private static final int DONOR_ROW = 2;

    public static void open(Player player, Plugin plugin, KitManager kitManager, UserManager userManager,
                             Messages messages) {
        open(player, plugin, kitManager, userManager, messages, 0);
    }

    public static void open(Player player, Plugin plugin, KitManager kitManager, UserManager userManager,
                             Messages messages, int requestedPage) {
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

        List<Pair> pairs = new ArrayList<>();
        List<Kit> remainingDonorKits = new ArrayList<>(donorKits);
        for (Kit baseKit : baseKits) {
            String donorName = baseKit.getName() + "-donator";
            Kit matchingDonor = remainingDonorKits.stream()
                    .filter(candidate -> candidate.getName().equalsIgnoreCase(donorName))
                    .findFirst().orElse(null);
            if (matchingDonor != null) {
                remainingDonorKits.remove(matchingDonor);
            }
            pairs.add(new Pair(baseKit, matchingDonor));
        }
        for (Kit donorKit : remainingDonorKits) {
            pairs.add(new Pair(null, donorKit));
        }

        int pageCount = Math.max(1, (pairs.size() + 6) / 7);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        Holder holder = new Holder(page, userManager);
        Inventory inventory = Bukkit.createInventory(holder, GRID_SIZE, messages.get(player, "kit.gui-title"));
        holder.inventory = inventory;

        NamespacedKey kitIdKey = new NamespacedKey(plugin, KIT_ID_KEY);
        User user = userManager.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        Economy economy = EconomyHook.getEconomy();
        boolean bypassCost = player.hasPermission("hcfcore.kit.bypasscost");

        int start = page * 7;
        int end = Math.min(pairs.size(), start + 7);
        for (int i = start; i < end; i++) {
            Pair pair = pairs.get(i);
            int column = USABLE_COLUMN_START + i - start;
            if (pair.base() != null) {
                inventory.setItem(BASE_ROW * ROW_WIDTH + column,
                        buildIcon(player, pair.base(), user, now, economy, bypassCost, messages, kitIdKey));
            }
            if (pair.donor() != null) {
                inventory.setItem(DONOR_ROW * ROW_WIDTH + column,
                        buildIcon(player, pair.donor(), user, now, economy, bypassCost, messages, kitIdKey));
            }
        }

        if (page > 0) {
            inventory.setItem(0, pageButton(plugin, player, messages, "kit.gui-previous-page", "previous"));
        }
        if (page < pageCount - 1) {
            inventory.setItem(8, pageButton(plugin, player, messages, "kit.gui-next-page", "next"));
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
        if (kit.getPurpose() != null && !kit.getPurpose().isBlank()) {
            lore.add(MessageFormatter.deserialize(kit.getPurpose()));
        }

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
        if (kit.getIcon() != null && !kit.getIcon().isBlank()) {
            Material override = Material.matchMaterial(kit.getIcon());
            if (override != null) {
                return new ItemStack(override);
            }
        }
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

    private static ItemStack pageButton(Plugin plugin, Player player, Messages messages, String key, String action) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.get(player, key).decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, PAGE_ACTION_KEY), PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    private record Pair(Kit base, Kit donor) {
    }

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private final int page;
        private final UserManager userManager;

        private Holder(int page, UserManager userManager) {
            this.page = page;
            this.userManager = userManager;
        }

        public int page() { return page; }
        public UserManager userManager() { return userManager; }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
